package com.app.qa.web;

import com.app.qa.intent.BindableKnowledgeBaseQuery;
import com.app.qa.intent.IntentTreeStore;
import com.app.qa.intent.IntentTreeStore.CreateNode;
import com.app.qa.intent.IntentTreeStore.EditableNode;
import com.app.qa.intent.IntentTreeStore.EnableScope;
import com.app.qa.intent.IntentTreeStore.IntentNode;
import com.app.qa.intent.IntentTreeStore.IntentTreeSnapshot;
import com.app.qa.intent.IntentTreeStore.TreeOperation;
import com.app.qa.intent.KnowledgeBaseLookup;
import com.app.qa.model.IntentNodeType;
import com.app.qa.model.SystemAction;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qa/intent-tree")
public final class QaIntentTreeController {

    private final IntentTreeStore store;
    private final BindableKnowledgeBaseQuery knowledgeBases;
    private final KnowledgeBaseLookup knowledgeBaseAvailability;

    public QaIntentTreeController(
            IntentTreeStore store,
            BindableKnowledgeBaseQuery knowledgeBases,
            KnowledgeBaseLookup knowledgeBaseAvailability) {
        this.store = store;
        this.knowledgeBases = knowledgeBases;
        this.knowledgeBaseAvailability = knowledgeBaseAvailability;
    }

    @GetMapping
    public TreeResponse tree() {
        return treeResponse(store.current());
    }

    @GetMapping("/bindable-knowledge-bases")
    public KnowledgeBasePage bindableKnowledgeBases(
            @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        validateLimit(limit);
        if (query.codePointCount(0, query.length()) > 128) {
            throw validationFailed("query 最多 128 个字符。");
        }
        Long beforeId = decodeKnowledgeBaseCursor(cursor);
        var found = knowledgeBases.find(query, beforeId, limit + 1);
        boolean hasMore = found.size() > limit;
        var items = found.stream().limit(limit).toList();
        String nextCursor = hasMore ? encodeKnowledgeBaseCursor(items.get(items.size() - 1).knowledgeBaseId()) : null;
        return new KnowledgeBasePage(items, nextCursor);
    }

    @PostMapping("/nodes")
    public ResponseEntity<TreeMutationResponse> create(@RequestBody CreateNodeRequest request) {
        requireRequest(request);
        if (request.parentId() == null || request.type() == null || request.name() == null) {
            throw validationFailed("create 必须包含 parentId、type 和 name。");
        }
        validateSortOrder(request.sortOrder());
        var committed = store.create(
                request.expectedRevisionId(),
                new CreateNode(
                        request.parentId(),
                        request.type(),
                        request.name(),
                        request.description(),
                        request.examples(),
                        request.knowledgeBaseId(),
                        request.systemAction(),
                        request.promptSnippet(),
                        request.sortOrder()));
        return ResponseEntity.status(HttpStatus.CREATED).body(mutationResponse(committed));
    }

    @PutMapping("/nodes/{nodeId}")
    public TreeMutationResponse update(
            @PathVariable UUID nodeId, @RequestBody UpdateNodeRequest request) {
        requireRequest(request);
        if (request.name() == null) {
            throw validationFailed("update 必须包含 name。");
        }
        validateSortOrder(request.sortOrder());
        var committed = store.update(
                request.expectedRevisionId(),
                nodeId,
                new EditableNode(
                        request.name(),
                        request.description(),
                        request.examples(),
                        request.knowledgeBaseId(),
                        request.systemAction(),
                        request.promptSnippet(),
                        request.sortOrder()));
        return mutationResponse(committed);
    }

    @PostMapping("/nodes/{nodeId}/impact")
    public ImpactResponse impact(
            @PathVariable UUID nodeId, @RequestBody ImpactRequest request) {
        requireRequest(request);
        if (request.operation() == null) {
            throw validationFailed("impact operation 不能为空。");
        }
        var preview = store.preview(request.expectedRevisionId(), nodeId, request.operation());
        return new ImpactResponse(
                preview.revisionId(),
                preview.operation(),
                preview.nodeCount(),
                preview.enabledLeafCount(),
                preview.boundKnowledgeBaseCount(),
                preview.blockingNodes());
    }

