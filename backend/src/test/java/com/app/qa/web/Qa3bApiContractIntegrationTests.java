package com.app.qa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3B-API-01~04 与契约 §13 的隐私边界。
 *
 * <p><b>直接 seed 落库结果，不跑真实检索</b>：本类验的是"落库的东西怎么变成响应"，
 * 而不是"检索算得对不对"（那是 {@code RetrievalOrchestratorIntegrationTests} 的事）。
 * seed 也让"文档已删除""参数缺省"这些边角状态能被精确构造出来。
 *
 * <p>本类必须自带工具白名单（参数表的展示名来自它），因此不可避免地是一个<b>新的上下文变体</b>。
 * 每个变体都带一条 Hikari 池，而测试期间 Postgres 连接峰值已经到 88/100——所以顺手把本变体的
 * 池压到 6。上限只需大于 {@code app.qa.retrieval.max-parallelism}（默认 4，启动时校验），
 * 本类又不跑并行检索，6 足够。新增测试上下文时压低它的池，是这个套件里必须养成的习惯：
 * 实测给某个类单独加一条属性覆盖、让它独占默认 10 连接的上下文，会把入库测试的
 * {@code awaitStatus} 超时从 1 个推到 3 个。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@org.springframework.test.context.TestPropertySource(
        properties = {
            "app.knowledge.ingestion.enabled=false",
            "spring.datasource.hikari.maximum-pool-size=6",
            "app.qa.tools.whitelist[0].name=submit-return-ticket",
            "app.qa.tools.whitelist[0].display-name=提交退货工单",
            "app.qa.tools.whitelist[0].description=在售后工单系统中新建一张退货工单。",
            "app.qa.tools.whitelist[0].side-effect=true",
            "app.qa.tools.whitelist[0].input-schema=classpath:tools/qa/3b/submit-return-ticket.schema.json",
        })
class Qa3bApiContractIntegrationTests {

    /**
     * 白名单里的工具必须有执行器，否则上下文起不来——工单 09 有意的启动校验（"确认之后才发现
     * 调不到"是最差的失败时机）。本类只读响应、不执行工具，所以给一个永不被调用的假执行器。
     */
    @org.springframework.boot.test.context.TestConfiguration
    static class Tools {

        @org.springframework.context.annotation.Bean
        com.app.qa.tool.ToolExecutor submitReturnTicket() {
            return new com.app.qa.tool.ToolExecutor() {
                @Override
                public String name() {
                    return "submit-return-ticket";
                }

                @Override
                public com.app.qa.tool.ToolExecutionResult execute(
                        String argumentsJson, java.time.Duration timeout) {
                    throw new AssertionError("本类不执行工具，只验响应形态");
                }
            };
        }
    }

