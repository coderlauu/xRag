package com.app.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.ai.model.AssistantMessage;
import com.app.ai.model.ChatClient;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatResult;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RequestId;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.SystemMessage;
import com.app.ai.model.StreamingChatClient;
import com.app.knowledge.FakeEmbeddingConfig;
import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.model.TurnStatus;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
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
import reactor.core.publisher.Flux;

@SpringBootTest
@AutoConfigureMockMvc
/*
 * 检索接进 turn 处理流程后，走到 ACTIONS_READY 的用例会真的执行一次检索——而检索要算查询
 * 向量。本类原先没有假 Embedding，那次调用会打到真实的阿里云接口上：**跑一次测试就是一次
 * 真实计费**，而且离线时会失败。导入 FakeEmbeddingConfig 把它按住。
 *
 * 顺带说明为什么不是关掉派发器就够了：那条属性只管入库派发器，管不到检索——检索是同步的，
 * 就在请求线程里跑。
 */
@Import({QuestionUnderstandingE2ETests.FakeChatConfiguration.class, com.app.knowledge.FakeEmbeddingConfig.class})
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = "app.knowledge.ingestion.enabled=false")
class QuestionUnderstandingE2ETests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.app.knowledge.vector.ChunkVectorRepository vectors;

    @Autowired
    private DeterministicQaChat chat;

    @Autowired
    private DeterministicGenerationStream generationStream;

    @Autowired
    private ConversationPersistenceService conversations;

    private UUID revisionId;
    private UUID nodeId;
    private long knowledgeBaseId;

    @BeforeEach
    void publishIntentTree() {
        chat.reset();
        generationStream.reset();
        knowledgeBaseId = jdbc.queryForObject("""
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
    void 理解完成后接着执行检索且检索失败不改变本轮终态() throws Exception {
        String request = """
                {
                  "clientRequestId":"%s",
                  "conversationId":null,
                  "message":{"type":"QUESTION","text":"退款多久到账？"}
                }
                """.formatted(UUID.randomUUID());

        // 正常路径：走到 ACTIONS_READY 就该有 retrieval 块。库里没有内容，所以是 NO_RESULT
        // ——"查了但没有"，与"没查过"（retrieval=null）是两个不同的事实。
        //
        // 反向的那半（WAITING_CLARIFICATION / TOO_MANY_TOPICS 时不检索）由
        // `completed && decision.status() == ACTIONS_READY` 这个显式守卫保证，两条短路路径
        // 本身已由 e2e05 与 e2e01_四主题 覆盖。单独为它造一次歧义要 seed 第二个意图节点
        // （见 e2e05 开头那三十行），成本远超收益。
        mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("ACTIONS_READY"))
                .andExpect(jsonPath("$.retrieval").exists())
                .andExpect(jsonPath("$.retrieval.status").value("NO_RESULT"));

        // 失败路径：查询向量算不出来，检索整体失败。**本轮终态必须还是 ACTIONS_READY**
        // ——理解已经成功了，把检索的失败写成 turn 的 FAILED 会让用户以为"问题没读懂"，
        // 而实际是"库没查成"，两者的下一步完全不同（PRD §12.3）。
        FakeEmbeddingConfig.SHOULD_FAIL.set(true);
        try {
            mvc.perform(post("/api/qa/turns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "clientRequestId":"%s",
                                      "conversationId":null,
                                      "message":{"type":"QUESTION","text":"退款多久到账？"}
                                    }
                                    """.formatted(UUID.randomUUID())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.turn.status").value("ACTIONS_READY"))
                    .andExpect(jsonPath("$.turn.terminalReason").value("ACTIONS_MAPPED"));
        } finally {
            FakeEmbeddingConfig.SHOULD_FAIL.set(false);
        }
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

        @Bean
        @Primary
        DeterministicGenerationStream deterministicGenerationStream() {
            return new DeterministicGenerationStream();
        }
    }

    static final class DeterministicGenerationStream implements StreamingChatClient {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<ChatCommand> command = new AtomicReference<>();

        @Override
        public Flux<ChatStreamEvent> stream(ChatCommand command) {
            this.command.set(command);
            calls.incrementAndGet();
            return Flux.just(
                    new ChatStreamEvent.TextDelta("退款通常三到五个工作日到账。"),
                    new ChatStreamEvent.Completed(ChatFinishReason.STOP, new TokenUsage(2, 4, 6)));
        }

        void reset() {
            calls.set(0);
            command.set(null);
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

    /**
     * 3B-E2E-01：动作 → 三通道 → 融合 → 精排 → 来源落库 → API 返回，一条完整链路。
     *
     * <p><b>本方法不参与类级事务</b>：检索跑在 {@code qaRetrievalExecutor} 的其他线程上，看不见
     * 测试事务里未提交的种子数据（工单 08 踩过这个坑，症状是所有用例都 NO_RESULT，看起来像
     * 检索坏了）。所以种子真提交，用 finally 清理。
     */
    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void e2e01_3B_有内容时全链路召回并在API上返回来源() throws Exception {
        long docId = jdbc.queryForObject("""
                insert into source_document (kb_id, name, source_type, storage_object_id)
                values (?, '退货与退款操作手册.txt', 'FILE', ?) returning id
                """, Long.class, knowledgeBaseId, UUID.randomUUID().toString());
        String content = "退款到账时间说明：银行卡退款通常三到五个工作日到账";
        long chunkId = jdbc.queryForObject("""
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                values (?, ?, 1, 7, ?, ?, 1,
                        '0000000000000000000000000000000000000000000000000000000000000000')
                returning id
                """, Long.class, knowledgeBaseId, docId, content, content.length());
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        vectors.insertAll(knowledgeBaseId, docId, java.util.List.of(chunkId), java.util.List.of(vector));

        try {
            String response = mvc.perform(post("/api/qa/turns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "clientRequestId":"%s",
                                      "conversationId":null,
                                      "message":{"type":"QUESTION","text":"退款多久到账？"}
                                    }
                                    """.formatted(UUID.randomUUID())))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.turn.status").value("ACTIONS_READY"))
                    .andExpect(jsonPath("$.retrieval.status").value("RETRIEVED"))
                    .andExpect(jsonPath("$.retrieval.sources[0].chunkId").value((int) chunkId))
                    // 展示三项齐全：库名、文档名、分块序号（沿用知识库页的序号语义，不重编号）。
                    .andExpect(jsonPath("$.retrieval.sources[0].documentName")
                            .value("退货与退款操作手册.txt"))
                    .andExpect(jsonPath("$.retrieval.sources[0].chunkIndex").value(7))
                    .andExpect(jsonPath("$.retrieval.sources[0].documentDeleted").value(false))
                    .andReturn().getResponse().getContentAsString();

            // 落库与响应一致：来源真的进了 qa_retrieval_source，不是只在响应里拼出来的。
            String turnId = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response).at("/turn/turnId").asText();
            assertThat(jdbc.queryForObject(
                    "select count(*) from qa_retrieval_source where turn_id = ?",
                    Integer.class, UUID.fromString(turnId))).isEqualTo(1);
            // 漏斗三个计数都落了库（架构 §22.2：课程实现里它们只存在于 log.info）。
            assertThat(jdbc.queryForObject(
                    "select channel_hits from qa_turn_retrieval where turn_id = ?",
                    Integer.class, UUID.fromString(turnId))).isPositive();

            // **只扫 retrieval 块**：整个响应里是有 score 的——inspector.subQuestions[].matches[].score
            // 是 3A 的意图匹配分数，属于验收信息，ui-spec §7 明确允许它以强/中/低区间出现在
            // 验收面板。把断言范围写成整个响应会误报这个正当字段，那种"过宽的隐私断言"迟早
            // 被人改松，还不如一开始就画准范围。
            String retrievalBlock = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(response).at("/retrieval").toString();
            assertThat(retrievalBlock)
                    .as("3B 来源不带任何分数——库里本来就不存（数据模型 §14.2），这是第二道闸")
                    .doesNotContain("score")
                    .doesNotContain("relevance")
                    // 命中通道只进处理详情，避免"命中两次更可信"的错误暗示。
                    .doesNotContain("originChannel")
                    .doesNotContain("INTENT_TARGETED");
        } finally {
            jdbc.update("delete from qa_retrieval_source where turn_id in "
                    + "(select id from qa_turn where conversation_id in "
                    + "(select id from qa_conversation where title is not null))");
            jdbc.update("delete from document_chunk_keyword where kb_id = ?", knowledgeBaseId);
            jdbc.update("delete from document_chunk_embedding where kb_id = ?", knowledgeBaseId);
            jdbc.update("delete from document_chunk where kb_id = ?", knowledgeBaseId);
            jdbc.update("delete from source_document where kb_id = ?", knowledgeBaseId);
        }
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void e2e_3A到3B到3C经真实Http只调用一次流式模型并可Get恢复() throws Exception {
        long docId = jdbc.queryForObject("""
                insert into source_document (kb_id, name, source_type, storage_object_id)
                values (?, '3C退款说明.txt', 'FILE', ?) returning id
                """, Long.class, knowledgeBaseId, UUID.randomUUID().toString());
        String evidence = "3C垂直链路证据：银行卡退款通常三到五个工作日到账";
        long chunkId = jdbc.queryForObject("""
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                values (?, ?, 1, 9, ?, ?, 1,
                        '1111111111111111111111111111111111111111111111111111111111111111')
                returning id
                """, Long.class, knowledgeBaseId, docId, evidence, evidence.length());
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        vectors.insertAll(knowledgeBaseId, docId, List.of(chunkId), List.of(vector));

        UUID conversationId = null;
        try {
            var started = mvc.perform(post("/api/qa/turns:stream")
                            .contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM)
                            .content(questionRequest(UUID.randomUUID(), null, "退款多久到账？")))
                    .andExpect(status().isOk())
                    .andReturn();
            started.getAsyncResult(Duration.ofSeconds(10).toMillis());
            String sse = mvc.perform(asyncDispatch(started))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var acceptedLine = sse.lines().filter(line -> line.startsWith("data:"))
                    .findFirst().orElseThrow().substring(5);
            var accepted = new com.fasterxml.jackson.databind.ObjectMapper().readTree(acceptedLine);
            conversationId = UUID.fromString(accepted.path("conversationId").asText());
            UUID turnId = UUID.fromString(accepted.path("turnId").asText());

            assertThat(sse).contains("event:accepted", "event:answer_delta", "event:completed");
            mvc.perform(get("/api/qa/conversations/{conversationId}/turns/{turnId}", conversationId, turnId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.turn.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.retrieval.status").value("RETRIEVED"))
                    .andExpect(jsonPath("$.retrieval.sources[0].chunkId").value((int) chunkId))
                    .andExpect(jsonPath("$.assistantMessage.content")
                            .value("退款通常三到五个工作日到账。"))
                    .andExpect(jsonPath("$.generation.status").value("COMPLETED"));
            assertThat(generationStream.calls).hasValue(1);
            assertThat(generationStream.command.get().toString())
                    .contains("银行卡退款通常三到五个工作日到账");
        } finally {
            if (conversationId != null) {
                jdbc.update("""
                        delete from qa_retrieval_source
                         where turn_id in (select id from qa_turn where conversation_id = ?)
                        """, conversationId);
            }
            jdbc.update("delete from document_chunk_keyword where chunk_id = ?", chunkId);
            jdbc.update("delete from document_chunk_embedding where chunk_id = ?", chunkId);
            jdbc.update("delete from document_chunk where id = ?", chunkId);
            jdbc.update("delete from source_document where id = ?", docId);
        }
    }
}
