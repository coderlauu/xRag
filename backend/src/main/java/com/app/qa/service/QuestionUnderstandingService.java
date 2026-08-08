package com.app.qa.service;

import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.conversation.TurnProcessor;
import com.app.qa.conversation.TurnStageSink;
import com.app.qa.intent.AmbiguityActionService;
import com.app.qa.intent.IntentScoringService;
import com.app.qa.intent.IntentTreeStore;
import com.app.qa.memory.ConversationMemoryService;
import com.app.qa.model.IntentNodeType;
import com.app.qa.model.StageOutcome;
import com.app.qa.model.SubQuestionSource;
import com.app.qa.model.TurnStage;
import com.app.qa.model.TurnStatus;
import com.app.qa.prompt.PromptBudgetExceededException;
import com.app.qa.rewrite.QueryRewriteService;
import com.app.qa.rewrite.RewriteDisposition;
import com.app.qa.rewrite.RewriteRequest;
import com.app.qa.rewrite.RewriteResult;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public final class QuestionUnderstandingService implements TurnProcessor {

    private final ConversationPersistenceService conversations;
    private final ConversationMemoryService memory;
    private final QueryRewriteService rewrite;
    private final IntentTreeStore trees;
    private final IntentScoringService scoring;
    private final AmbiguityActionService decisions;
    private final TurnStageSink stages;

    public QuestionUnderstandingService(
            ConversationPersistenceService conversations,
            ConversationMemoryService memory,
            QueryRewriteService rewrite,
            IntentTreeStore trees,
            IntentScoringService scoring,
            AmbiguityActionService decisions,
            TurnStageSink stages) {
        this.conversations = conversations;
        this.memory = memory;
        this.rewrite = rewrite;
        this.trees = trees;
        this.scoring = scoring;
        this.decisions = decisions;
        this.stages = stages;
    }

    @Override
    public void process(ConversationPersistenceService.TurnSubmission submission) {
        Objects.requireNonNull(submission, "submission");
        process(submission.turnId());
    }

    public void process(UUID turnId) {
        if (!conversations.markProcessing(turnId)) {
            return;
        }
        RewriteResult rewritten = null;
        var degradations = new LinkedHashSet<String>();
        try {
            var turnContext = conversations.findTurnContext(turnId)
                    .orElseThrow(() -> new IllegalStateException("processing turn is not readable"));
            var snapshot = trees.current();
            if (snapshot.revisionId() == null) {
                throw new IllegalStateException("intent tree has no published revision");
            }
            var memorySnapshot = memory.prepare(turnId);
            degradations.addAll(memorySnapshot.degradations());

            OffsetDateTime rewriteStarted = OffsetDateTime.now();
            rewritten = rewrite.rewrite(new RewriteRequest(
                    memorySnapshot.summary() == null ? "（无）" : memorySnapshot.summary(),
                    formatHistory(memorySnapshot.recentTurns()),
                    memorySnapshot.currentMessage().content()));
            if (rewritten.degradationCode() != null) {
                degradations.add(rewritten.degradationCode());
            }
            recordStage(
                    turnId,
                    TurnStage.REWRITE,
                    rewritten.degradationCode() == null ? StageOutcome.SUCCEEDED : StageOutcome.DEGRADED,
                    rewritten.degradationCode(),
                    rewritten.logicalCallId(),
                    snapshot.revisionId(),
                    rewriteStarted);

            if (rewritten.disposition() == RewriteDisposition.TOO_MANY_TOPICS) {
                conversations.rejectUnderstanding(
                        turnId,
                        TurnStatus.REJECTED_TOO_MANY_TOPICS,
                        "TOO_MANY_TOPICS",
                        rewritten.mode(),
                        List.copyOf(degradations));
                recordStage(turnId, TurnStage.FINALIZE, StageOutcome.SUCCEEDED,
                        "TOO_MANY_TOPICS", null, snapshot.revisionId(), OffsetDateTime.now());
                return;
            }

            List<IntentScoringService.ScoringQuestion> questions = new ArrayList<>();
            for (int ordinal = 0; ordinal < rewritten.subQuestions().size(); ordinal++) {
                questions.add(new IntentScoringService.ScoringQuestion(
                        UUID.randomUUID(), ordinal, rewritten.subQuestions().get(ordinal)));
            }
            var scored = scoring.score(turnId, snapshot, questions);
            UUID selectedIntentNodeId = turnContext.selectedIntentNodeId();
            if (selectedIntentNodeId != null) {
                scored = ensureSelectedCandidate(scored, snapshot, selectedIntentNodeId);
            }
            var decision = selectedIntentNodeId == null
                    ? decisions.decide(turnId, scored)
                    : decisions.decide(turnId, scored, selectedIntentNodeId);

            boolean completed = conversations.completeUnderstanding(
                    turnId,
                    scored.revisionId(),
                    decision.status(),
                    decision.reasonCode(),
                    rewritten.mode(),
                    List.copyOf(degradations),
                    memorySnapshot.summaryUsed(),
                    memorySnapshot.recentTurns().size(),
                    rewritten.rewrittenQuestion(),
                    subQuestions(scored, rewritten),
                    matches(scored),
                    options(decision),
                    actions(decision));
            recordStage(
                    turnId,
                    TurnStage.FINALIZE,
                    completed ? StageOutcome.SUCCEEDED : StageOutcome.SKIPPED,
                    completed ? decision.reasonCode() : "TERMINAL_CAS_LOST",
                    null,
                    scored.revisionId(),
                    OffsetDateTime.now());
        } catch (RuntimeException exception) {
            String reason = exception instanceof PromptBudgetExceededException
                    ? "INTENT_CATALOG_PROMPT_TOO_LARGE"
                    : "UNDERSTANDING_FAILED";
            conversations.rejectUnderstanding(
                    turnId,
                    TurnStatus.FAILED,
                    reason,
                    rewritten == null ? null : rewritten.mode(),
                    List.copyOf(degradations));
            recordStage(turnId, TurnStage.FINALIZE, StageOutcome.DEGRADED,
                    reason, null, null, OffsetDateTime.now());
        }
    }

    private static List<ConversationPersistenceService.SubQuestionWrite> subQuestions(
            IntentScoringService.ScoringBatch scored, RewriteResult rewritten) {
        SubQuestionSource source = rewritten.mode() == com.app.qa.model.RewriteMode.MODEL
                ? SubQuestionSource.MODEL
                : SubQuestionSource.NORMALIZED_FALLBACK;
        return scored.questions().stream()
                .map(question -> new ConversationPersistenceService.SubQuestionWrite(
                        question.subQuestionId(), question.ordinal(), question.text(), source))
                .toList();
    }

    private static List<ConversationPersistenceService.IntentMatchWrite> matches(
            IntentScoringService.ScoringBatch scored) {
        var matches = new ArrayList<ConversationPersistenceService.IntentMatchWrite>();
        for (var question : scored.questions()) {
            for (var candidate : question.candidates()) {
                matches.add(new ConversationPersistenceService.IntentMatchWrite(
                        UUID.randomUUID(),
                        question.subQuestionId(),
                        candidate.nodeId(),
                        candidate.type(),
                        candidate.fullPath(),
                        candidate.knowledgeBaseId(),
                        candidate.systemAction(),
                        candidate.score(),
                        candidate.rank(),
                        candidate.categoryNodeId()));
            }
        }
        return List.copyOf(matches);
    }

    private static List<ConversationPersistenceService.ClarificationOptionWrite> options(
            AmbiguityActionService.Decision decision) {
        return decision.options().stream()
                .map(option -> new ConversationPersistenceService.ClarificationOptionWrite(
                        UUID.randomUUID(),
                        option.ordinal(),
                        option.nodeId(),
                        option.categoryNodeId(),
                        option.label()))
                .toList();
    }

    private static List<ConversationPersistenceService.ActionWrite> actions(
            AmbiguityActionService.Decision decision) {
        return decision.actions().stream()
                .map(action -> new ConversationPersistenceService.ActionWrite(
                        UUID.randomUUID(),
                        action.subQuestionId(),
                        action.ordinal(),
                        action.type(),
                        action.intentNodeId(),
                        action.knowledgeBaseId(),
                        action.systemAction(),
                        action.reasonCode()))
                .toList();
    }

    private static IntentScoringService.ScoringBatch ensureSelectedCandidate(
            IntentScoringService.ScoringBatch scored,
            IntentTreeStore.IntentTreeSnapshot snapshot,
            UUID selectedIntentNodeId) {
        if (scored.questions().size() != 1) {
            throw new IllegalStateException("clarification choice must produce exactly one subquestion");
        }
        var existing = scored.questions().get(0).candidates().stream()
                .filter(candidate -> candidate.nodeId().equals(selectedIntentNodeId))
                .findFirst();
        if (existing.isPresent()) {
            return scored;
        }
        var selectedNode = snapshot.nodes().stream()
                .filter(node -> node.nodeId().equals(selectedIntentNodeId)
                        && !node.deleted()
                        && node.enabled()
                        && node.type() != IntentNodeType.GROUP)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("selected clarification node is no longer enabled"));
        UUID categoryId = selectedNode.type() == IntentNodeType.KB && selectedNode.depth() >= 3
                ? selectedNode.parentId()
                : selectedNode.nodeId();
        var selected = new IntentScoringService.IntentCandidate(
                selectedNode.nodeId(),
                selectedNode.type(),
                fullPath(selectedNode, snapshot.nodes()),
                selectedNode.knowledgeBaseId(),
                selectedNode.systemAction(),
                categoryId,
                1.0,
                1);
        var question = scored.questions().get(0);
        return new IntentScoringService.ScoringBatch(
                scored.revisionId(),
                List.of(new IntentScoringService.ScoredQuestion(
                        question.subQuestionId(),
                        question.ordinal(),
                        question.text(),
                        List.of(selected),
                        question.reasonCode(),
                        question.logicalCallId())));
    }

    private static String fullPath(
            IntentTreeStore.IntentNode selected,
            List<IntentTreeStore.IntentNode> nodes) {
        var names = new ArrayList<String>();
        var current = selected;
        while (current != null) {
            names.add(current.name());
            UUID parentId = current.parentId();
            current = IntentTreeStore.VIRTUAL_ROOT_ID.equals(parentId)
                    ? null
                    : nodes.stream().filter(node -> node.nodeId().equals(parentId)).findFirst().orElse(null);
        }
        java.util.Collections.reverse(names);
        return String.join(" > ", names);
    }

    private static String formatHistory(List<ConversationMemoryService.HistoryTurn> history) {
        var formatted = new StringBuilder();
        for (var turn : history) {
            for (var message : turn.messages()) {
                formatted.append(message.role())
                        .append('/')
                        .append(message.type())
                        .append(": ")
                        .append(message.content())
                        .append('\n');
            }
        }
        return formatted.toString();
    }

    private void recordStage(
            UUID turnId,
            TurnStage stage,
            StageOutcome outcome,
            String reasonCode,
            UUID logicalCallId,
            UUID revisionId,
            OffsetDateTime startedAt) {
        stages.recordBestEffort(new TurnStageSink.StageEvent(
                turnId,
                stage,
                0,
                outcome,
                reasonCode,
                logicalCallId,
                revisionId,
                startedAt,
                OffsetDateTime.now()));
    }
}
