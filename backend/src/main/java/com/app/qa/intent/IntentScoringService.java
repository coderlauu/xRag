package com.app.qa.intent;

import com.app.ai.model.ChatClient;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.SystemMessage;
import com.app.ai.model.UserMessage;
import com.app.qa.config.QaUnderstandingProperties;
import com.app.qa.conversation.TurnStageSink;
import com.app.qa.model.IntentNodeType;
import com.app.qa.model.QaChatCommandFactory;
import com.app.qa.model.StageOutcome;
import com.app.qa.model.SystemAction;
import com.app.qa.model.TurnStage;
import com.app.qa.prompt.IntentCatalogEncoder;
import com.app.qa.prompt.IntentCatalogEntry;
import com.app.qa.prompt.PromptTemplateCatalog;
import com.app.qa.prompt.QaPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public final class IntentScoringService {

    private static final Pattern JSON_FENCE = Pattern.compile(
            "^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final int MAX_CANDIDATES_PER_QUESTION = 3;

    private final ChatClient chat;
    private final PromptTemplateCatalog prompts;
    private final IntentCatalogEncoder catalogEncoder;
    private final ObjectMapper json;
    private final QaUnderstandingProperties properties;
    private final Executor executor;
    private final TurnStageSink stages;

    @Autowired
    public IntentScoringService(
            ChatClient chat,
            PromptTemplateCatalog prompts,
            IntentCatalogEncoder catalogEncoder,
            ObjectMapper json,
            QaUnderstandingProperties properties,
            @Qualifier("qaIntentScoringExecutor") Executor executor,
            TurnStageSink stages) {
        this.chat = chat;
        this.prompts = prompts;
        this.catalogEncoder = catalogEncoder;
        this.json = json;
        this.properties = properties;
        this.executor = executor;
        this.stages = stages;
    }

    public ScoringBatch score(
            UUID turnId,
            IntentTreeStore.IntentTreeSnapshot snapshot,
            List<ScoringQuestion> questions) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(turnId, "turnId");
        validateQuestions(questions);
        Catalog catalog = catalog(snapshot);

        String systemPrompt = prompts.render(QaPrompt.INTENT_SCORE_SYSTEM, Map.of());
        String encodedCatalog = catalogEncoder.encode(catalog.entries());
        List<ScoringWork> work = questions.stream()
                .map(question -> new ScoringWork(
                        question,
                        QaChatCommandFactory.balanced(List.of(
                                new SystemMessage(systemPrompt),
                                new UserMessage(prompts.render(QaPrompt.INTENT_SCORE_USER, Map.of(
                                        "question", question.text(),
                                        "intentCatalog", encodedCatalog)))))))
                .toList();

        List<CompletableFuture<ScoredQuestion>> futures = work.stream()
                .map(item -> CompletableFuture.supplyAsync(
                        () -> scoreOne(turnId, snapshot.revisionId(), item, catalog), executor))
                .toList();
        var scored = new ArrayList<ScoredQuestion>(futures.size());
        try {
            for (var future : futures) {
                scored.add(future.get());
            }
        } catch (InterruptedException exception) {
            futures.forEach(future -> future.cancel(true));
            Thread.currentThread().interrupt();
            throw new IntentScoringInterruptedException();
        } catch (ExecutionException exception) {
            futures.forEach(future -> future.cancel(true));
            throw new IntentScoringException(exception.getCause());
        }
        return new ScoringBatch(snapshot.revisionId(), capGlobally(scored));
    }

    private ScoredQuestion scoreOne(
            UUID turnId, UUID revisionId, ScoringWork work, Catalog catalog) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        try {
            var result = chat.complete(work.command());
            UUID logicalCallId = result.execution().logicalCallId();
            ParseResult parsed = parse(result.message().text(), catalog);
            var scored = new ScoredQuestion(
                    work.question().subQuestionId(),
                    work.question().ordinal(),
                    work.question().text(),
                    parsed.candidates(),
                    parsed.reasonCode(),
                    logicalCallId);
            recordStage(
                    turnId,
                    revisionId,
                    work.question().ordinal(),
                    parsed.outcome(),
                    parsed.reasonCode(),
                    logicalCallId,
                    startedAt);
            return scored;
        } catch (ModelCallException exception) {
            UUID logicalCallId = exception.execution().logicalCallId();
            recordStage(
                    turnId,
                    revisionId,
                    work.question().ordinal(),
                    StageOutcome.DEGRADED,
                    "INTENT_SCORING_UNAVAILABLE",
                    logicalCallId,
                    startedAt);
            return failed(work.question(), "INTENT_SCORING_UNAVAILABLE", logicalCallId);
        } catch (RuntimeException exception) {
            recordStage(
                    turnId,
                    revisionId,
                    work.question().ordinal(),
                    StageOutcome.DEGRADED,
                    "INTENT_SCORING_UNAVAILABLE",
                    null,
                    startedAt);
            return failed(work.question(), "INTENT_SCORING_UNAVAILABLE", null);
        }
    }

    private ParseResult parse(String raw, Catalog catalog) {
        if (raw == null) {
            return new ParseResult(
                    List.of(), "INTENT_SCORING_INVALID_RESPONSE", StageOutcome.DEGRADED);
        }
        try {
            JsonNode root = json.readTree(stripFence(raw));
            if (root == null || !root.isArray()) {
                return new ParseResult(
                        List.of(), "INTENT_SCORING_INVALID_RESPONSE", StageOutcome.DEGRADED);
            }
            Map<UUID, Double> best = new HashMap<>();
            for (JsonNode item : root) {
                if (!item.isObject()
                        || item.size() != 2
                        || !item.has("id")
                        || !item.has("score")
                        || !item.get("id").isTextual()
                        || !item.get("score").isNumber()) {
                    continue;
                }
                UUID nodeId;
                try {
                    nodeId = UUID.fromString(item.get("id").textValue());
                } catch (IllegalArgumentException exception) {
                    continue;
                }
                double score = item.get("score").doubleValue();
                if (!catalog.nodes().containsKey(nodeId)
                        || !Double.isFinite(score)
                        || score < 0
                        || score > 1
                        || score < properties.getThresholds().getRetain()) {
                    continue;
                }
                best.merge(nodeId, score, Math::max);
            }
            var candidates = best.entrySet().stream()
                    .sorted(Map.Entry.<UUID, Double>comparingByValue().reversed()
                            .thenComparing(Map.Entry::getKey))
                    .limit(MAX_CANDIDATES_PER_QUESTION)
                    .map(entry -> candidate(catalog, entry.getKey(), entry.getValue(), 0))
                    .toList();
            return new ParseResult(
                    candidates,
                    candidates.isEmpty() ? "NO_VALID_INTENT_SCORE" : null,
                    StageOutcome.SUCCEEDED);
        } catch (Exception exception) {
            return new ParseResult(
                    List.of(), "INTENT_SCORING_INVALID_RESPONSE", StageOutcome.DEGRADED);
        }
    }

    private void recordStage(
            UUID turnId,
            UUID revisionId,
            int ordinal,
            StageOutcome outcome,
            String reasonCode,
            UUID logicalCallId,
            OffsetDateTime startedAt) {
        stages.recordBestEffort(new TurnStageSink.StageEvent(
                turnId,
                TurnStage.INTENT_SCORE,
                ordinal,
                outcome,
                reasonCode,
                logicalCallId,
                revisionId,
                startedAt,
                OffsetDateTime.now()));
    }

    private List<ScoredQuestion> capGlobally(List<ScoredQuestion> questions) {
        var selected = new HashSet<CandidateSlot>();
        for (int index = 0; index < questions.size(); index++) {
            var candidates = questions.get(index).candidates();
            if (!candidates.isEmpty()) {
                selected.add(new CandidateSlot(index, candidates.get(0).nodeId()));
            }
        }
        var remaining = new ArrayList<RankedCandidate>();
        for (int questionIndex = 0; questionIndex < questions.size(); questionIndex++) {
            var candidates = questions.get(questionIndex).candidates();
            for (int candidateIndex = 1; candidateIndex < candidates.size(); candidateIndex++) {
                remaining.add(new RankedCandidate(questionIndex, candidates.get(candidateIndex)));
            }
        }
        remaining.sort(Comparator.comparingDouble(
                        (RankedCandidate item) -> item.candidate().score()).reversed()
                .thenComparing(item -> item.candidate().nodeId())
                .thenComparingInt(RankedCandidate::questionIndex));
        for (var item : remaining) {
            if (selected.size() >= properties.getMaxTotalIntents()) {
                break;
            }
            selected.add(new CandidateSlot(item.questionIndex(), item.candidate().nodeId()));
        }

        var capped = new ArrayList<ScoredQuestion>(questions.size());
        for (int questionIndex = 0; questionIndex < questions.size(); questionIndex++) {
            var question = questions.get(questionIndex);
            var candidates = new ArrayList<IntentCandidate>();
            for (var candidate : question.candidates()) {
                if (selected.contains(new CandidateSlot(questionIndex, candidate.nodeId()))) {
                    candidates.add(candidate.withRank(candidates.size() + 1));
                }
            }
            capped.add(new ScoredQuestion(
                    question.subQuestionId(),
                    question.ordinal(),
                    question.text(),
                    candidates,
                    question.reasonCode(),
                    question.logicalCallId()));
        }
        return List.copyOf(capped);
    }

    private Catalog catalog(IntentTreeStore.IntentTreeSnapshot snapshot) {
        Map<UUID, IntentTreeStore.IntentNode> active = new LinkedHashMap<>();
        snapshot.nodes().stream()
                .filter(node -> !node.deleted())
                .forEach(node -> active.put(node.nodeId(), node));
        Map<UUID, CatalogNode> nodes = new LinkedHashMap<>();
        for (var node : active.values()) {
            if (node.enabled()
                    && node.type() != IntentNodeType.GROUP
                    && ancestorsEnabled(node, active)) {
                String fullPath = fullPath(node, active);
                UUID categoryId = node.type() == IntentNodeType.KB && node.depth() >= 3
                        ? node.parentId()
                        : node.nodeId();
                nodes.put(node.nodeId(), new CatalogNode(node, fullPath, categoryId));
            }
        }
        List<IntentCatalogEntry> entries = nodes.values().stream()
                .map(node -> new IntentCatalogEntry(
                        node.node().nodeId(),
                        node.node().type(),
                        node.fullPath(),
                        node.node().description(),
                        node.node().examples()))
                .toList();
        return new Catalog(Map.copyOf(nodes), entries);
    }

    private static boolean ancestorsEnabled(
            IntentTreeStore.IntentNode node,
            Map<UUID, IntentTreeStore.IntentNode> active) {
        UUID parentId = node.parentId();
        while (!IntentTreeStore.VIRTUAL_ROOT_ID.equals(parentId)) {
            var parent = active.get(parentId);
            if (parent == null || !parent.enabled()) {
                return false;
            }
            parentId = parent.parentId();
        }
        return true;
    }

    private static String fullPath(
            IntentTreeStore.IntentNode node,
            Map<UUID, IntentTreeStore.IntentNode> active) {
        var names = new ArrayList<String>();
        var current = node;
        while (current != null) {
            names.add(current.name());
            current = IntentTreeStore.VIRTUAL_ROOT_ID.equals(current.parentId())
                    ? null
                    : active.get(current.parentId());
        }
        java.util.Collections.reverse(names);
        return String.join(" > ", names);
    }

    private static IntentCandidate candidate(Catalog catalog, UUID nodeId, double score, int rank) {
        var catalogNode = catalog.nodes().get(nodeId);
        var node = catalogNode.node();
        return new IntentCandidate(
                node.nodeId(),
                node.type(),
                catalogNode.fullPath(),
                node.knowledgeBaseId(),
                node.systemAction(),
                catalogNode.categoryNodeId(),
                score,
                rank);
    }

    private static ScoredQuestion failed(ScoringQuestion question, String reasonCode, UUID logicalCallId) {
        return new ScoredQuestion(
                question.subQuestionId(),
                question.ordinal(),
                question.text(),
                List.of(),
                reasonCode,
                logicalCallId);
    }

    private static void validateQuestions(List<ScoringQuestion> questions) {
        if (questions == null || questions.isEmpty() || questions.size() > 3) {
            throw new IllegalArgumentException("intent scoring requires 1 to 3 subquestions");
        }
        Set<UUID> ids = new HashSet<>();
        for (int ordinal = 0; ordinal < questions.size(); ordinal++) {
            var question = questions.get(ordinal);
            if (question == null
                    || question.subQuestionId() == null
                    || question.ordinal() != ordinal
                    || question.text() == null
                    || question.text().isBlank()
                    || !ids.add(question.subQuestionId())) {
                throw new IllegalArgumentException("subquestions require stable IDs and contiguous ordinals");
            }
        }
    }

    private static String stripFence(String raw) {
        var matcher = JSON_FENCE.matcher(raw);
        return matcher.matches() ? matcher.group(1) : raw;
    }

    public record ScoringQuestion(UUID subQuestionId, int ordinal, String text) {}

    public record ScoringBatch(UUID revisionId, List<ScoredQuestion> questions) {

        public ScoringBatch {
            questions = List.copyOf(questions);
        }
    }

    public record ScoredQuestion(
            UUID subQuestionId,
            int ordinal,
            String text,
            List<IntentCandidate> candidates,
            String reasonCode,
            UUID logicalCallId) {

        public ScoredQuestion {
            candidates = List.copyOf(candidates);
        }
    }

    public record IntentCandidate(
            UUID nodeId,
            IntentNodeType type,
            String fullPath,
            Long knowledgeBaseId,
            SystemAction systemAction,
            UUID categoryNodeId,
            double score,
            int rank) {

        IntentCandidate withRank(int value) {
            return new IntentCandidate(
                    nodeId, type, fullPath, knowledgeBaseId, systemAction,
                    categoryNodeId, score, value);
        }
    }

    public static final class IntentScoringInterruptedException extends RuntimeException {

        IntentScoringInterruptedException() {
            super("intent scoring interrupted");
        }
    }

    public static final class IntentScoringException extends RuntimeException {

        IntentScoringException(Throwable cause) {
            super("intent scoring failed", cause);
        }
    }

    private record ScoringWork(ScoringQuestion question, ChatCommand command) {}

    private record ParseResult(
            List<IntentCandidate> candidates, String reasonCode, StageOutcome outcome) {}

    private record Catalog(Map<UUID, CatalogNode> nodes, List<IntentCatalogEntry> entries) {}

    private record CatalogNode(
            IntentTreeStore.IntentNode node, String fullPath, UUID categoryNodeId) {}

    private record RankedCandidate(int questionIndex, IntentCandidate candidate) {}

    private record CandidateSlot(int questionIndex, UUID nodeId) {}
}
