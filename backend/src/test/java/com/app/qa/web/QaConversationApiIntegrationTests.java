package com.app.qa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.conversation.TurnProcessor;
import com.app.qa.model.IntentNodeType;
import com.app.qa.model.RetrievalActionType;
import com.app.qa.model.RewriteMode;
import com.app.qa.model.SubQuestionSource;
import com.app.qa.model.TurnStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(QaConversationApiIntegrationTests.ProcessorConfiguration.class)
@Transactional
@TestPropertySource(properties = "app.knowledge.ingestion.enabled=false")
class QaConversationApiIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private CountingProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ConversationPersistenceService conversations;

    @BeforeEach
    void resetProcessor() {
        processor.reset();
    }

    @Test
    void api02_新问题返回201且相同幂等请求返回原稳定本轮() throws Exception {
        String requestId = "917c0cd3-293c-4476-97bf-ca581afa0ed0";
        String request = """
                {
                  "clientRequestId":"%s",
                  "conversationId":null,
                  "message":{"type":"QUESTION","text":"退款多久到账？"}
                }
                """.formatted(requestId);

        String firstBody = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversation.created").value(true))
                .andExpect(jsonPath("$.turn.status").value("FAILED"))
                .andExpect(jsonPath("$.turn.terminalReason").value("TEST_TERMINAL"))
                .andReturn().getResponse().getContentAsString();
        JsonNode first = json.readTree(firstBody);
        String conversationId = first.at("/conversation/conversationId").asText();
        String turnId = first.at("/turn/turnId").asText();

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace("\"conversationId\":null", "\"conversationId\":\"" + conversationId + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversation.created").value(false))
                .andExpect(jsonPath("$.turn.turnId").value(turnId))
                .andExpect(jsonPath("$.turn.status").value("FAILED"));

        assertThat(processor.invocations()).isEqualTo(1);
    }

    @Test
    void api03_幂等键复用不同正文返回ProblemDetail且不再次处理() throws Exception {
        String requestId = "92eb54df-799a-419f-8870-f83002baa75a";
        String firstBody = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(requestId, null, "退款多久到账？")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversationId = json.readTree(firstBody).at("/conversation/conversationId").asText();

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(requestId, conversationId, "退款需要什么材料？")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("幂等键已用于其他消息"))
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(processor.invocations()).isEqualTo(1);
    }

    @Test
    void api03_活动本轮存在时新问题返回ConversationBusy() throws Exception {
        processor.holdNext();
        String firstBody = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "9e0afbad-a6cb-4a35-a105-ed85689bf131", null, "第一问")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("SAVED"))
                .andReturn().getResponse().getContentAsString();
        String conversationId = json.readTree(firstBody).at("/conversation/conversationId").asText();

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "fc5bb605-7183-482b-a33b-96ddfe734383", conversationId, "第二问")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("当前对话仍在处理中"))
                .andExpect(jsonPath("$.code").value("CONVERSATION_BUSY"));

        assertThat(processor.invocations()).isEqualTo(1);
    }

    @Test
    void api04_对话和消息使用不透明游标稳定翻页() throws Exception {
        String firstBody = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "413c40b5-07ef-47bf-9ace-0c4fdc1337f7", null, "第一段对话")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String firstConversationId = json.readTree(firstBody).at("/conversation/conversationId").asText();
        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "d535f007-4311-4838-b0d9-a4c6ca01d22e", null, "第二段对话")))
                .andExpect(status().isCreated());

        String firstPageBody = mvc.perform(get("/api/qa/conversations").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].messagePreview").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        JsonNode firstPage = json.readTree(firstPageBody);
        String firstItemId = firstPage.at("/items/0/conversationId").asText();
        String cursor = firstPage.get("nextCursor").asText();

        mvc.perform(get("/api/qa/conversations").param("limit", "1").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(result -> assertThat(json.readTree(result.getResponse().getContentAsString())
                                .at("/items/0/conversationId").asText())
                        .isNotEqualTo(firstItemId));

        mvc.perform(get("/api/qa/conversations/{id}", firstConversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latestTurn.status").value("FAILED"));
        mvc.perform(get("/api/qa/conversations/{id}/messages", firstConversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].role").value("USER"))
                .andExpect(jsonPath("$.items[0].type").value("USER_QUERY"))
                .andExpect(jsonPath("$.items[0].content").value("第一段对话"));

        mvc.perform(get("/api/qa/conversations").param("cursor", "not-a-cursor"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
    }

    @Test
    void conv09_标题更新使用版本CAS并在冲突时返回当前版本() throws Exception {
        String body = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "95d614cf-47cb-49cb-8237-2335235416a7", null, "待改名对话")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversationId = json.readTree(body).at("/conversation/conversationId").asText();

        mvc.perform(patch("/api/qa/conversations/{id}", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"新标题","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新标题"))
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(patch("/api/qa/conversations/{id}", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"冲突标题","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_VERSION_CONFLICT"))
                .andExpect(jsonPath("$.currentVersion").value(1));

        mvc.perform(get("/api/qa/conversations/{id}", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("新标题"));
    }

    @Test
    void api05_本轮不属于当前对话时返回404而不是泄露它存在() throws Exception {
        String first = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "2b0f1d4e-9c33-4a71-8f52-4f0a1c7d9e10", null, "第一个对话")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "7d5c2a10-3e84-4b09-9a6d-1c8b5f2e4a37", null, "第二个对话")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String foreignConversationId = json.readTree(first).at("/conversation/conversationId").asText();
        String turnId = json.readTree(second).at("/turn/turnId").asText();

        // 本轮真实存在，只是不属于这个对话。响应必须与"根本不存在"完全一致——
        // 用 403 或带 detail 的 409 都会让调用方推断出"这个 turnId 是有效的"。
        mvc.perform(get("/api/qa/conversations/{conversationId}/turns/{turnId}",
                        foreignConversationId, turnId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TURN_NOT_FOUND"));
    }

    @Test
    void conv10_活动本轮阻止删除且终态对话删除后全部普通读取为404() throws Exception {
        processor.holdNext();
        String activeBody = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "f41414d7-8360-4ef0-bf3f-fb1be6af2823", null, "活动本轮")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String activeConversationId = json.readTree(activeBody).at("/conversation/conversationId").asText();

        mvc.perform(delete("/api/qa/conversations/{id}", activeConversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONVERSATION_HAS_ACTIVE_TURN"));

        String completedBody = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "25fc9f6d-fb7e-4f89-8c12-fcedd57eb539", null, "可删除本轮")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        JsonNode completed = json.readTree(completedBody);
        String conversationId = completed.at("/conversation/conversationId").asText();
        String turnId = completed.at("/turn/turnId").asText();

        mvc.perform(get("/api/qa/conversations/{conversationId}/turns/{turnId}", conversationId, turnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.status").value("FAILED"));
        mvc.perform(delete("/api/qa/conversations/{id}", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedVersion":0}
                                """))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/qa/conversations/{id}", conversationId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));
        mvc.perform(get("/api/qa/conversations/{id}/messages", conversationId))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/qa/conversations/{conversationId}/turns/{turnId}", conversationId, turnId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TURN_NOT_FOUND"));
    }

    @Test
    void api01_非法JSON字段媒体类型UUID和枚举统一返回ProblemDetail() throws Exception {
        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"f81744d2-7dcb-441d-b052-d15d2cb9ee0c",
                                  "conversationId":null,
                                  "message":{"type":"QUESTION","text":"问题"},
                                  "unexpected":true
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("{}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        mvc.perform(get("/api/qa/conversations/not-a-uuid"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"2a188558-a5a1-4095-9a16-bcb9817516d5",
                                  "conversationId":null,
                                  "message":{"type":"NOT_A_TYPE","text":"问题"}
                                }
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        assertThat(processor.invocations()).isZero();
    }

    @Test
    void conv02_校验或目标对话不存在时处理器零调用() throws Exception {
        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "a5829d60-0696-4379-8e36-ebbc73d4cc23", null, "   ")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "7115b737-d1a2-4dc2-b340-a5644e417acd",
                                "51f06c83-ed61-4e3f-94c3-1f4dc083fa77",
                                "追问")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CONVERSATION_NOT_FOUND"));

        assertThat(processor.invocations()).isZero();
    }

    @Test
    void conv02_用户消息事务保存失败返回503且处理器零调用() throws Exception {
        jdbc.execute("""
                alter table qa_message
                add constraint qa_message_force_api_failure
                check (content <> 'FORCE_SAVE_FAILURE')
                """);

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "781cdac7-9ec9-4f08-baea-3d674295ca54", null, "FORCE_SAVE_FAILURE")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("MESSAGE_PERSISTENCE_FAILED"));

        assertThat(processor.invocations()).isZero();
    }

    @Test
    void conv08_处理器异常后本轮收敛为可刷新读取的稳定失败() throws Exception {
        processor.failNext();

        String body = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                "ca0b10fc-6ec8-4536-ae75-6afdab860ad9", null, "触发处理异常")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("FAILED"))
                .andExpect(jsonPath("$.turn.terminalReason").value("PROCESSING_FAILED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode response = json.readTree(body);

        mvc.perform(get(
                        "/api/qa/conversations/{conversationId}/turns/{turnId}",
                        response.at("/conversation/conversationId").asText(),
                        response.at("/turn/turnId").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.status").value("FAILED"))
                .andExpect(jsonPath("$.turn.terminalReason").value("PROCESSING_FAILED"));
    }

    @Test
    void amb09到10_澄清选择API拒绝伪造节点并接受都不是或原选项() throws Exception {
        long revisionNo = jdbc.queryForObject(
                "select coalesce(max(revision_no), 0) + 1 from qa_intent_tree_revision", Long.class);
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, change_type, node_count, enabled_leaf_count)
                values (?, ?, 'CREATE', 0, 0)
                """, revisionId, revisionNo);
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        var source = conversations.submitQuestion(null, UUID.randomUUID(), "我要退货");
        conversations.markProcessing(source.turnId());
        conversations.completeUnderstanding(
                source.turnId(),
                revisionId,
                TurnStatus.WAITING_CLARIFICATION,
                "AMBIGUOUS",
                List.of(new ConversationPersistenceService.SubQuestionWrite(
                        UUID.randomUUID(), 0, "我要退货", SubQuestionSource.MODEL)),
                List.of(
                        new ConversationPersistenceService.ClarificationOptionWrite(
                                UUID.randomUUID(), 1, nodeA, UUID.randomUUID(), "退货政策"),
                        new ConversationPersistenceService.ClarificationOptionWrite(
                                UUID.randomUUID(), 2, nodeB, UUID.randomUUID(), "退款政策")),
                List.of());

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarificationRequest(
                                UUID.randomUUID().toString(),
                                source.conversationId().toString(),
                                source.turnId().toString(),
                                UUID.randomUUID().toString(),
                                false)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLARIFICATION_CHOICE_INVALID"));

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clarificationRequest(
                                UUID.randomUUID().toString(),
                                source.conversationId().toString(),
                                source.turnId().toString(),
                                nodeB.toString(),
                                false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conversation.created").value(false))
                .andExpect(jsonPath("$.turn.status").value("FAILED"));

        assertThat(processor.invocations()).isEqualTo(1);
    }

    @Test
    void api06_稳定Turn刷新恢复批准Inspector字段且不暴露内部诊断() throws Exception {
        long revisionNo = jdbc.queryForObject(
                "select coalesce(max(revision_no), 0) + 1 from qa_intent_tree_revision", Long.class);
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, change_type, node_count, enabled_leaf_count)
                values (?, ?, 'CREATE', 0, 0)
                """, revisionId, revisionNo);
        var turn = conversations.submitQuestion(null, UUID.randomUUID(), "退款多久到账？");
        conversations.markProcessing(turn.turnId());
        UUID subQuestionId = UUID.randomUUID();
        UUID nodeId = UUID.randomUUID();
        conversations.completeUnderstanding(
                turn.turnId(),
                revisionId,
                TurnStatus.ACTIONS_READY,
                "ACTIONS_MAPPED",
                RewriteMode.MODEL,
                List.of("SUMMARY_UNAVAILABLE"),
                true,
                8,
                "退款通常多久到账？",
                List.of(new ConversationPersistenceService.SubQuestionWrite(
                        subQuestionId, 0, "退款多久到账？", SubQuestionSource.MODEL)),
                List.of(new ConversationPersistenceService.IntentMatchWrite(
                        UUID.randomUUID(),
                        subQuestionId,
                        nodeId,
                        IntentNodeType.KB,
                        "售后服务 > 退款政策",
                        12L,
                        null,
                        0.91,
                        1,
                        nodeId)),
                List.of(),
                List.of(new ConversationPersistenceService.ActionWrite(
                        UUID.randomUUID(),
                        subQuestionId,
                        1,
                        RetrievalActionType.KB_TARGETED,
                        nodeId,
                        12L,
                        null,
                        "SCORE_AT_OR_ABOVE_TARGETED")));

        String body = mvc.perform(get(
                        "/api/qa/conversations/{conversationId}/turns/{turnId}",
                        turn.conversationId(),
                        turn.turnId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.degradations[0]").value("SUMMARY_UNAVAILABLE"))
                .andExpect(jsonPath("$.turn.intentRevisionId").value(revisionId.toString()))
                .andExpect(jsonPath("$.inspector.history.summaryUsed").value(true))
                .andExpect(jsonPath("$.inspector.history.recentCompleteTurns").value(8))
                .andExpect(jsonPath("$.inspector.rewrite.mode").value("MODEL"))
                .andExpect(jsonPath("$.inspector.rewrite.rewrittenQuestion")
                        .value("退款通常多久到账？"))
                .andExpect(jsonPath("$.inspector.subQuestions[0].matches[0].fullPath")
                        .value("售后服务 > 退款政策"))
                .andExpect(jsonPath("$.inspector.subQuestions[0].actions[0].type")
                        .value("KB_TARGETED"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .doesNotContain("logicalCallId", "ExecutionInfo", "prompt", "rawResponse", "secret");
    }

    private static String questionRequest(String requestId, String conversationId, String text) {
        String conversation = conversationId == null ? "null" : "\"" + conversationId + "\"";
        return """
                {
                  "clientRequestId":"%s",
                  "conversationId":%s,
                  "message":{"type":"QUESTION","text":"%s"}
                }
                """.formatted(requestId, conversation, text);
    }

    private static String clarificationRequest(
            String requestId,
            String conversationId,
            String sourceTurnId,
            String selectedNodeId,
            boolean noneOfTheAbove) {
        String selected = selectedNodeId == null ? "null" : "\"" + selectedNodeId + "\"";
        return """
                {
                  "clientRequestId":"%s",
                  "conversationId":"%s",
                  "message":{
                    "type":"CLARIFICATION_CHOICE",
                    "sourceTurnId":"%s",
                    "selectedNodeId":%s,
                    "noneOfTheAbove":%s
                  }
                }
                """.formatted(requestId, conversationId, sourceTurnId, selected, noneOfTheAbove);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProcessorConfiguration {

        @Bean
        @Primary
        CountingProcessor qaTestTurnProcessor(ConversationPersistenceService conversations) {
            return new CountingProcessor(conversations);
        }
    }

    static final class CountingProcessor implements TurnProcessor {

        private final ConversationPersistenceService conversations;
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicBoolean holdNext = new AtomicBoolean();
        private final AtomicBoolean failNext = new AtomicBoolean();

        CountingProcessor(ConversationPersistenceService conversations) {
            this.conversations = conversations;
        }

        @Override
        public void process(ConversationPersistenceService.TurnSubmission submission) {
            invocations.incrementAndGet();
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("simulated processor failure");
            }
            if (holdNext.getAndSet(false)) {
                return;
            }
            conversations.markProcessing(submission.turnId());
            conversations.completeTurn(submission.turnId(), TurnStatus.FAILED, "TEST_TERMINAL");
        }

        int invocations() {
            return invocations.get();
        }

        void reset() {
            invocations.set(0);
            holdNext.set(false);
            failNext.set(false);
        }

        void holdNext() {
            holdNext.set(true);
        }

        void failNext() {
            failNext.set(true);
        }
    }
}
