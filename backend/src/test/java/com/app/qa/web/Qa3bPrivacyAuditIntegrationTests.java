package com.app.qa.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.knowledge.FakeEmbeddingConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 3B-PRIV：检索与工具链路的标记串隐私审计（工单 14）。
 *
 * <p>手法与 {@link QaPrivacyAuditIntegrationTests} 和 Phase 2 的
 * {@code DiagnosticPrivacyAuditIntegrationTests} 相同：把可识别标记串塞进每一类正文，跑完链路，
 * 再去 3B 的五张表、3B 端点响应和 {@code ai_*} 诊断表里搜。
 *
 * <p><b>每个标记串各有自己的允许范围</b>，这是本类唯一重要的设计。写成"全都不许出现"会得到
 * 一个永远绿的假审计——工具参数值本来就该出现在确认卡上，chunk 正文本来就该能在分块页看到。
 * 真正要守的是<b>它们不该跨到哪里去</b>：
 *
 * <ul>
 *   <li><b>chunk 正文</b>：允许留在 {@code document_chunk}。**不该进 3B 的任何一张表**——来源表
 *       只存 chunkId（数据模型 §14.2），正文按需现取，这样它才能跟随知识库的逻辑删除。
 *       更要紧的是不该进 {@code ai_*}：精排会把正文塞进 rerank 请求，诊断一旦记了请求体就泄漏。</li>
 *   <li><b>工具参数值</b>：允许留在 {@code qa_tool_call.arguments_json} 与 API 的 {@code parameters}
 *       ——用户确认前唯一能核对的就是它。不该进 {@code ai_*}。</li>
 *   <li><b>工具返回</b>：允许留在 {@code qa_tool_call.result_text} 与 API 的 {@code resultText}。
 *       不该进 {@code ai_*}。</li>
 *   <li><b>Schema 原文</b>：只属于白名单配置文件。任何表、任何响应都不该有（契约 §12）。</li>
 *   <li><b>异常 message</b>：哪儿都不该有。失败原因是归一化闭集（PRD §12.6）。</li>
 * </ul>
 *
 * <p>用扫描而不是逐字段断言：泄漏几乎总是发生在新增字段时，逐字段断言不会失败，扫描会。
 * 报告只给位置与类别名，不复制原文。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({FakeEmbeddingConfig.class, Qa3bPrivacyAuditIntegrationTests.StubTool.class})
/*
 * **必须配白名单**，否则本类是假绿的：`TurnResultViewAssembler.parameters()` 遍历的是白名单
 * 声明的参数，没有配置时 definition 为 null、参数表为空——于是"工具参数值不该跨到 ai_* 表"
 * 这类断言会因为"参数压根没出现在任何地方"而自然成立。
 *
 * 这正是工单 11 记录过的失效模式（那次是隐私断言假绿），本次由 priv02 的**正向断言**
 * （参数值应该出现在响应里）把它抓了出来。隐私测试必须同时有正向与反向断言，只有反向的
 * 那一半永远绿。
 */
@TestPropertySource(
        properties = {
            "app.knowledge.ingestion.enabled=false",
            "app.qa.tools.whitelist[0].name=submit-return-ticket",
            "app.qa.tools.whitelist[0].display-name=提交退货工单",
            "app.qa.tools.whitelist[0].description=在售后工单系统中新建一张退货工单。",
            "app.qa.tools.whitelist[0].side-effect=true",
            "app.qa.tools.whitelist[0].input-schema=classpath:tools/qa/3b/submit-return-ticket.schema.json",
        })
class Qa3bPrivacyAuditIntegrationTests {