    @PostMapping("/nodes/{nodeId}/disable")
    public TreeMutationResponse disable(
            @PathVariable UUID nodeId, @RequestBody DisableRequest request) {
        requireRequest(request);
        if (request.scope() != DisableScope.SUBTREE) {
            throw validationFailed("disable scope 只能是 SUBTREE。");
        }
        return mutationResponse(store.disable(request.expectedRevisionId(), nodeId));
    }

    @PostMapping("/nodes/{nodeId}/enable")
    public TreeMutationResponse enable(
            @PathVariable UUID nodeId, @RequestBody EnableRequest request) {
        requireRequest(request);
        if (request.scope() == null) {
            throw validationFailed("enable scope 不能为空。");
        }
        return mutationResponse(store.enable(request.expectedRevisionId(), nodeId, request.scope()));
    }

    @PostMapping("/nodes/{nodeId}/delete")
    public TreeMutationResponse delete(
            @PathVariable UUID nodeId, @RequestBody DeleteRequest request) {
        requireRequest(request);
        if (request.scope() != DeleteScope.SUBTREE) {
            throw validationFailed("delete scope 只能是 SUBTREE。");
        }
        return mutationResponse(store.delete(request.expectedRevisionId(), nodeId));
    }

    private TreeMutationResponse mutationResponse(IntentTreeStore.CommitResult committed) {
        var tree = treeResponse(committed.snapshot());
        return new TreeMutationResponse(
                tree.revision(), committed.changedNodeId(), tree.summary(), tree.nodes());
    }

    private TreeResponse treeResponse(IntentTreeSnapshot snapshot) {
        var activeNodes = snapshot.nodes().stream().filter(node -> !node.deleted()).toList();
        Map<UUID, IntentNode> byId = new HashMap<>();
        Map<UUID, Integer> childCounts = new HashMap<>();
        for (var node : activeNodes) {
            byId.put(node.nodeId(), node);
            childCounts.merge(node.parentId(), 1, Integer::sum);
        }
        var nodes = activeNodes.stream()
                .map(node -> new IntentNodeResponse(
                        node.nodeId(),
                        node.parentId(),
                        node.depth(),
                        node.sortOrder(),
                        node.type(),
                        node.name(),
                        node.description(),
                        node.examples(),
                        node.knowledgeBaseId(),
                        node.systemAction(),
                        node.promptSnippet(),
                        node.enabled(),
                        effectiveEnabled(node, byId),
                        fullPath(node, byId),
                        childCounts.getOrDefault(node.nodeId(), 0),
                        enabledLeafCount(snapshot, node.nodeId()),
                        "CURRENT"))
                .toList();
        int enabledLeafCount = (int) activeNodes.stream().filter(this::enabledLeaf).count();
        int incompleteLeafCount = (int) activeNodes.stream().filter(this::incompleteLeaf).count();
        return new TreeResponse(
                new RevisionResponse(snapshot.revisionId(), snapshot.revisionNo(), snapshot.updatedAt()),
                new RootResponse(IntentTreeStore.VIRTUAL_ROOT_ID, "全部意图", true, false),
                nodes,
                new TreeSummary(activeNodes.size(), enabledLeafCount, incompleteLeafCount));
    }

    private int enabledLeafCount(IntentTreeSnapshot snapshot, UUID nodeId) {
        return (int) snapshot.activeSubtree(nodeId).stream().filter(this::enabledLeaf).count();
    }

    private boolean enabledLeaf(IntentNode node) {
        return node.enabled() && node.type() != IntentNodeType.GROUP;
    }

    private boolean incompleteLeaf(IntentNode node) {
        if (node.type() == IntentNodeType.GROUP) {
            return false;
        }
        if (node.description() == null || node.description().isBlank() || node.examples().isEmpty()) {
            return true;
        }
        return node.type() == IntentNodeType.KB
                && (node.knowledgeBaseId() == null
                        || !knowledgeBaseAvailability.isAvailable(node.knowledgeBaseId()));
    }

