package com.app.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.conversation.TurnStageSink;
import com.app.qa.intent.AmbiguityActionService;
import com.app.qa.intent.IntentScoringService;
import com.app.qa.intent.IntentTreeStore;
import com.app.qa.memory.ConversationMemoryService;
import com.app.qa.model.IntentNodeType;
import com.app.qa.model.MessageRole;
import com.app.qa.model.MessageType;
import com.app.qa.model.RetrievalActionType;
import com.app.qa.model.RewriteMode;
import com.app.qa.model.SubQuestionSource;
import com.app.qa.model.TurnStatus;
import com.app.qa.rewrite.QueryRewriteService;
import com.app.qa.rewrite.RewriteDisposition;
import com.app.qa.retrieval.RetrievalOrchestrator;
import com.app.qa.rewrite.RewriteResult;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class QuestionUnderstandingServiceTests {

    private ConversationPersistenceService conversations;
    private ConversationMemoryService memory;
    private QueryRewriteService rewrite;
    private IntentTreeStore trees;
    private IntentScoringService scoring;
    private AmbiguityActionService decisions;
    private List<TurnStageSink.StageEvent> stages;
    private RetrievalOrchestrator retrieval;
    private QuestionUnderstandingService service;

    @BeforeEach
    void setUp() {
        conversations = mock(ConversationPersistenceService.class);
        memory = mock(ConversationMemoryService.class);
        rewrite = mock(QueryRewriteService.class);
        trees = mock(IntentTreeStore.class);
        scoring = mock(IntentScoringService.class);
        decisions = mock(AmbiguityActionService.class);
        stages = new ArrayList<>();
        // 检索是 3A 之后的下一段，本类只验理解编排——给个假的就够，检索自己的行为由
        // RetrievalOrchestratorIntegrationTests 验。
        retrieval = mock(RetrievalOrchestrator.class);
        service = new QuestionUnderstandingService(
                conversations, memory, rewrite, trees, scoring, decisions, stages::add, retrieval);
    }

    @Test
    void e2e01到03_固定revision串起记忆改写评分动作并一次终态聚合() {
        UUID turnId = id(900);
        UUID revisionId = id(1);
        UUID subQuestionId = id(501);
        UUID nodeId = id(101);
        var submission = submission(turnId);
        var snapshot = new IntentTreeStore.IntentTreeSnapshot(
                revisionId, 7, OffsetDateTime.now(), List.of());
        var rewriteResult = new RewriteResult(
                "原问题", "改写问题", List.of("子问题"), RewriteMode.NORMALIZED_FALLBACK,
                RewriteDisposition.READY, "REWRITE_INVALID_RESPONSE", id(700));
        var candidate = new IntentScoringService.IntentCandidate(
                nodeId, IntentNodeType.KB, "售后 > 退款", 12L, null, nodeId, 0.91, 1);
        var scored = new IntentScoringService.ScoringBatch(
                revisionId,
                List.of(new IntentScoringService.ScoredQuestion(
                        subQuestionId, 0, "子问题", List.of(candidate), null, id(701))));
        var decision = new AmbiguityActionService.Decision(
                TurnStatus.ACTIONS_READY,
                "ACTIONS_MAPPED",
                null,
                List.of(),
                List.of(new AmbiguityActionService.RetrievalAction(
                        subQuestionId,
                        1,
                        RetrievalActionType.KB_TARGETED,
                        nodeId,
                        12L,
                        null,
                        "SCORE_AT_OR_ABOVE_TARGETED")),
                null);

        when(conversations.markProcessing(turnId)).thenReturn(true);
        when(conversations.findTurnContext(turnId)).thenReturn(java.util.Optional.of(
                new ConversationPersistenceService.TurnContext(id(800), null, null)));
        when(trees.current()).thenReturn(snapshot);
        when(memory.prepare(turnId)).thenReturn(memory("原问题", List.of("SUMMARY_UNAVAILABLE")));
        when(rewrite.rewrite(any())).thenReturn(rewriteResult);
        when(scoring.score(eq(turnId), eq(snapshot), anyList())).thenReturn(scored);
        when(decisions.decide(turnId, scored)).thenReturn(decision);
        when(conversations.completeUnderstanding(
                        eq(turnId), eq(revisionId), eq(TurnStatus.ACTIONS_READY), eq("ACTIONS_MAPPED"),
                        eq(RewriteMode.NORMALIZED_FALLBACK), anyList(), eq(false), eq(0),
                        eq("改写问题"), anyList(), anyList(),
                        anyList(), anyList()))
                .thenReturn(true);

        service.process(submission);

        var degradations = ArgumentCaptor.forClass(List.class);
        var subQuestions = ArgumentCaptor.forClass(List.class);
        var matches = ArgumentCaptor.forClass(List.class);
        verify(conversations).completeUnderstanding(
                eq(turnId), eq(revisionId), eq(TurnStatus.ACTIONS_READY), eq("ACTIONS_MAPPED"),
                eq(RewriteMode.NORMALIZED_FALLBACK), degradations.capture(), eq(false), eq(0),
                eq("改写问题"), subQuestions.capture(),
                matches.capture(), anyList(), anyList());
        assertThat(degradations.getValue())
                .containsExactly("SUMMARY_UNAVAILABLE", "REWRITE_INVALID_RESPONSE");
        assertThat((List<ConversationPersistenceService.SubQuestionWrite>) subQuestions.getValue())
                .singleElement()
                .satisfies(question -> {
                    assertThat(question.text()).isEqualTo("子问题");
                    assertThat(question.source()).isEqualTo(SubQuestionSource.NORMALIZED_FALLBACK);
                });
        assertThat((List<ConversationPersistenceService.IntentMatchWrite>) matches.getValue())
                .singleElement()
                .satisfies(match -> assertThat(match.nodeId()).isEqualTo(nodeId));
        assertThat(stages).extracting(event -> event.stage().name())
                .contains("REWRITE", "FINALIZE");
    }

    @Test
    void e2e01_超过三个主题稳定拒绝且不进入评分() {
        UUID turnId = id(900);
        when(conversations.markProcessing(turnId)).thenReturn(true);
        when(conversations.findTurnContext(turnId)).thenReturn(java.util.Optional.of(
                new ConversationPersistenceService.TurnContext(id(800), null, null)));
        when(trees.current()).thenReturn(new IntentTreeStore.IntentTreeSnapshot(
                id(1), 1, OffsetDateTime.now(), List.of()));
        when(memory.prepare(turnId)).thenReturn(memory("复合问题", List.of()));
        when(rewrite.rewrite(any())).thenReturn(new RewriteResult(
                "复合问题", "复合问题", List.of("一", "二", "三", "四"), RewriteMode.MODEL,
                RewriteDisposition.TOO_MANY_TOPICS, null, id(700)));

        service.process(submission(turnId));

        verify(conversations).rejectUnderstanding(
                turnId,
                TurnStatus.REJECTED_TOO_MANY_TOPICS,
                "TOO_MANY_TOPICS",
                RewriteMode.MODEL,
                List.of());
        verify(scoring, never()).score(any(), any(), anyList());
    }

    @Test
    void e2e04_未取得Processing所有权不重复任何外部调用() {
        UUID turnId = id(900);
        when(conversations.markProcessing(turnId)).thenReturn(false);

        service.process(submission(turnId));

        verify(memory, never()).prepare(any());
        verify(rewrite, never()).rewrite(any());
        verify(scoring, never()).score(any(), any(), anyList());
    }

    @Test
    void e2e03_整轮异常收敛为稳定Failed且不向API抛出() {
        UUID turnId = id(900);
        when(conversations.markProcessing(turnId)).thenReturn(true);
        when(conversations.findTurnContext(turnId)).thenReturn(java.util.Optional.of(
                new ConversationPersistenceService.TurnContext(id(800), null, null)));
        when(trees.current()).thenThrow(new IllegalStateException("tree unavailable"));

        service.process(submission(turnId));

        verify(conversations).rejectUnderstanding(
                turnId,
                TurnStatus.FAILED,
                "UNDERSTANDING_FAILED",
                null,
                List.of());
    }

    private static ConversationMemoryService.MemorySnapshot memory(
            String currentQuestion, List<String> degradations) {
        return new ConversationMemoryService.MemorySnapshot(
                null,
                false,
                List.of(),
                new ConversationMemoryService.MemoryMessage(
                        id(600),
                        1,
                        MessageRole.USER,
                        MessageType.USER_QUERY,
                        currentQuestion,
                        OffsetDateTime.now()),
                degradations);
    }

    private static ConversationPersistenceService.TurnSubmission submission(UUID turnId) {
        return new ConversationPersistenceService.TurnSubmission(
                id(800), turnId, id(600), TurnStatus.SAVED, false, false);
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }
}
