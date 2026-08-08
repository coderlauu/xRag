package com.app.qa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.app.ai.model.SystemMessage;
import com.app.ai.model.TokenUsage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3A-API-11~12：问答链路的统一标记串隐私审计。
 *
 * <p>做法与 Phase 2 的 {@code DiagnosticPrivacyAuditIntegrationTests} 相同——把可识别标记串塞进
 * 每一类正文（用户问题、摘要、模型评分理由、意图运营字段、密钥、Provider 原始错误体），跑完一条
 * 完整链路，再去 {@code qa_turn_stage}、全部 {@code /api/qa} 响应和日志里搜。
 *
 * <p>关键在于**每个标记串各有自己的允许范围**：用户问题理应出现在 `/messages` 和 Inspector 的
 * 规范化问题里，但绝不该出现在无正文的 stage 表或日志里；摘要正文哪儿都不该出现在 API 上；
 * 意图 description 只属于意图树 API，不属于本轮响应。写成"全都不许出现"会得到一个永远绿的假审计。
 *
 * <p>用扫描而不是逐字段断言，是因为泄漏几乎总是发生在新增字段时：有人给 Inspector DTO 加一个
 * "顺手带上模型理由"的字段，逐字段断言不会失败，扫描会。报告只给位置与类别名，不复制原文。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(QaPrivacyAuditIntegrationTests.MarkedChatConfiguration.class)
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class QaPrivacyAuditIntegrationTests {

    /** 每个标记串对应一类正文。名字要能一眼看出泄漏源。 */
    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("用户问题正文", "XRAGQA_QUESTION_4b7e");
        MARKERS.put("摘要正文", "XRAGQA_SUMMARY_2c9a");
        MARKERS.put("模型评分理由", "XRAGQA_MODELREASON_8d31");
        MARKERS.put("意图运营字段", "XRAGQA_NODEDESC_5f60");
        MARKERS.put("Prompt 追加片段", "XRAGQA_SNIPPET_1e4d");
        MARKERS.put("密钥值", "XRAGQA_SECRET_7a0c");
        MARKERS.put("Provider 原始错误体", "XRAGQA_RAWERROR_3b82");
    }

    /** stage 表没有任何正文列，所以七个标记串一个都不许出现（architecture §12）。 */
    private static final List<String> FORBIDDEN_IN_STAGE = List.copyOf(MARKERS.keySet());

    /** 对话/本轮响应只允许出现用户可见正文；摘要、评分理由、运营字段都不属于 Inspector（api.md §6）。 */
    private static final List<String> FORBIDDEN_IN_CONVERSATION_API = List.of(
            "摘要正文", "模型评分理由", "意图运营字段", "Prompt 追加片段", "密钥值", "Provider 原始错误体");

    /** 意图树 API 是运营字段的正当归属地，但它同样不该带出摘要、评分理由或任何密钥。 */
    private static final List<String> FORBIDDEN_IN_INTENT_TREE_API = List.of(
            "用户问题正文", "摘要正文", "模型评分理由", "密钥值", "Provider 原始错误体");

    /** 日志只允许 ID、枚举和脱敏 requestId，任何正文都不许（3A-API-12）。 */
    private static final List<String> FORBIDDEN_IN_LOGS = List.copyOf(MARKERS.keySet());

    /** 内部字段名一旦出现在 `/api/qa` 响应里，说明某个 DTO 把调度层结构整体嵌进去了。 */
    private static final List<String> FORBIDDEN_FIELD_NAMES = List.of(
            "logicalcallid", "executioninfo", "providerrequestid", "attemptno",
            "temperature", "topp", "apikey", "api_key", "authorization");

    /** 对话/本轮响应里连 `prompt` 这个词都不该出现；意图树例外，`promptSnippet` 是批准的可编辑字段。 */
    private static final List<String> CONVERSATION_FORBIDDEN_FIELD_NAMES =
            java.util.stream.Stream.concat(FORBIDDEN_FIELD_NAMES.stream(), java.util.stream.Stream.of("prompt"))
                    .toList();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MarkedQaChat chat;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private ch.qos.logback.classic.Logger rootLogger;

    private UUID revisionId;
    private UUID nodeId;

    @BeforeEach
    void publishMarkedTreeAndCaptureLogs() {
        chat.reset();
        long knowledgeBaseId = jdbc.queryForObject("""
                insert into knowledge_base
                    (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'text-embedding-v3', 1024)
                returning id
                """, Long.class,
                "qa-privacy-" + UUID.randomUUID(),
                "qa-privacy-" + UUID.randomUUID());
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
                values (?, ?, null, 1, 0, 'GROUP', '售后服务', ?, '[]', true, false)
                """, revisionId, groupId, MARKERS.get("意图运营字段"));
        jdbc.update("""
                insert into qa_intent_node_snapshot
                    (revision_id, node_id, parent_id, depth, sort_order, node_type,
                     name, description, examples, knowledge_base_id, prompt_snippet, enabled, deleted)
                values (?, ?, ?, 2, 0, 'KB', '退款政策', ?, ?::jsonb, ?, ?, true, false)
                """, revisionId, nodeId, groupId,
                MARKERS.get("意图运营字段"),
                "[\"" + MARKERS.get("意图运营字段") + "\"]",
                knowledgeBaseId,
                MARKERS.get("Prompt 追加片段"));
        jdbc.update("""
                update qa_intent_tree_head
                   set current_revision_id = ?, current_revision_no = ?, updated_at = now()
                 where singleton_id = 1
                """, revisionId, revisionNo);
        chat.nodeId = nodeId;

        logs.start();
        rootLogger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(
                org.slf4j.Logger.ROOT_LOGGER_NAME);
        rootLogger.addAppender(logs);
        rootLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void detachLogs() {
        if (rootLogger != null) {
            rootLogger.detachAppender(logs);
        }
        logs.stop();
    }

    @Test
    void api11到api12_跑完一整轮后标记串只出现在允许的位置() throws Exception {
        String turnResponse = runOneTurn();

        List<String> leaks = new ArrayList<>();
        leaks.addAll(scanStageTable());
        leaks.addAll(scanText("POST /turns 响应", turnResponse,
                FORBIDDEN_IN_CONVERSATION_API, CONVERSATION_FORBIDDEN_FIELD_NAMES));
        leaks.addAll(scanConversationApis(turnResponse));
        leaks.addAll(scanIntentTreeApis());
        leaks.addAll(scanLogs());

        assertThat(leaks)
                .withFailMessage("问答链路出现隐私泄漏（只列出位置与类别，不复制原文）：%n%s",
                        String.join("\n", leaks))
                .isEmpty();
    }

    @Test
    void api11_用户可见正文仍然留在它该在的地方() throws Exception {
        String turnResponse = runOneTurn();
        String conversationId = mapper.readTree(turnResponse).at("/conversation/conversationId").asText();

        // 审计不能靠"什么都查不到"通过：正文必须真的被写进业务表并能读回来。
        String messages = mvc.perform(get("/api/qa/conversations/{id}/messages", conversationId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(messages).contains(MARKERS.get("用户问题正文"));
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_conversation_summary where deleted = false", Integer.class))
                .isZero();

        // 意图运营字段属于意图树 API，不属于本轮响应。
        String tree = mvc.perform(get("/api/qa/intent-tree"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(tree).contains(MARKERS.get("意图运营字段"));
        assertThat(turnResponse).doesNotContain(MARKERS.get("意图运营字段"));
    }

    @Test
    void 审计自身可信_人为种入禁区的标记串必须被扫出来且报告不复制原文() throws Exception {
        runOneTurn();
        assertThat(scanStageTable()).isEmpty();

        // 往两个不同的禁区各种一个标记串：stage 的稳定原因码，和一条日志。
        jdbc.update("update qa_turn_stage set reason_code = ? where stage = 'REWRITE'",
                MARKERS.get("摘要正文"));
        LoggerFactory.getLogger(QaPrivacyAuditIntegrationTests.class)
                .warn("模拟一条把用户问题写进日志的实现：{}", MARKERS.get("用户问题正文"));

        List<String> stageLeaks = scanStageTable();
        List<String> logLeaks = scanLogs();

        assertThat(stageLeaks).anySatisfy(leak ->
                assertThat(leak).contains("qa_turn_stage.reason_code").contains("摘要正文"));
        assertThat(logLeaks).anySatisfy(leak -> assertThat(leak).contains("用户问题正文"));
        assertThat(stageLeaks).allSatisfy(leak ->
                assertThat(leak).doesNotContain(MARKERS.get("摘要正文")));
        assertThat(logLeaks).allSatisfy(leak ->
                assertThat(leak).doesNotContain(MARKERS.get("用户问题正文")));
    }

    // ---------- 链路 ----------

    private String runOneTurn() throws Exception {
        return mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"%s",
                                  "conversationId":null,
                                  "message":{"type":"QUESTION","text":"%s"}
                                }
                                """.formatted(UUID.randomUUID(), MARKERS.get("用户问题正文"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.status").value("ACTIONS_READY"))
                .andReturn().getResponse().getContentAsString();
    }

    // ---------- 扫描 ----------

    /** 扫 `qa_turn_stage` 的全部文本列。列表从 information_schema 取，新增列自动纳入。 */
    private List<String> scanStageTable() {
        List<String> leaks = new ArrayList<>();
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select column_name
                  from information_schema.columns
                 where table_name = 'qa_turn_stage'
                   and data_type in ('character varying', 'text', 'character')
                 order by column_name
                """);
        for (Map<String, Object> column : columns) {
            String name = (String) column.get("column_name");
            for (String category : FORBIDDEN_IN_STAGE) {
                Long hits = jdbc.queryForObject(
                        "select count(*) from qa_turn_stage where %s like ?".formatted(name),
                        Long.class,
                        "%" + MARKERS.get(category) + "%");
                if (hits != null && hits > 0) {
                    leaks.add("表 qa_turn_stage.%s 命中「%s」%d 行".formatted(name, category, hits));
                }
            }
        }
        return leaks;
    }

    private List<String> scanConversationApis(String turnResponse) throws Exception {
        var json = mapper.readTree(turnResponse);
        String conversationId = json.at("/conversation/conversationId").asText();
        String turnId = json.at("/turn/turnId").asText();
        List<String> leaks = new ArrayList<>();
        leaks.addAll(scanEndpoint("GET /conversations", "/api/qa/conversations"));
        leaks.addAll(scanEndpoint(
                "GET /conversations/{id}", "/api/qa/conversations/" + conversationId));
        leaks.addAll(scanEndpoint(
                "GET /messages", "/api/qa/conversations/" + conversationId + "/messages"));
        leaks.addAll(scanEndpoint(
                "GET /turns/{id}",
                "/api/qa/conversations/" + conversationId + "/turns/" + turnId));
        return leaks;
    }

    private List<String> scanIntentTreeApis() throws Exception {
        List<String> leaks = new ArrayList<>();
        String tree = mvc.perform(get("/api/qa/intent-tree"))
                .andReturn().getResponse().getContentAsString();
        leaks.addAll(scanText(
                "GET /intent-tree", tree, FORBIDDEN_IN_INTENT_TREE_API, FORBIDDEN_FIELD_NAMES));
        String bindable = mvc.perform(get("/api/qa/intent-tree/bindable-knowledge-bases"))
                .andReturn().getResponse().getContentAsString();
        leaks.addAll(scanText(
                "GET /bindable-knowledge-bases", bindable, FORBIDDEN_IN_INTENT_TREE_API,
                FORBIDDEN_FIELD_NAMES));
        return leaks;
    }

    private List<String> scanEndpoint(String where, String path) throws Exception {
        String body = mvc.perform(get(path)).andReturn().getResponse().getContentAsString();
        return scanText(where, body, FORBIDDEN_IN_CONVERSATION_API, CONVERSATION_FORBIDDEN_FIELD_NAMES);
    }

    private List<String> scanLogs() {
        List<String> leaks = new ArrayList<>();
        for (ILoggingEvent event : List.copyOf(logs.list)) {
            String text = (event.getFormattedMessage() + " " + event.getLoggerName())
                    .toLowerCase(Locale.ROOT);
            for (String category : FORBIDDEN_IN_LOGS) {
                if (text.contains(MARKERS.get(category).toLowerCase(Locale.ROOT))) {
                    leaks.add("日志 %s 命中「%s」".formatted(event.getLoggerName(), category));
                }
            }
        }
        return leaks;
    }

    private static List<String> scanText(
            String where, String text, List<String> categories, List<String> forbiddenNames) {
        List<String> leaks = new ArrayList<>();
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

    @TestConfiguration(proxyBeanMethods = false)
    static class MarkedChatConfiguration {

        @Bean
        @Primary
        MarkedQaChat markedQaChat() {
            return new MarkedQaChat();
        }
    }

    /**
     * 假 Chat 在每个响应里都混进不该被留存的内容：摘要正文、评分 `reason`、密钥值和
     * Provider 原始错误体。它们只要被任何一层"顺手保存下来"，扫描就会命中。
     */
    static final class MarkedQaChat implements ChatClient {

        private volatile UUID nodeId;

        @Override
        public ChatResult complete(ChatCommand command) {
            String system = ((SystemMessage) command.messages().get(0)).text();
            String response;
            if (system.contains("长期记忆")) {
                response = MARKERS.get("摘要正文");
            } else if (system.contains("改写当前问题")) {
                response = "{\"rewrittenQuestion\":\"%s\",\"subQuestions\":[\"%s\"]}"
                        .formatted(MARKERS.get("用户问题正文"), MARKERS.get("用户问题正文"));
            } else if (system.contains("可执行意图叶子")) {
                response = "[{\"id\":\"%s\",\"score\":0.91,\"reason\":\"%s\"}]"
                        .formatted(nodeId, MARKERS.get("模型评分理由"));
            } else {
                throw new IllegalStateException("unexpected QA chat prompt");
            }
            return new ChatResult(
                    new AssistantMessage(response, null, List.of()),
                    ChatFinishReason.STOP,
                    new TokenUsage(1, 1, 2),
                    new ExecutionInfo(
                            UUID.randomUUID(),
                            // RequestId 是调度关联值；即使上游给了带密钥的脏值也不该流出去。
                            new RequestId(MARKERS.get("密钥值")),
                            new ExecutionTarget(
                                    new ProviderId(MARKERS.get("Provider 原始错误体")),
                                    new DeploymentId("fake-chat"),
                                    new ModelIdentity("fake-chat-v1")),
                            1,
                            0,
                            0));
        }

        void reset() {
            nodeId = null;
        }
    }
}
