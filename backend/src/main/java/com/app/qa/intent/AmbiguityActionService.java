package com.app.qa.intent;

import com.app.ai.model.ChatClient;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.SystemMessage;
import com.app.ai.model.UserMessage;
import com.app.qa.config.QaUnderstandingProperties;
import com.app.qa.conversation.TurnStageSink;
import com.app.qa.model.IntentNodeType;
import com.app.qa.model.QaChatCommandFactory;
import com.app.qa.model.RetrievalActionType;
import com.app.qa.model.StageOutcome;
import com.app.qa.model.SystemAction;
import com.app.qa.model.TurnStage;
import com.app.qa.model.TurnStatus;
import com.app.qa.prompt.PromptTemplateCatalog;
import com.app.qa.prompt.QaPrompt;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class AmbiguityActionService {

    private static final String GUIDANCE_PREFIX = "请确认你想了解的方向：";
    private static final double SCORE_EPSILON = 1e-12;

    private final ChatClient chat;
    private final PromptTemplateCatalog prompts;
    private final ObjectMapper json;
    private final QaUnderstandingProperties properties;
    private final TurnStageSink stages;

    public AmbiguityActionService(
            ChatClient chat,
            PromptTemplateCatalog prompts,
            ObjectMapper json,
            QaUnderstandingProperties properties,
            TurnStageSink stages) {
        this.chat = chat;
        this.prompts = prompts;
        this.json = json;
        this.properties = properties;
        this.stages = stages;
    }

    public Decision decide(UUID turnId, IntentScoringService.ScoringBatch batch) {
        return decide(turnId, batch, null);
    }

    public Decision decide(
            UUID turnId,
            IntentScoringService.ScoringBatch batch,
            UUID selectedIntentNodeId) {
        Objects.requireNonNull(turnId, "turnId");
        Objects.requireNonNull(batch, "batch");
        if (batch.questions().isEmpty() || batch.questions().size() > 3) {
            throw new IllegalArgumentException("ambiguity/action mapping requires 1 to 3 subquestions");
        }

        List<IntentScoringService.ScoredQuestion> questions = selectedIntentNodeId == null
                ? batch.questions()
                : retainSelection(batch.questions(), selectedIntentNodeId);
        AmbiguityResult ambiguity = inspectAmbiguity(turnId, batch.revisionId(), questions, selectedIntentNodeId);
        if (ambiguity.ambiguous()) {
            List<ClarificationOption> options = options(questions.get(0).candidates());
            String guidance = GUIDANCE_PREFIX + options.stream()
                    .map(ClarificationOption::label)
                    .reduce((left, right) -> left + "；" + right)
                    .orElseThrow();
            return new Decision(
                    TurnStatus.WAITING_CLARIFICATION,
                    ambiguity.reasonCode(),
                    guidance,
                    options,
                    List.of(),
                    ambiguity.logicalCallId());
        }

        List<RetrievalAction> actions = actions(questions);
        for (var question : questions) {
            recordStage(
                    turnId,
                    batch.revisionId(),
                    TurnStage.ACTION_MAP,
                    question.ordinal(),
                    StageOutcome.SUCCEEDED,
                    "ACTIONS_MAPPED",
                    null,
                    OffsetDateTime.now());
        }
        return new Decision(
                TurnStatus.ACTIONS_READY,
                "ACTIONS_MAPPED",
                null,
                List.of(),
                actions,
                ambiguity.logicalCallId());
    }

    private List<IntentScoringService.ScoredQuestion> retainSelection(
            List<IntentScoringService.ScoredQuestion> questions, UUID selectedIntentNodeId) {
        if (questions.size() != 1) {
            throw new IllegalArgumentException("a clarification selection requires exactly one subquestion");
        }
        var question = questions.get(0);
        var selected = question.candidates().stream()
                .filter(candidate -> candidate.nodeId().equals(selectedIntentNodeId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("selected intent is not a current candidate"));
        return List.of(new IntentScoringService.ScoredQuestion(
                question.subQuestionId(),
                question.ordinal(),
                question.text(),
                List.of(selected),
                question.reasonCode(),
                question.logicalCallId()));
    }

    private AmbiguityResult inspectAmbiguity(
            UUID turnId,
            UUID revisionId,
            List<IntentScoringService.ScoredQuestion> questions,
            UUID selectedIntentNodeId) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        if (selectedIntentNodeId != null) {
            recordStage(
                    turnId,
                    revisionId,
                    TurnStage.AMBIGUITY,
                    0,
                    StageOutcome.SUCCEEDED,
                    "EXPLICIT_CLARIFICATION_SELECTION",
                    null,
                    startedAt);
            return new AmbiguityResult(false, "EXPLICIT_CLARIFICATION_SELECTION", null);
        }
        if (questions.size() != 1) {
            recordStage(turnId, revisionId, TurnStage.AMBIGUITY, 0, StageOutcome.SUCCEEDED,
                    "MULTI_QUESTION_NOT_AMBIGUOUS", null, startedAt);
            return new AmbiguityResult(false, "MULTI_QUESTION_NOT_AMBIGUOUS", null);
        }

        var representatives = categoryRepresentatives(questions.get(0).candidates());
        double fallback = properties.getThresholds().getFallback();
        if (representatives.size() < 2 || representatives.get(0).score() < fallback) {
            recordStage(turnId, revisionId, TurnStage.AMBIGUITY, 0, StageOutcome.SUCCEEDED,
                    "AMBIGUITY_NOT_APPLICABLE", null, startedAt);
            return new AmbiguityResult(false, "AMBIGUITY_NOT_APPLICABLE", null);
        }

        double margin = properties.getThresholds().getAmbiguityMargin();
        double gap = representatives.get(0).score() - representatives.get(1).score();
        if (gap <= margin + SCORE_EPSILON) {
            recordStage(turnId, revisionId, TurnStage.AMBIGUITY, 0, StageOutcome.SUCCEEDED,
                    "AMBIGUOUS_SCORE_GAP", null, startedAt);
            return new AmbiguityResult(true, "AMBIGUOUS_SCORE_GAP", null);
        }
        if (gap > margin * 2) {
            recordStage(turnId, revisionId, TurnStage.AMBIGUITY, 0, StageOutcome.SUCCEEDED,
                    "CLEAR_SCORE_GAP", null, startedAt);
            return new AmbiguityResult(false, "CLEAR_SCORE_GAP", null);
        }
        return confirmGrayArea(turnId, revisionId, questions.get(0).text(), representatives, startedAt);
    }

    private AmbiguityResult confirmGrayArea(
            UUID turnId,
            UUID revisionId,
            String question,
            List<IntentScoringService.IntentCandidate> representatives,
            OffsetDateTime startedAt) {
        try {
            var command = QaChatCommandFactory.balanced(List.of(
                    new SystemMessage(prompts.render(QaPrompt.AMBIGUITY_CONFIRM_SYSTEM, Map.of())),
                    new UserMessage(prompts.render(QaPrompt.AMBIGUITY_CONFIRM_USER, Map.of(
                            "question", question,
                            "candidates", encodeCandidates(representatives))))));
            var result = chat.complete(command);
            UUID logicalCallId = result.execution().logicalCallId();
            Boolean ambiguous = parseConfirmation(result.message().text());
            if (ambiguous == null) {
                recordStage(turnId, revisionId, TurnStage.AMBIGUITY, 0, StageOutcome.DEGRADED,
                        "AMBIGUITY_CONFIRM_INVALID_RESPONSE", logicalCallId, startedAt);
                return new AmbiguityResult(true, "AMBIGUITY_CONFIRM_INVALID_RESPONSE", logicalCallId);
            }
            String reason = ambiguous ? "AMBIGUITY_CONFIRMED" : "AMBIGUITY_REJECTED";
            recordStage(turnId, revisionId, TurnStage.AMBIGUITY, 0, StageOutcome.SUCCEEDED,
                    reason, logicalCallId, startedAt);
            return new AmbiguityResult(ambiguous, reason, logicalCallId);
        } catch (ModelCallException exception) {
            UUID logicalCallId = exception.execution().logicalCallId();
            recordStage(turnId, revisionId, TurnStage.AMBIGUITY, 0, StageOutcome.DEGRADED,
                    "AMBIGUITY_CONFIRM_UNAVAILABLE", logicalCallId, startedAt);
            return new AmbiguityResult(true, "AMBIGUITY_CONFIRM_UNAVAILABLE", logicalCallId);
        } catch (RuntimeException exception) {
            recordStage(turnId, revisionId, TurnStage.AMBIGUITY, 0, StageOutcome.DEGRADED,
                    "AMBIGUITY_CONFIRM_UNAVAILABLE", null, startedAt);
            return new AmbiguityResult(true, "AMBIGUITY_CONFIRM_UNAVAILABLE", null);
        }
    }

    private String encodeCandidates(List<IntentScoringService.IntentCandidate> candidates) {
        try {
            return json.writeValueAsString(candidates.stream()
                    .limit(3)
                    .map(candidate -> Map.of(
                            "nodeId", candidate.nodeId(),
                            "categoryNodeId", candidate.categoryNodeId(),
                            "fullPath", candidate.fullPath()))
                    .toList());
        } catch (Exception exception) {
            throw new IllegalStateException("cannot encode ambiguity candidates", exception);
        }
    }

    private Boolean parseConfirmation(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            JsonNode root = json.readTree(raw);
            if (root == null
                    || !root.isObject()
                    || root.size() != 1
                    || !root.has("ambiguous")
                    || !root.get("ambiguous").isBoolean()) {
                return null;
            }
            return root.get("ambiguous").booleanValue();
        } catch (Exception exception) {
            return null;
        }
    }

    private List<ClarificationOption> options(
            List<IntentScoringService.IntentCandidate> candidates) {
        var representatives = categoryRepresentatives(candidates);
        var options = new ArrayList<ClarificationOption>();
        for (int index = 0; index < Math.min(3, representatives.size()); index++) {
            var candidate = representatives.get(index);
            options.add(new ClarificationOption(
                    index + 1,
                    candidate.nodeId(),
                    candidate.categoryNodeId(),
                    candidate.fullPath()));
        }
        return List.copyOf(options);
    }

    private static List<IntentScoringService.IntentCandidate> categoryRepresentatives(
            List<IntentScoringService.IntentCandidate> candidates) {
        var categories = new LinkedHashMap<UUID, IntentScoringService.IntentCandidate>();
        candidates.stream()
                .filter(candidate -> candidate.type() == IntentNodeType.KB)
                .forEach(candidate -> categories.putIfAbsent(candidate.categoryNodeId(), candidate));
        return List.copyOf(categories.values());
    }

    private List<RetrievalAction> actions(
            List<IntentScoringService.ScoredQuestion> questions) {
        if (sameExclusiveSystem(questions)) {
            var actions = new ArrayList<RetrievalAction>();
            for (var question : questions) {
                var candidate = question.candidates().get(0);
                actions.add(new RetrievalAction(
                        question.subQuestionId(),
                        1,
                        RetrievalActionType.SYSTEM,
                        candidate.nodeId(),
                        null,
                        candidate.systemAction(),
                        "SAME_SYSTEM_PRIMARY"));
            }
            return List.copyOf(actions);
        }

        var actions = new ArrayList<RetrievalAction>();
        for (var question : questions) {
            var kbCandidates = question.candidates().stream()
                    .filter(candidate -> candidate.type() == IntentNodeType.KB)
                    .toList();
            int ordinal = 1;
            for (var candidate : kbCandidates) {
                if (candidate.score() >= properties.getThresholds().getTargeted()) {
                    actions.add(new RetrievalAction(
                            question.subQuestionId(),
                            ordinal++,
                            RetrievalActionType.KB_TARGETED,
                            candidate.nodeId(),
                            candidate.knowledgeBaseId(),
                            null,
                            "SCORE_AT_OR_ABOVE_TARGETED"));
                }
            }
            if (kbCandidates.isEmpty()) {
                RetrievalActionType type = question.reasonCode() == null
                        ? RetrievalActionType.GLOBAL_FALLBACK
                        : RetrievalActionType.NO_MATCH;
                String reason = question.reasonCode() == null ? "NO_CANDIDATE" : question.reasonCode();
                actions.add(new RetrievalAction(
                        question.subQuestionId(), ordinal, type, null, null, null, reason));
                continue;
            }
            double topScore = kbCandidates.get(0).score();
            boolean belowFallback = topScore < properties.getThresholds().getFallback();
            boolean singleNeedsSupplement = kbCandidates.size() == 1
                    && topScore < properties.getThresholds().getSupplement();
            if (belowFallback || singleNeedsSupplement) {
                actions.add(new RetrievalAction(
                        question.subQuestionId(),
                        ordinal,
                        RetrievalActionType.GLOBAL_FALLBACK,
                        null,
                        null,
                        null,
                        belowFallback ? "TOP_SCORE_BELOW_FALLBACK" : "SINGLE_INTENT_NEEDS_SUPPLEMENT"));
            }
        }
        return List.copyOf(actions);
    }

    private static boolean sameExclusiveSystem(
            List<IntentScoringService.ScoredQuestion> questions) {
        UUID nodeId = null;
        SystemAction systemAction = null;
        for (var question : questions) {
            if (question.candidates().size() != 1
                    || question.candidates().get(0).type() != IntentNodeType.SYSTEM) {
                return false;
            }
            var candidate = question.candidates().get(0);
            if (nodeId == null) {
                nodeId = candidate.nodeId();
                systemAction = candidate.systemAction();
            } else if (!nodeId.equals(candidate.nodeId()) || systemAction != candidate.systemAction()) {
                return false;
            }
        }
        return true;
    }

    private void recordStage(
            UUID turnId,
            UUID revisionId,
            TurnStage stage,
            int ordinal,
            StageOutcome outcome,
            String reasonCode,
            UUID logicalCallId,
            OffsetDateTime startedAt) {
        stages.recordBestEffort(new TurnStageSink.StageEvent(
                turnId,
                stage,
                ordinal,
                outcome,
                reasonCode,
                logicalCallId,
                revisionId,
                startedAt,
                OffsetDateTime.now()));
    }

    public record Decision(
            TurnStatus status,
            String reasonCode,
            String guidance,
            List<ClarificationOption> options,
            List<RetrievalAction> actions,
            UUID ambiguityLogicalCallId) {

        public Decision {
            options = List.copyOf(options);
            actions = List.copyOf(actions);
        }
    }

    public record ClarificationOption(
            int ordinal, UUID nodeId, UUID categoryNodeId, String label) {}

    public record RetrievalAction(
            UUID subQuestionId,
            int ordinal,
            RetrievalActionType type,
            UUID intentNodeId,
            Long knowledgeBaseId,
            SystemAction systemAction,
            String reasonCode) {}

    private record AmbiguityResult(boolean ambiguous, String reasonCode, UUID logicalCallId) {}
}