    private static boolean effectiveEnabled(IntentNode node, Map<UUID, IntentNode> byId) {
        if (!node.enabled()) {
            return false;
        }
        UUID parentId = node.parentId();
        while (!IntentTreeStore.VIRTUAL_ROOT_ID.equals(parentId)) {
            var parent = byId.get(parentId);
            if (parent == null || !parent.enabled()) {
                return false;
            }
            parentId = parent.parentId();
        }
        return true;
    }

    private static String fullPath(IntentNode node, Map<UUID, IntentNode> byId) {
        var names = new java.util.ArrayDeque<String>();
        IntentNode current = node;
        while (current != null) {
            names.addFirst(current.name());
            current = IntentTreeStore.VIRTUAL_ROOT_ID.equals(current.parentId())
                    ? null
                    : byId.get(current.parentId());
        }
        return String.join(" > ", names);
    }

    private static void requireRequest(Object request) {
        if (request == null) {
            throw validationFailed("请求正文不能为空。");
        }
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw validationFailed("limit 必须在 1 到 100 之间。");
        }
    }

    private static void validateSortOrder(int sortOrder) {
        if (sortOrder < 0) {
            throw validationFailed("sortOrder 不能为负数。");
        }
    }

    private static QaRequestException validationFailed(String detail) {
        return new QaRequestException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "请求参数无效",
                "VALIDATION_FAILED",
                detail);
    }

    private static String encodeKnowledgeBaseCursor(long id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(Long.toString(id).getBytes(StandardCharsets.UTF_8));
    }

    private static Long decodeKnowledgeBaseCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            long id = Long.parseLong(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8));
            if (id < 1) {
                throw new IllegalArgumentException("cursor id must be positive");
            }
            return id;
        } catch (RuntimeException exception) {
            throw new QaRequestException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "游标无效",
                    "INVALID_CURSOR",
                    "请移除游标并重新加载第一页。");
        }
    }

    public enum DisableScope {
        SUBTREE
    }

    public enum DeleteScope {
        SUBTREE
    }

    public record CreateNodeRequest(
            @JsonProperty(required = true) UUID expectedRevisionId,
            UUID parentId,
            IntentNodeType type,
            String name,
            String description,
            List<String> examples,
            Long knowledgeBaseId,
            SystemAction systemAction,
            String promptSnippet,
            int sortOrder) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record UpdateNodeRequest(
            @JsonProperty(required = true) UUID expectedRevisionId,
            String name,
            String description,
            List<String> examples,
            Long knowledgeBaseId,
            SystemAction systemAction,
            String promptSnippet,
            int sortOrder) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record ImpactRequest(
            @JsonProperty(required = true) UUID expectedRevisionId, TreeOperation operation) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record DisableRequest(
            @JsonProperty(required = true) UUID expectedRevisionId, DisableScope scope) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record EnableRequest(
            @JsonProperty(required = true) UUID expectedRevisionId, EnableScope scope) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record DeleteRequest(
            @JsonProperty(required = true) UUID expectedRevisionId, DeleteScope scope) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record RevisionResponse(UUID revisionId, long revisionNo, OffsetDateTime updatedAt) {}

    public record RootResponse(UUID nodeId, String name, boolean virtual, boolean editable) {}

    public record IntentNodeResponse(
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
            boolean effectiveEnabled,
            String fullPath,
            int childCount,
            int enabledLeafCount,
            String versionState) {}

    public record TreeSummary(int nodeCount, int enabledLeafCount, int incompleteLeafCount) {}

    public record TreeResponse(
            RevisionResponse revision, RootResponse root, List<IntentNodeResponse> nodes, TreeSummary summary) {}

    public record TreeMutationResponse(
            RevisionResponse revision,
            UUID changedNodeId,
            TreeSummary treeSummary,
            List<IntentNodeResponse> nodes) {}

    public record ImpactResponse(
            UUID revisionId,
            TreeOperation operation,
            int nodeCount,
            int enabledLeafCount,
            int boundKnowledgeBaseCount,
            List<UUID> blockingNodes) {}

    public record KnowledgeBasePage(
            List<BindableKnowledgeBaseQuery.BindableKnowledgeBase> items, String nextCursor) {}
}
