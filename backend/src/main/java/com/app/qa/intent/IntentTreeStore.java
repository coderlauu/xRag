package com.app.qa.intent;

import com.app.qa.model.IntentNodeType;
import com.app.qa.model.SystemAction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class IntentTreeStore {

    public static final UUID VIRTUAL_ROOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KnowledgeBaseLookup knowledgeBases;
    private final ObjectMapper json;
    private final Consumer<IntentTreeSnapshot> cachePublisher;
    private final AtomicReference<IntentTreeSnapshot> cache = new AtomicReference<>();

    @Autowired
    public IntentTreeStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            KnowledgeBaseLookup knowledgeBases,
            ObjectMapper json) {
        this(jdbc, transactionManager, knowledgeBases, json, snapshot -> {});
    }

    IntentTreeStore(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            KnowledgeBaseLookup knowledgeBases,
            ObjectMapper json,
            Consumer<IntentTreeSnapshot> cachePublisher) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.knowledgeBases = knowledgeBases;
        this.json = json;
        this.cachePublisher = cachePublisher;
    }

    public IntentTreeSnapshot current() {
        var cached = cache.get();
        if (cached != null) {
            return cached;
        }
        var loaded = loadSnapshot(readHead(false));
        cache.compareAndSet(null, loaded);
        return cache.get();
    }

    public CommitResult create(UUID expectedRevisionId, CreateNode command) {
        Objects.requireNonNull(command, "command");
        if (VIRTUAL_ROOT_ID.equals(command.parentId()) && command.type() != IntentNodeType.GROUP) {
            throw invalid("only GROUP nodes may be attached to the virtual root");
        }
        UUID nodeId = UUID.randomUUID();
        return commit(expectedRevisionId, nodeId, ChangeType.CREATE, nodes -> {
            requireActiveParent(nodes, command.parentId());
            nodes.add(new IntentNode(
                    nodeId,
                    command.parentId(),
                    1,
                    command.sortOrder(),
                    command.type(),
                    command.name(),
                    command.description(),
                    immutableExamples(command.examples()),
                    command.knowledgeBaseId(),
                    command.systemAction(),
                    command.promptSnippet(),
                    false,
                    false));
            return nodes;
        });
    }

    public CommitResult update(UUID expectedRevisionId, UUID nodeId, EditableNode command) {
        Objects.requireNonNull(command, "command");
        rejectVirtualRoot(nodeId);
        return commit(expectedRevisionId, nodeId, ChangeType.UPDATE, nodes -> {
            int index = activeNodeIndex(nodes, nodeId);
            var old = nodes.get(index);
            nodes.set(index, new IntentNode(
                    old.nodeId(),
                    old.parentId(),
                    old.depth(),
                    command.sortOrder(),
                    old.type(),
                    command.name(),
                    command.description(),
                    immutableExamples(command.examples()),
                    command.knowledgeBaseId(),
                    command.systemAction(),
                    command.promptSnippet(),
                    old.enabled(),
                    false));
            return nodes;
        });
    }

    public CommitResult disable(UUID expectedRevisionId, UUID nodeId) {
        rejectVirtualRoot(nodeId);
        return commit(expectedRevisionId, nodeId, ChangeType.DISABLE_SUBTREE, nodes -> {
            Set<UUID> subtree = subtreeIds(nodes, nodeId);
            replaceMatching(nodes, subtree, node -> node.withEnabled(false));
            return nodes;
        });
    }

    public CommitResult enable(UUID expectedRevisionId, UUID nodeId, EnableScope scope) {
        rejectVirtualRoot(nodeId);
        Objects.requireNonNull(scope, "scope");
        return commit(expectedRevisionId, nodeId, ChangeType.ENABLE_SUBTREE, nodes -> {
            var target = activeNode(nodes, nodeId);
            if (target.type() != IntentNodeType.GROUP && scope != EnableScope.NODE_ONLY) {
                throw invalid("leaf nodes only accept NODE_ONLY enable scope");
            }
            Set<UUID> selected = scope == EnableScope.CONFIGURED_SUBTREE
                    ? subtreeIds(nodes, nodeId)
                    : Set.of(nodeId);
            replaceMatching(nodes, selected, node -> node.withEnabled(true));
            return nodes;
        });
    }

    public CommitResult delete(UUID expectedRevisionId, UUID nodeId) {
        rejectVirtualRoot(nodeId);
        return commit(expectedRevisionId, nodeId, ChangeType.DELETE_SUBTREE, nodes -> {
            Set<UUID> subtree = subtreeIds(nodes, nodeId);
            replaceMatching(nodes, subtree, node -> node.withDeleted());
            return nodes;
        });
    }

    public ImpactPreview preview(UUID expectedRevisionId, UUID nodeId, TreeOperation operation) {
        rejectVirtualRoot(nodeId);
        var snapshot = current();
        requireExpected(expectedRevisionId, snapshot.revisionId(), snapshot.revisionNo());
        var subtree = snapshot.activeSubtree(nodeId);
        if (subtree.isEmpty()) {
            throw notFound();
        }
        long enabledLeaves = subtree.stream().filter(IntentNode::isEnabledLeaf).count();
        long boundKnowledgeBases = subtree.stream()
                .filter(node -> node.type() == IntentNodeType.KB)
                .map(IntentNode::knowledgeBaseId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        List<UUID> blockers = operation == TreeOperation.ENABLE_CONFIGURED_SUBTREE
                ? subtree.stream().filter(this::isIncompleteLeaf).map(IntentNode::nodeId).toList()
                : List.of();
        return new ImpactPreview(
                snapshot.revisionId(), operation, subtree.size(), (int) enabledLeaves,
                (int) boundKnowledgeBases, blockers);
    }

    private CommitResult commit(
            UUID expectedRevisionId,
            UUID changedNodeId,
            ChangeType changeType,
            Function<List<IntentNode>, List<IntentNode>> mutation) {
        IntentTreeSnapshot committed = transactions.execute(status -> {
            var head = readHead(true);
            requireExpected(expectedRevisionId, head.revisionId(), head.revisionNo());
            var current = loadSnapshot(head);
            var changed = mutation.apply(new ArrayList<>(current.nodes()));
            var normalized = normalizeDepths(changed);
            validate(normalized);
            return persist(head, changedNodeId, changeType, normalized);
        });
        if (committed == null) {
            throw new IllegalStateException("intent tree transaction returned no snapshot");
        }
        try {
            cachePublisher.accept(committed);
            cache.set(committed);
        } catch (RuntimeException cacheFailure) {
            cache.set(null);
        }
        return new CommitResult(committed, changedNodeId);
    }

    private IntentTreeSnapshot persist(
            Head head, UUID changedNodeId, ChangeType changeType, List<IntentNode> nodes) {
        UUID revisionId = UUID.randomUUID();
        long revisionNo = head.revisionNo() + 1;
        int nodeCount = (int) nodes.stream().filter(node -> !node.deleted()).count();
        int enabledLeafCount = (int) nodes.stream().filter(IntentNode::isEnabledLeaf).count();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, parent_revision_id, change_type, changed_node_id,
                     node_count, enabled_leaf_count)
                values (?, ?, ?, ?, ?, ?, ?)
                """, revisionId, revisionNo, head.revisionId(), changeType.name(), changedNodeId,
                nodeCount, enabledLeafCount);
        for (var node : nodes) {
            jdbc.update("""
                    insert into qa_intent_node_snapshot
                        (revision_id, node_id, parent_id, depth, sort_order, node_type,
                         name, description, examples, knowledge_base_id, system_action,
                         prompt_snippet, enabled, deleted)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)
                    """,
                    revisionId,
                    node.nodeId(),
                    databaseParent(node.parentId()),
                    node.depth(),
                    node.sortOrder(),
                    node.type().name(),
                    node.name().strip(),
                    stripNullable(node.description()),
                    writeExamples(node.examples()),
                    node.knowledgeBaseId(),
                    node.systemAction() == null ? null : node.systemAction().name(),
                    stripNullable(node.promptSnippet()),
                    node.enabled(),
                    node.deleted());
        }
        jdbc.update("""
                update qa_intent_tree_head
                   set current_revision_id = ?, current_revision_no = ?, updated_at = now()
                 where singleton_id = 1
                """, revisionId, revisionNo);
        return loadSnapshot(readHead(false));
    }

    private Head readHead(boolean lock) {
        String sql = """
                select current_revision_id, current_revision_no, updated_at
                  from qa_intent_tree_head where singleton_id = 1
                """ + (lock ? " for update" : "");
        return jdbc.queryForObject(sql, (rs, rowNum) -> new Head(
                rs.getObject("current_revision_id", UUID.class),
                rs.getLong("current_revision_no"),
                rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private IntentTreeSnapshot loadSnapshot(Head head) {
        if (head == null) {
            throw new IllegalStateException("intent tree head is missing");
        }
        if (head.revisionId() == null) {
            return new IntentTreeSnapshot(null, 0, head.updatedAt(), List.of());
        }
        var nodes = jdbc.query("""
                select node_id, parent_id, depth, sort_order, node_type, name, description,
                       examples::text, knowledge_base_id, system_action, prompt_snippet,
                       enabled, deleted
                  from qa_intent_node_snapshot
                 where revision_id = ?
                 order by depth, sort_order, node_id
                """, (rs, rowNum) -> mapNode(rs), head.revisionId());
        return new IntentTreeSnapshot(head.revisionId(), head.revisionNo(), head.updatedAt(), nodes);
    }

    private IntentNode mapNode(ResultSet rs) throws SQLException {
        String action = rs.getString("system_action");
        return new IntentNode(
                rs.getObject("node_id", UUID.class),
                Optional.ofNullable(rs.getObject("parent_id", UUID.class)).orElse(VIRTUAL_ROOT_ID),
                rs.getInt("depth"),
                rs.getInt("sort_order"),
                IntentNodeType.valueOf(rs.getString("node_type")),
                rs.getString("name"),
                rs.getString("description"),
                readExamples(rs.getString("examples")),
                rs.getObject("knowledge_base_id", Long.class),
                action == null ? null : SystemAction.valueOf(action),
                rs.getString("prompt_snippet"),
                rs.getBoolean("enabled"),
                rs.getBoolean("deleted"));
    }

    private List<IntentNode> normalizeDepths(List<IntentNode> nodes) {
        Map<UUID, IntentNode> byId = new HashMap<>();
        for (var node : nodes) {
            if (node.nodeId() == null || VIRTUAL_ROOT_ID.equals(node.nodeId()) || byId.put(node.nodeId(), node) != null) {
                throw invalid("node IDs must be unique and may not use the virtual root ID");
            }
        }
        var normalized = new ArrayList<IntentNode>(nodes.size());
        for (var node : nodes) {
            int depth = depthOf(node, byId, new HashSet<>());
            normalized.add(node.withDepth(depth));
        }
        return normalized;
    }

    private int depthOf(IntentNode node, Map<UUID, IntentNode> byId, Set<UUID> visiting) {
        if (!visiting.add(node.nodeId())) {
            throw invalid("intent tree contains a cycle");
        }
        if (node.parentId() == null) {
            throw invalid("parentId must not be null");
        }
        int depth;
        if (VIRTUAL_ROOT_ID.equals(node.parentId())) {
            depth = 1;
        } else {
            var parent = byId.get(node.parentId());
            if (parent == null) {
                throw invalid("intent tree contains an orphan node");
            }
            depth = depthOf(parent, byId, visiting) + 1;
        }
        visiting.remove(node.nodeId());
        if (depth > 3) {
            throw invalid("intent tree depth must not exceed three");
        }
        return depth;
    }

    private void validate(List<IntentNode> nodes) {
        Map<UUID, IntentNode> byId = new HashMap<>();
        Map<UUID, Set<String>> siblingNames = new HashMap<>();
        Map<UUID, Integer> childCounts = new HashMap<>();
        for (var node : nodes) {
            byId.put(node.nodeId(), node);
            validateFields(node);
            if (!node.deleted()
                    && !siblingNames.computeIfAbsent(node.parentId(), ignored -> new HashSet<>())
                            .add(normalized(node.name()))) {
                throw invalid("active sibling node names must be unique");
            }
            if (!node.deleted() && !VIRTUAL_ROOT_ID.equals(node.parentId())) {
                childCounts.merge(node.parentId(), 1, Integer::sum);
            }
        }
        for (var node : nodes) {
            if (VIRTUAL_ROOT_ID.equals(node.parentId()) && node.type() != IntentNodeType.GROUP) {
                throw invalid("only GROUP nodes may be attached to the virtual root");
            }
            if (!VIRTUAL_ROOT_ID.equals(node.parentId())) {
                var parent = byId.get(node.parentId());
                if (parent == null || parent.type() != IntentNodeType.GROUP) {
                    throw invalid("every persisted parent must be a GROUP node");
                }
                if (!node.deleted() && parent.deleted()) {
                    throw invalid("active nodes may not have deleted ancestors");
                }
            }
            if (node.type() != IntentNodeType.GROUP && childCounts.getOrDefault(node.nodeId(), 0) > 0) {
                throw invalid("KB and SYSTEM nodes must be leaves");
            }
            if (node.enabled() && !allAncestorsEnabled(node, byId)) {
                throw invalid("enabled nodes require enabled ancestors");
            }
            if (node.isEnabledLeaf() && isIncompleteLeaf(node)) {
                throw invalid("enabled leaves require complete and available targets");
            }
        }
    }

    private void validateFields(IntentNode node) {
        requireLength(node.name(), 1, 120, "name");
        requireOptionalLength(node.description(), 1, 2_000, "description");
        requireOptionalLength(node.promptSnippet(), 1, 2_000, "promptSnippet");
        if (node.sortOrder() < 0) {
            throw invalid("sortOrder must not be negative");
        }
        if (node.examples() == null || node.examples().size() > 20) {
            throw invalid("examples may contain at most 20 items");
        }
        Set<String> normalizedExamples = new HashSet<>();
        for (String example : node.examples()) {
            requireLength(example, 1, 500, "example");
            if (!normalizedExamples.add(normalized(example))) {
                throw invalid("examples must be unique after normalization");
            }
        }
        switch (node.type()) {
            case GROUP -> {
                if (node.knowledgeBaseId() != null || node.systemAction() != null || node.promptSnippet() != null) {
                    throw invalid("GROUP nodes may not declare execution targets or prompt snippets");
                }
            }
            case KB -> {
                if (node.knowledgeBaseId() == null || node.systemAction() != null) {
                    throw invalid("KB nodes require only a knowledgeBaseId target");
                }
            }
            case SYSTEM -> {
                if (node.knowledgeBaseId() != null || node.systemAction() == null) {
                    throw invalid("SYSTEM nodes require only a closed systemAction target");
                }
            }
        }
        if (node.deleted() && node.enabled()) {
            throw invalid("deleted nodes must be disabled");
        }
    }

    private boolean isIncompleteLeaf(IntentNode node) {
        if (node.type() == IntentNodeType.GROUP) {
            return false;
        }
        if (node.description() == null || node.description().isBlank() || node.examples().isEmpty()) {
            return true;
        }
        return node.type() == IntentNodeType.KB && !knowledgeBases.isAvailable(node.knowledgeBaseId());
    }

    private static boolean allAncestorsEnabled(IntentNode node, Map<UUID, IntentNode> byId) {
        UUID parentId = node.parentId();
        while (!VIRTUAL_ROOT_ID.equals(parentId)) {
            var parent = byId.get(parentId);
            if (parent == null || parent.deleted() || !parent.enabled()) {
                return false;
            }
            parentId = parent.parentId();
        }
        return true;
    }

    private static void requireActiveParent(List<IntentNode> nodes, UUID parentId) {
        if (VIRTUAL_ROOT_ID.equals(parentId)) {
            return;
        }
        var parent = activeNode(nodes, parentId);
        if (parent.type() != IntentNodeType.GROUP) {
            throw invalid("new nodes require a GROUP parent");
        }
    }

    private static IntentNode activeNode(List<IntentNode> nodes, UUID nodeId) {
        return nodes.stream()
                .filter(node -> node.nodeId().equals(nodeId) && !node.deleted())
                .findFirst()
                .orElseThrow(IntentTreeStore::notFound);
    }

    private static int activeNodeIndex(List<IntentNode> nodes, UUID nodeId) {
        for (int index = 0; index < nodes.size(); index++) {
            var node = nodes.get(index);
            if (node.nodeId().equals(nodeId) && !node.deleted()) {
                return index;
            }
        }
        throw notFound();
    }

    private static Set<UUID> subtreeIds(List<IntentNode> nodes, UUID nodeId) {
        activeNode(nodes, nodeId);
        Set<UUID> subtree = new HashSet<>();
        subtree.add(nodeId);
        boolean added;
        do {
            added = false;
            for (var node : nodes) {
                if (!node.deleted() && subtree.contains(node.parentId()) && subtree.add(node.nodeId())) {
                    added = true;
                }
            }
        } while (added);
        return subtree;
    }

    private static void replaceMatching(
            List<IntentNode> nodes, Set<UUID> selected, Function<IntentNode, IntentNode> replacement) {
        for (int index = 0; index < nodes.size(); index++) {
            if (selected.contains(nodes.get(index).nodeId())) {
                nodes.set(index, replacement.apply(nodes.get(index)));
            }
        }
    }

    private static void requireExpected(UUID expected, UUID current, long currentNo) {
        if (!Objects.equals(expected, current)) {
            throw new IntentTreeRevisionConflictException(current, currentNo);
        }
    }

    private static void rejectVirtualRoot(UUID nodeId) {
        if (VIRTUAL_ROOT_ID.equals(nodeId)) {
            throw new IntentTreeValidationException(
                    IntentTreeValidationException.Kind.VIRTUAL_ROOT,
                    "virtual root is immutable");
        }
    }

    private static void requireLength(String value, int min, int max, String field) {
        if (value == null || value.isBlank()) {
            throw invalid(field + " must not be blank");
        }
        int length = value.strip().codePointCount(0, value.strip().length());
        if (length < min || length > max) {
            throw invalid(field + " length is outside the accepted range");
        }
    }

    private static void requireOptionalLength(String value, int min, int max, String field) {
        if (value != null) {
            requireLength(value, min, max, field);
        }
    }

    private static String normalized(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static List<String> immutableExamples(Collection<String> examples) {
        return examples == null ? List.of() : examples.stream().map(String::strip).toList();
    }

    private static UUID databaseParent(UUID parentId) {
        return VIRTUAL_ROOT_ID.equals(parentId) ? null : parentId;
    }

    private static String stripNullable(String value) {
        return value == null ? null : value.strip();
    }

    private String writeExamples(List<String> examples) {
        try {
            return json.writeValueAsString(examples);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot serialize intent examples", exception);
        }
    }

    private List<String> readExamples(String value) {
        try {
            return List.copyOf(json.readValue(value, STRING_LIST));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot read intent examples", exception);
        }
    }

    private static IntentTreeValidationException invalid(String message) {
        return new IntentTreeValidationException(message);
    }

    private static IntentTreeValidationException notFound() {
        return new IntentTreeValidationException(
                IntentTreeValidationException.Kind.NOT_FOUND,
                "intent node does not exist");
    }

    private record Head(UUID revisionId, long revisionNo, OffsetDateTime updatedAt) {}

    private enum ChangeType {
        CREATE,
        UPDATE,
        ENABLE_SUBTREE,
        DISABLE_SUBTREE,
        DELETE_SUBTREE
    }

    public enum EnableScope {
        NODE_ONLY,
        CONFIGURED_SUBTREE
    }

    public enum TreeOperation {
        ENABLE_CONFIGURED_SUBTREE,
        DISABLE_SUBTREE,
        DELETE_SUBTREE
    }

    public record CreateNode(
            UUID parentId,
            IntentNodeType type,
            String name,
            String description,
            List<String> examples,
            Long knowledgeBaseId,
            SystemAction systemAction,
            String promptSnippet,
            int sortOrder) {}

    public record EditableNode(
            String name,
            String description,
            List<String> examples,
            Long knowledgeBaseId,
            SystemAction systemAction,
            String promptSnippet,
            int sortOrder) {}

    public record IntentNode(
            UUID nodeId,
            UUID parentId,
            int depth,
            int sortOrder,
            IntentNodeType type,
            String name,
            String description,
            List<String> examples,
            Long knowledgeBaseId,
            SystemAction systemAction,
            String promptSnippet,
            boolean enabled,
            boolean deleted) {

        public IntentNode {
            examples = examples == null ? List.of() : List.copyOf(examples);
        }

        IntentNode withDepth(int value) {
            return new IntentNode(
                    nodeId, parentId, value, sortOrder, type, name, description, examples,
                    knowledgeBaseId, systemAction, promptSnippet, enabled, deleted);
        }

        IntentNode withEnabled(boolean value) {
            return new IntentNode(
                    nodeId, parentId, depth, sortOrder, type, name, description, examples,
                    knowledgeBaseId, systemAction, promptSnippet, value, deleted);
        }

        IntentNode withDeleted() {
            return new IntentNode(
                    nodeId, parentId, depth, sortOrder, type, name, description, examples,
                    knowledgeBaseId, systemAction, promptSnippet, false, true);
        }

        boolean isEnabledLeaf() {
            return !deleted && enabled && type != IntentNodeType.GROUP;
        }
    }

    public record IntentTreeSnapshot(
            UUID revisionId, long revisionNo, OffsetDateTime updatedAt, List<IntentNode> nodes) {

        public IntentTreeSnapshot {
            nodes = List.copyOf(nodes);
        }

        public Optional<IntentNode> node(UUID nodeId) {
            return nodes.stream().filter(node -> node.nodeId().equals(nodeId)).findFirst();
        }

        public List<IntentNode> activeSubtree(UUID nodeId) {
            Map<UUID, IntentNode> active = new LinkedHashMap<>();
            nodes.stream().filter(node -> !node.deleted()).forEach(node -> active.put(node.nodeId(), node));
            if (!active.containsKey(nodeId)) {
                return List.of();
            }
            return active.values().stream()
                    .filter(node -> node.nodeId().equals(nodeId) || hasAncestor(node, nodeId, active))
                    .sorted(Comparator.comparingInt(IntentNode::depth)
                            .thenComparingInt(IntentNode::sortOrder)
                            .thenComparing(IntentNode::nodeId))
                    .toList();
        }

        private static boolean hasAncestor(
                IntentNode node, UUID ancestorId, Map<UUID, IntentNode> active) {
            UUID parentId = node.parentId();
            while (!VIRTUAL_ROOT_ID.equals(parentId)) {
                if (ancestorId.equals(parentId)) {
                    return true;
                }
                var parent = active.get(parentId);
                if (parent == null) {
                    return false;
                }
                parentId = parent.parentId();
            }
            return false;
        }
    }

    public record CommitResult(IntentTreeSnapshot snapshot, UUID changedNodeId) {}

    public record ImpactPreview(
            UUID revisionId,
            TreeOperation operation,
            int nodeCount,
            int enabledLeafCount,
            int boundKnowledgeBaseCount,
            List<UUID> blockingNodes) {

        public ImpactPreview {
            blockingNodes = List.copyOf(blockingNodes);
        }
    }
}
