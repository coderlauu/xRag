package com.app.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.ai.model.AssistantMessage;
import com.app.ai.model.ChatClient;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatResult;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RequestId;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.SystemMessage;
import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.model.TurnStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Import(QuestionUnderstandingE2ETests.FakeChatConfiguration.class)
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = "app.knowledge.ingestion.enabled=false")
class QuestionUnderstandingE2ETests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeterministicQaChat chat;

    @Autowired
    private ConversationPersistenceService conversations;

    private UUID revisionId;
    private UUID nodeId;

    @BeforeEach
    void publishIntentTree() {
        chat.reset();
        long knowledgeBaseId = jdbc.queryForObject("""
                insert into knowledge_base
                    (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'text-embedding-v3', 1024)
                returning id
                """, Long.class,
                "qa-e2e-" + UUID.randomUUID(),
                "qa-e2e-" + UUID.randomUUID());
        long revisionNo = jdbc.queryForObject(
                "select coalesce(max(revision_no), 0) + 1 from qa_intent_tree_revision", Long.class);
        revisionId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        nodeId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, change_type, node_count, enabled_leaf_count)
                values (?, ?, 'CREATE', 2, 1)
                """, revisionId, revisionNo);
        jdbc.update("""
                insert into qa_intent_node_snapshot
                    (revision_id, node_id, parent_id, depth, sort_order, node_type,
                     name, description, examples, enabled, deleted)
                values (?, ?, null, 1, 0, 'GROUP', '售后服务', '售后服务分组', '[]', true, false)
                """, revisionId, groupId);
        jdbc.update("""
                insert into qa_intent_node_snapshot
                    (revision_id, node_id, parent_id, depth, sort_order, node_type,
                     name, description, examples, knowledge_base_id, prompt_snippet, enabled, deleted)
                values (?, ?, ?, 2, 0, 'KB', '退款政策', '退款到账与材料',
                        '["退款多久到账"]', ?, '仅用于后续生成', true, false)
                """, revisionId, nodeId, groupId, knowledgeBaseId);
        jdbc.update("""
                update qa_intent_tree_head
                   set current_revision_id = ?, current_revision_no = ?, updated_at = now()
                 where singleton_id = 1
                """, revisionId, revisionNo);
        chat.nodeId.set(nodeId);
        chat.scoringResponse.set("[{\"id\":\"" + nodeId + "\",\"score\":0.91}]");
    }

    @Test
    void e2e01_真实HTTP垂直链路稳定持久化且幂等重放不重复模型调用() throws Exception {
        UUID requestId = UUID.randomUUID();
        String request = """
                {
                  "clientRequestId":"%s",
                  "conversationId":null,
                  "message":{"type":"QUESTION","text":"退款多久到账？"}
                }
                """.formatted(requestId);

        String first = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("ACTIONS_READY"))
                .andExpect(jsonPath("$.turn.intentRevisionId").value(revisionId.toString()))
                .andExpect(jsonPath("$.inspector.rewrite.mode").value("MODEL"))
                .andExpect(jsonPath("$.inspector.subQuestions[0].matches[0].nodeId")
                        .value(nodeId.toString()))
                .andExpect(jsonPath("$.inspector.subQuestions[0].actions[0].type")
                        .value("KB_TARGETED"))
                .andReturn().getResponse().getContentAsString();
        String conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(first).at("/conversation/conversationId").asText();
        String turnId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(first).at("/turn/turnId").asText();

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request.replace(
                                "\"conversationId\":null",
                                "\"conversationId\":\"" + conversationId + "\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.turnId").value(turnId));
        mvc.perform(get(
                        "/api/qa/conversations/{conversationId}/turns/{turnId}",
                        conversationId,
                        turnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inspector.subQuestions[0].actions[0].knowledgeBaseId")
                        .isNumber());

        assertThat(chat.calls()).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_intent_match where turn_id = ? and intent_revision_id = ?",
                        Integer.class,
                        UUID.fromString(turnId),
                        revisionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_retrieval_action where turn_id = ?",
                        Integer.class,
                        UUID.fromString(turnId)))
                .isEqualTo(1);
    }

    @Test
    void e2e01_四主题业务拒绝不保存截断子问题且幂等重放零额外调用() throws Exception {
        chat.rewriteResponse.set("""
                {"rewrittenQuestion":"复合问题","subQuestions":["一","二","三","四"]}
                """);
        UUID requestId = UUID.randomUUID();
        String request = questionRequest(requestId, null, "请同时回答四个主题");

        String first = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("REJECTED_TOO_MANY_TOPICS"))
                .andExpect(jsonPath("$.turn.terminalReason").value("TOO_MANY_TOPICS"))
                .andReturn().getResponse().getContentAsString();
        String conversationId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(first).at("/conversation/conversationId").asText();
        String turnId = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(first).at("/turn/turnId").asText();

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(requestId, conversationId, "请同时回答四个主题")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.turnId").value(turnId));

        assertThat(chat.calls()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_subquestion where turn_id = ?",
                        Integer.class,
                        UUID.fromString(turnId)))
                .isZero();
    }

    @Test
    void e2e03_摘要与改写与评分组合失败仍形成可解释NoMatch稳定结果() throws Exception {
        UUID conversationId = null;
        for (int index = 0; index < 12; index++) {
            var previous = conversations.submitQuestion(
                    conversationId, UUID.randomUUID(), "历史问题-" + index);
            conversationId = previous.conversationId();
            conversations.markProcessing(previous.turnId());
            conversations.completeTurn(previous.turnId(), TurnStatus.ACTIONS_READY, "TEST_HISTORY");
        }
        chat.failSummary = true;
        chat.rewriteResponse.set("not-json");
        chat.scoringResponse.set("not-json");

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(
                                UUID.randomUUID(), conversationId.toString(), "当前问题")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("ACTIONS_READY"))
                .andExpect(jsonPath("$.turn.degradations")
                        .value(org.hamcrest.Matchers.contains(
                                "SUMMARY_UNAVAILABLE", "REWRITE_INVALID_RESPONSE")))
                .andExpect(jsonPath("$.inspector.rewrite.mode").value("NORMALIZED_FALLBACK"))
                .andExpect(jsonPath("$.inspector.subQuestions[0].actions[0].type")
                        .value("NO_MATCH"));

        assertThat(chat.calls()).isEqualTo(3);
    }

    @Test
    void e2e05_歧义短路刷新选择后第二轮形成动作且不重复同一歧义() throws Exception {
        long secondKnowledgeBaseId = jdbc.queryForObject("""
                insert into knowledge_base
                    (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'text-embedding-v3', 1024)
                returning id
                """, Long.class,
                "qa-e2e-second-" + UUID.randomUUID(),
                "qa-e2e-second-" + UUID.randomUUID());
        UUID secondNodeId = UUID.randomUUID();
        UUID groupId = jdbc.queryForObject("""
                select node_id from qa_intent_node_snapshot
                 where revision_id = ? and node_type = 'GROUP'
                """, UUID.class, revisionId);
        jdbc.update("""
                insert into qa_intent_node_snapshot
                    (revision_id, node_id, parent_id, depth, sort_order, node_type,
                     name, description, examples, knowledge_base_id, prompt_snippet, enabled, deleted)
                values (?, ?, ?, 2, 1, 'KB', '换货政策', '换货条件与流程',
                        '["能不能换货"]', ?, '仅用于后续生成', true, false)
                """, revisionId, secondNodeId, groupId, secondKnowledgeBaseId);
        jdbc.update("""
                update qa_intent_tree_revision
                   set node_count = 3, enabled_leaf_count = 2
                 where id = ?
                """, revisionId);
        chat.scoringResponse.set("[{\"id\":\"" + nodeId + "\",\"score\":0.80},"
                + "{\"id\":\"" + secondNodeId + "\",\"score\":0.70}]");

        String first = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(questionRequest(UUID.randomUUID(), null, "售后怎么办？")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("WAITING_CLARIFICATION"))
                .andExpect(jsonPath("$.assistantMessage.type").value("CLARIFICATION_GUIDANCE"))
                .andExpect(jsonPath("$.clarification.options.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        var firstJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(first);
        String conversationId = firstJson.at("/conversation/conversationId").asText();
        String sourceTurnId = firstJson.at("/turn/turnId").asText();

        mvc.perform(get(
                        "/api/qa/conversations/{conversationId}/turns/{turnId}",
                        conversationId,
                        sourceTurnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clarification.allowNoneOfTheAbove").value(true));

        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"%s",
                                  "conversationId":"%s",
                                  "message":{
                                    "type":"CLARIFICATION_CHOICE",
                                    "sourceTurnId":"%s",
                                    "selectedNodeId":"%s",
                                    "noneOfTheAbove":false
                                  }
                                }
                                """.formatted(
                                UUID.randomUUID(), conversationId, sourceTurnId, secondNodeId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("ACTIONS_READY"))
                .andExpect(jsonPath("$.clarification").doesNotExist())
                .andExpect(jsonPath("$.inspector.subQuestions[0].actions[0].intentNodeId")
                        .value(secondNodeId.toString()));

        assertThat(chat.calls()).isEqualTo(4);
    }

    private static String questionRequest(UUID requestId, String conversationId, String text) {
        String conversation = conversationId == null ? "null" : "\"" + conversationId + "\"";
        return """
                {
                  "clientRequestId":"%s",
                  "conversationId":%s,
                  "message":{"type":"QUESTION","text":"%s"}
                }
                """.formatted(requestId, conversation, text);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeChatConfiguration {

        @Bean
        @Primary
        DeterministicQaChat deterministicQaChat() {
            return new DeterministicQaChat();
        }
    }

    static final class DeterministicQaChat implements ChatClient {

        private final AtomicReference<UUID> nodeId = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> rewriteResponse = new AtomicReference<>();
        private final AtomicReference<String> scoringResponse = new AtomicReference<>();
        private volatile boolean failSummary;

        @Override
        public ChatResult complete(ChatCommand command) {
            calls.incrementAndGet();
            String system = ((SystemMessage) command.messages().get(0)).text();
            String response;
            if (system.contains("长期记忆")) {
                if (failSummary) {
                    throw new IllegalStateException("simulated summary failure");
                }
                response = "历史摘要";
            } else if (system.contains("改写当前问题")) {
                response = rewriteResponse.get();
            } else if (system.contains("可执行意图叶子")) {
                response = scoringResponse.get();
            } else {
                throw new IllegalStateException("unexpected QA chat prompt");
            }
            UUID logicalCallId = UUID.randomUUID();
            return new ChatResult(
                    new AssistantMessage(response, null, List.of()),
                    ChatFinishReason.STOP,
                    new TokenUsage(1, 1, 2),
                    new ExecutionInfo(
                            logicalCallId,
                            new RequestId("qa-e2e-" + calls.get()),
                            new ExecutionTarget(
                                    new ProviderId("fake-provider"),
                                    new DeploymentId("fake-chat"),
                                    new ModelIdentity("fake-chat-v1")),
                            1,
                            0,
                            0));
        }

        int calls() {
            return calls.get();
        }

        void reset() {
            calls.set(0);
            rewriteResponse.set("{\"rewrittenQuestion\":\"退款通常多久到账？\","
                    + "\"subQuestions\":[\"退款多久到账？\"]}");
            scoringResponse.set("[{\"id\":\"" + nodeId.get() + "\",\"score\":0.91}]");
            failSummary = false;
        }
    }
}