    private static final String CHUNK_TEXT = "退货需要提供订单号与凭证照片，七日内可申请。";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    private UUID conversationId;
    private final java.util.Map<UUID, UUID> subQuestionOf = new java.util.HashMap<>();

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID();
        subQuestionOf.clear();
        jdbc.update(
                "insert into qa_conversation (id, title, last_active_at) values (?, '3B 契约', now())",
                conversationId);
    }

    @Test
    void api01_未进过3B时retrieval为null且tools为空数组() throws Exception {
        var turn = seedTurn();

        mvc.perform(get("/api/qa/conversations/{c}/turns/{t}", conversationId, turn))
                .andExpect(status().isOk())
                // "没检索过"是 null，不是空对象——空对象会让客户端以为查过且没结果。
                .andExpect(jsonPath("$.retrieval").doesNotExist())
                .andExpect(jsonPath("$.tools").isArray())
                .andExpect(jsonPath("$.tools", org.hamcrest.Matchers.hasSize(0)))
                // 3A 字段逐字段不变：turn 块仍在原位。
                .andExpect(jsonPath("$.turn.turnId").value(turn.toString()));
    }

    @Test
    void api02_响应不出现分数正文通道名Schema原文与异常message() throws Exception {
        var kb = seedKb("契约扫描", CHUNK_TEXT);
        var turn = seedTurn();
        seedRetrieval(turn, "RETRIEVED", false, false, "RERANK_UNAVAILABLE");
        seedSource(turn, 0, kb.chunkId(), kb.kbId(), kb.docId(), "INTENT_TARGETED");
        seedToolCall(turn, 0, "PENDING_CONFIRM", """
                {"orderNo":"ORD-20260806-1183","reason":"质量问题"}""");

        String body = mvc.perform(get("/api/qa/conversations/{c}/turns/{t}", conversationId, turn))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 分数：字段名与库里存过的任何数值表达都不该出现。
        assertThat(body)
                .doesNotContain("score")
                .doesNotContain("Score")
                .doesNotContain("distance")
                .doesNotContain("relevance");
        // chunk 正文：来源只给 chunkId，正文另经知识库接口取。
        assertThat(body).doesNotContain(CHUNK_TEXT);
        // 通道名：origin_channel 只进处理详情，避免"命中两次更可信"的暗示。
        assertThat(body)
                .doesNotContain("INTENT_TARGETED")
                .doesNotContain("GLOBAL_VECTOR")
                .doesNotContain("KEYWORD");
        // Schema 原文与属性名：参数表用 title 的中文名，属性名不出去。
        assertThat(body)
                .doesNotContain("additionalProperties")
                .doesNotContain("$schema")
                .doesNotContain("orderNo")
                .doesNotContain("contactPhone");
        // 参数表确实用了 Schema 的 title。
        assertThat(body).contains("订单号").contains("ORD-20260806-1183");
    }

    @Test
    void tool17_描述与显示名来自白名单配置而不是模型输出() throws Exception {
        var turn = seedTurn();
        // 参数 JSON 里塞进模型可能编造的描述性字段。它们**不在 Schema 声明的参数里**，
        // 所以既不该进参数表，更不该冒充确认卡上那句说明。
        seedToolCall(turn, 0, "PENDING_CONFIRM", """
                {"orderNo":"ORD-1","reason":"质量问题",\
                "description":"我将帮你把这张单子直接通过审核","displayName":"一键退款"}""");

        String body = mvc.perform(get("/api/qa/conversations/{c}/turns/{t}", conversationId, turn))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 说明来自服务端白名单配置（架构 §21.2）。否则模型既决定调什么工具、又决定怎么向
        // 用户描述它，确认就成了走过场——用户读到的是模型自己写的推销词。
        assertThat(body).contains("在售后工单系统中新建一张退货工单。");
        assertThat(body).contains("提交退货工单");
        // 模型编造的那两句一个字都不能出现。
        assertThat(body).doesNotContain("我将帮你把这张单子直接通过审核");
        assertThat(body).doesNotContain("一键退款");
    }

    @Test
    void api03_同一轮的sources内chunkId不重复() throws Exception {
        var first = seedKb("契约去重甲", "退货政策甲");
        var second = seedKb("契约去重乙", "退货政策乙");
        var turn = seedTurn();
        seedRetrieval(turn, "RETRIEVED", false, true, null);
        seedSource(turn, 0, first.chunkId(), first.kbId(), first.docId(), "INTENT_TARGETED");
        seedSource(turn, 1, second.chunkId(), second.kbId(), second.docId(), "KEYWORD");

        String body = mvc.perform(get("/api/qa/conversations/{c}/turns/{t}", conversationId, turn))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var chunkIds = json.readTree(body).path("retrieval").path("sources").findValuesAsText("chunkId");
        assertThat(chunkIds).doesNotHaveDuplicates().hasSize(2);
    }

    @Test
    void api04_文档已删除时来源条目仍返回但不含正文() throws Exception {
        var kb = seedKb("契约已删", CHUNK_TEXT);
        jdbc.update("update source_document set deleted = true, delete_time = now() where id = ?", kb.docId());
        var turn = seedTurn();
        seedRetrieval(turn, "RETRIEVED", false, true, null);
        seedSource(turn, 0, kb.chunkId(), kb.kbId(), kb.docId(), "INTENT_TARGETED");

        mvc.perform(get("/api/qa/conversations/{c}/turns/{t}", conversationId, turn))
                .andExpect(status().isOk())
                // 条目仍在：界面显示"文档已删除"并禁用跳转，而不是让来源凭空少一条。
                .andExpect(jsonPath("$.retrieval.sources", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.retrieval.sources[0].chunkId").value((int) kb.chunkId()))
                .andExpect(jsonPath("$.retrieval.sources[0].documentDeleted").value(true))
                // 文档名照常返回——界面要说清是哪份文档被删了。
                .andExpect(jsonPath("$.retrieval.sources[0].documentName").exists());
    }

    @Test
    void partial与失败目标点名该库且failureCode是闭集码值() throws Exception {
        var kb = seedKb("契约部分失败", "退货政策");
        var turn = seedTurn();
        seedRetrieval(turn, "RETRIEVED", true, true, null);
        seedSource(turn, 0, kb.chunkId(), kb.kbId(), kb.docId(), "INTENT_TARGETED");
        var failedKb = seedKb("契约失败库", "海外仓运营");
        seedFailedTarget(turn, failedKb.kbId(), "RETRIEVAL_TIMEOUT");

        mvc.perform(get("/api/qa/conversations/{c}/turns/{t}", conversationId, turn))
                .andExpect(status().isOk())
                // partial 是服务端算好的显式字段，不能由 sources.length 推断。
                .andExpect(jsonPath("$.retrieval.partial").value(true))
                .andExpect(jsonPath("$.retrieval.failedTargets", org.hamcrest.Matchers.hasSize(1)))
                .andExpect(jsonPath("$.retrieval.failedTargets[0].knowledgeBaseId")
                        .value((int) failedKb.kbId()))
                .andExpect(jsonPath("$.retrieval.failedTargets[0].knowledgeBaseName").value("契约失败库"))
                .andExpect(jsonPath("$.retrieval.failedTargets[0].failureCode").value("RETRIEVAL_TIMEOUT"));
    }

    @Test
    void 未精排时degradation非空且不区分成因() throws Exception {
        var turn = seedTurn();
        seedRetrieval(turn, "NO_RESULT", false, false, "RERANK_UNAVAILABLE");

        mvc.perform(get("/api/qa/conversations/{c}/turns/{t}", conversationId, turn))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.retrieval.status").value("NO_RESULT"))
                .andExpect(jsonPath("$.retrieval.rerankApplied").value(false))
                .andExpect(jsonPath("$.retrieval.degradation").value("RERANK_UNAVAILABLE"));
    }

    @Test
    void 没有失败目标时重试返回409() throws Exception {
        var turn = seedTurn();
        seedRetrieval(turn, "RETRIEVED", false, true, null);

        mvc.perform(post("/api/qa/turns/{t}/retrieval/retry", turn)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clientRequestId\":\"%s\"}".formatted(UUID.randomUUID())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NOTHING_TO_RETRY"));
    }

    // ——— helpers ———

    private record Kb(long kbId, long docId, long chunkId) {}

    private Kb seedKb(String name, String content) {
        String alias = name + "-" + UUID.randomUUID();
        long kbId = jdbc.queryForObject(
                """
                insert into knowledge_base (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'test', 1024) returning id
                """,
                Long.class,
                name,
                alias);
        long docId = jdbc.queryForObject(
                """
                insert into source_document (kb_id, name, source_type, storage_object_id)
                values (?, '退货与退款操作手册 v3.docx', 'FILE', ?) returning id
                """,
                Long.class,
                kbId,
                UUID.randomUUID().toString());
        long chunkId = jdbc.queryForObject(
                """
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                values (?, ?, 1, 12, ?, ?, 1,
                        '0000000000000000000000000000000000000000000000000000000000000000')
                returning id
                """,
                Long.class,
                kbId,
                docId,
                content,
                content.length());
        return new Kb(kbId, docId, chunkId);
    }

    private UUID seedTurn() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, completed_at)
                values (?, ?, ?, ?, 'ACTIONS_READY', now())
                """,
                id,
                conversationId,
                UUID.randomUUID(),
                UUID.randomUUID());
        return id;
    }

    private void seedRetrieval(
            UUID turnId, String status, boolean partial, boolean rerankApplied, String degradation) {
        jdbc.update(
                """
                insert into qa_turn_retrieval
                    (turn_id, status, partial, rerank_applied, degradation,
                     channel_hits, after_dedup, after_rerank, completed_at)
                values (?, ?, ?, ?, ?, 3, 2, 1, now())
                """,
                turnId,
                status,
                partial,
                rerankApplied,
                degradation);
    }

    /**
     * 来源与目标都外键引用子问题和动作（V9 都是 not null），所以 seed 一条来源就要先有
     * 一条 3A 动作。这不是测试的负担，而是"3B 结果引用动作、不塞回意图快照表"那条纪律
     * 在数据库上的样子（架构 §15）。
     */
    private UUID seedAction(UUID turnId, Long kbId, int ordinal) {
        UUID subQuestionId = UUID.randomUUID();
        jdbc.update(
                "insert into qa_subquestion (id, turn_id, ordinal, text, source) values (?, ?, ?, ?, 'MODEL')",
                subQuestionId,
                turnId,
                ordinal,
                "退货需要哪些材料");
        UUID actionId = UUID.randomUUID();
        jdbc.update(
                """
                insert into qa_retrieval_action
                    (id, turn_id, subquestion_id, ordinal, action_type,
                     intent_node_id, knowledge_base_id, reason_code)
                values (?, ?, ?, 1, ?, ?, ?, 'TEST')
                """,
                actionId,
                turnId,
                subQuestionId,
                kbId == null ? "GLOBAL_FALLBACK" : "KB_TARGETED",
                kbId == null ? null : UUID.randomUUID(),
                kbId);
        subQuestionOf.put(actionId, subQuestionId);
        return actionId;
    }

    private void seedSource(UUID turnId, int ordinal, long chunkId, long kbId, long docId, String channel) {
        UUID actionId = seedAction(turnId, kbId, ordinal);
        jdbc.update(
                """
                insert into qa_retrieval_source
                    (id, turn_id, subquestion_id, action_id, ordinal, chunk_id, kb_id, doc_id, origin_channel)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                turnId,
                subQuestionOf.get(actionId),
                actionId,
                ordinal,
                chunkId,
                kbId,
                docId,
                channel);
    }

    private void seedFailedTarget(UUID turnId, long kbId, String failureCode) {
        UUID actionId = seedAction(turnId, kbId, 2);
        jdbc.update(
                """
                insert into qa_retrieval_target
                    (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
                values (?, ?, ?, ?, 'FAILED', ?, 12)
                """,
                UUID.randomUUID(),
                turnId,
                actionId,
                kbId,
                failureCode);
    }

    private void seedToolCall(UUID turnId, int ordinal, String status, String argumentsJson) {
        jdbc.update(
                """
                insert into qa_tool_call
                    (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json)
                values (?, ?, ?, 'submit-return-ticket', true, ?, ?)
                """,
                UUID.randomUUID(),
                turnId,
                ordinal,
                status,
                argumentsJson);
    }
}