    /**
     * 白名单里的工具必须有对应的 {@code ToolExecutor}，否则启动直接失败（工单 09 的校验：
     * "一个不能执行的白名单工具只会在用户已经确认之后才失败"）。本类不执行工具、只 seed 落库行，
     * 所以这个假实现存在的唯一目的就是满足那条校验。
     */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class StubTool {

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
                    throw new UnsupportedOperationException("本类不执行工具，只审计落库结果");
                }
            };
        }
    }

    /** 每个标记串对应一类正文。名字要能一眼看出泄漏源。 */
    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("chunk 正文", "XRAG3B_CHUNKTEXT_9f2a");
        MARKERS.put("工具参数值", "XRAG3B_TOOLARG_4c71");
        MARKERS.put("工具返回正文", "XRAG3B_TOOLRESULT_8e03");
        MARKERS.put("Schema 原文", "XRAG3B_SCHEMA_2b55");
        MARKERS.put("检索异常 message", "XRAG3B_RETRIEVALERR_6d18");
        MARKERS.put("工具异常 message", "XRAG3B_TOOLERR_1a94");
    }

    /**
     * 3B 的五张表一个标记串都不许出现。
     *
     * <p>工具参数值与返回是<b>例外中的例外</b>：它们该在 {@code qa_tool_call} 里，所以那张表
     * 单独放行这两类，见 {@link #ALLOWED_IN_TOOL_CALL}。
     */
    private static final List<String> TABLES = List.of(
            "document_chunk_keyword",
            "qa_turn_retrieval",
            "qa_retrieval_source",
            "qa_retrieval_target",
            "qa_tool_call");

    /** {@code qa_tool_call} 是工具参数与返回的正当归属地，其余四类仍然不许。 */
    private static final List<String> ALLOWED_IN_TOOL_CALL = List.of("工具参数值", "工具返回正文");

    /**
     * {@code ai_*} 诊断表一个都不许——这是 Acceptance 里点名的那条。
     *
     * <p>它守的是一条很容易被无声打破的边界：精排请求带着 chunk 正文、工具调用带着参数与返回，
     * 只要哪天有人为了排查方便把请求体记进诊断，这几类正文就整体进了另一个保留期更长、
     * 访问控制更松的地方。
     */
    private static final List<String> FORBIDDEN_IN_DIAGNOSTICS = List.copyOf(MARKERS.keySet());

    /** 内部结构一旦出现在 3B 响应里，说明某个 DTO 把领域类型整体嵌进去了。 */
    private static final List<String> FORBIDDEN_FIELD_NAMES = List.of(
            "argumentsjson", "inputschema", "originchannel", "logicalcallid",
            "rrf", "score", "relevance", "apikey", "authorization");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID conversationId;
    private UUID turnId;
    private UUID subQuestionId;
    private UUID actionId;
    private long kbId;

    @BeforeEach
    void setUp() {
        conversationId = UUID.randomUUID();
        turnId = UUID.randomUUID();
        jdbc.update(
                "insert into qa_conversation (id, title, last_active_at) values (?, '3B 隐私审计', now())",
                conversationId);
        jdbc.update(
                """
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, completed_at)
                values (?, ?, ?, ?, 'ACTIONS_READY', now())
                """,
                turnId,
                conversationId,
                UUID.randomUUID(),
                UUID.randomUUID());
        subQuestionId = UUID.randomUUID();
        jdbc.update(
                "insert into qa_subquestion (id, turn_id, ordinal, text, source) values (?, ?, 0, ?, 'MODEL')",
                subQuestionId,
                turnId,
                "退货需要哪些材料");
        actionId = UUID.randomUUID();
        jdbc.update(
                """
                insert into qa_retrieval_action
                    (id, turn_id, subquestion_id, ordinal, action_type,
                     intent_node_id, knowledge_base_id, reason_code)
                values (?, ?, ?, 1, 'GLOBAL_FALLBACK', null, null, 'TEST')
                """,
                actionId,
                turnId,
                subQuestionId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from qa_tool_call where turn_id = ?", turnId);
        jdbc.update("delete from qa_retrieval_source where turn_id = ?", turnId);
        jdbc.update("delete from qa_retrieval_target where turn_id = ?", turnId);
        jdbc.update("delete from qa_turn_retrieval where turn_id = ?", turnId);
        jdbc.update("delete from qa_retrieval_action where turn_id = ?", turnId);
        jdbc.update("delete from qa_subquestion where turn_id = ?", turnId);
        jdbc.update("delete from qa_turn where id = ?", turnId);
        jdbc.update("delete from qa_conversation where id = ?", conversationId);
        if (kbId > 0) {
            jdbc.update("delete from document_chunk_keyword where kb_id = ?", kbId);
            jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
            jdbc.update("delete from document_chunk where kb_id = ?", kbId);
            jdbc.update("delete from source_document where kb_id = ?", kbId);
            jdbc.update("delete from knowledge_base where id = ?", kbId);
        }
    }

    @Test
    void priv01_三类正文不跨进3B五张表与诊断表() throws Exception {
        seedRetrievalWithMarkedChunk();
        seedToolCallWithMarkedPayload();

        var leaks = new ArrayList<String>();
        leaks.addAll(scanBusinessTables());
        leaks.addAll(scanDiagnosticTables());

        assertThat(leaks)
                .as("标记串跨到了不该去的地方；每条给出表.列与类别名")
                .isEmpty();
    }

    @Test
    void priv02_3B响应不带正文分数与内部结构() throws Exception {
        seedRetrievalWithMarkedChunk();
        seedToolCallWithMarkedPayload();

        String response = mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/qa/conversations/{conversationId}/turns/{turnId}",
                                conversationId,
                                turnId))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // 工具参数与返回**应该**在响应里——它们是用户确认与查看结果的依据。
        assertThat(response).contains(MARKERS.get("工具参数值"));
        assertThat(response).contains(MARKERS.get("工具返回正文"));

        var forbidden = List.of("chunk 正文", "Schema 原文", "检索异常 message", "工具异常 message");
        assertThat(scanText("turn 响应", response, forbidden, FORBIDDEN_FIELD_NAMES)).isEmpty();
    }

    /**
     * 反向断言：审计本身得能抓到东西。
     *
     * <p>没有这条，上面两条随时可能因为"标记串压根没被写进去"而假绿——那正是隐私测试最常见的
     * 失效模式，也是本轮在工单 11 已经踩过一次的（白名单没配 → 参数表为空 → 断言自然成立）。
     */
    @Test
    void priv03_扫描器能抓到真实泄漏否则前两条是假绿() {
        seedRetrievalWithMarkedChunk();
        // 故意把异常 message 塞进 failure_code，模拟 PRD §12.6 要防的那种泄漏。
        // 选这一列是因为它无取值 check，而它的建表注释正写着"是归一化闭集，不是异常 message"
        // ——数据库拦不住的地方，正是审计要盯的地方。
        jdbc.update(
                """
                insert into qa_retrieval_target
                    (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
                values (?, ?, ?, null, 'FAILED', ?, 5)
                """,
                UUID.randomUUID(),
                turnId,
                actionId,
                MARKERS.get("检索异常 message"));

        assertThat(scanBusinessTables())
                .as("扫描器必须报出这条人为泄漏，否则 priv01 的绿色没有意义")
                .isNotEmpty();
    }

    // ——— seeds ———

    /** 造一条带标记串正文的分块，并按真实形态落一次检索结果。 */
    private void seedRetrievalWithMarkedChunk() {
        String alias = "priv3b-" + UUID.randomUUID();
        kbId = jdbc.queryForObject(
                """
                insert into knowledge_base (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'test', 1024) returning id
                """,
                Long.class,
                alias,
                alias);
        long docId = jdbc.queryForObject(
                """
                insert into source_document (kb_id, name, source_type, storage_object_id)
                values (?, 'priv.txt', 'FILE', ?) returning id
                """,
                Long.class,
                kbId,
                UUID.randomUUID().toString());
        String content = "退货政策说明 " + MARKERS.get("chunk 正文");
        long chunkId = jdbc.queryForObject(
                """
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                values (?, ?, 1, 0, ?, ?, 1,
                        '0000000000000000000000000000000000000000000000000000000000000000')
                returning id
                """,
                Long.class,
                kbId,
                docId,
                content,
                content.length());

        jdbc.update(
                """
                insert into qa_turn_retrieval
                    (turn_id, status, partial, rerank_applied, degradation,
                     channel_hits, after_dedup, after_rerank, completed_at)
                values (?, 'RETRIEVED', false, true, null, 1, 1, 1, now())
                """,
                turnId);
        jdbc.update(
                """
                insert into qa_retrieval_source
                    (id, turn_id, subquestion_id, action_id, ordinal, chunk_id, kb_id, doc_id, origin_channel)
                values (?, ?, ?, ?, 0, ?, ?, ?, 'INTENT_TARGETED')
                """,
                UUID.randomUUID(),
                turnId,
                subQuestionId,
                actionId,
                chunkId,
                kbId,
                docId);
    }

    /** 造一条带标记串参数与返回的工具调用。 */
    private void seedToolCallWithMarkedPayload() {
        jdbc.update(
                """
                insert into qa_tool_call
                    (id, turn_id, ordinal, tool_name, side_effect, status,
                     arguments_json, result_text, latency_ms, confirmed_at)
                values (?, ?, 0, 'submit-return-ticket', true, 'SUCCEEDED', ?, ?, 12, now())
                """,
                UUID.randomUUID(),
                turnId,
                "{\"orderNo\":\"" + MARKERS.get("工具参数值") + "\"}",
                "工单已创建 " + MARKERS.get("工具返回正文"));
    }

    // ——— scans ———

    /** 扫 3B 五张表的全部文本列。列名从 information_schema 取，新增列自动纳入。 */
    private List<String> scanBusinessTables() {
        var leaks = new ArrayList<String>();
        for (String table : TABLES) {
            List<String> allowed = "qa_tool_call".equals(table) ? ALLOWED_IN_TOOL_CALL : List.of();
            leaks.addAll(scanTable(table, allowed));
        }
        return leaks;
    }

    private List<String> scanDiagnosticTables() {
        var leaks = new ArrayList<String>();
        for (String table : List.of(
                "ai_logical_call", "ai_attempt", "ai_route_candidate", "ai_route_event")) {
            leaks.addAll(scanTable(table, List.of()));
        }
        return leaks;
    }

    private List<String> scanTable(String table, List<String> allowedCategories) {
        var leaks = new ArrayList<String>();
        List<Map<String, Object>> columns = jdbc.queryForList(
                """
                select column_name
                  from information_schema.columns
                 where table_name = ?
                   and data_type in ('character varying', 'text', 'character')
                 order by column_name
                """,
                table);
        for (Map<String, Object> column : columns) {
            String name = (String) column.get("column_name");
            for (Map.Entry<String, String> marker : MARKERS.entrySet()) {
                if (allowedCategories.contains(marker.getKey())) {
                    continue;
                }
                Long hits = jdbc.queryForObject(
                        "select count(*) from %s where %s like ?".formatted(table, name),
                        Long.class,
                        "%" + marker.getValue() + "%");
                if (hits != null && hits > 0) {
                    leaks.add("表 %s.%s 命中「%s」%d 行".formatted(table, name, marker.getKey(), hits));
                }
            }
        }
        return leaks;
    }

    private static List<String> scanText(
            String where, String text, List<String> categories, List<String> forbiddenNames) {
        var leaks = new ArrayList<String>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String category : categories) {
            if (lower.contains(MARKERS.get(category).toLowerCase(Locale.ROOT))) {
                leaks.add("%s 命中「%s」".formatted(where, category));
            }
        }
        for (String forbidden : forbiddenNames) {
            if (lower.contains(forbidden)) {
                leaks.add("%s 出现禁止的字段名片段：%s".formatted(where, forbidden));
            }
        }
        return leaks;
    }
}
