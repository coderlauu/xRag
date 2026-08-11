package com.app.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.app.ai.model.SystemMessage;
import com.app.ai.model.TokenUsage;
import com.app.qa.conversation.JdbcTurnStageSink;
import com.app.qa.conversation.TurnStageSink;
import com.app.qa.intent.IntentTreeStore;
import com.app.qa.model.IntentNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3A-E2E-04 与 3A-E2E-06：运营发布与用户提问的竞争，以及诊断与业务终态的相互独立。
 *
 * <p>两个不变量都属于"平时看不出来、出事时代价很大"那一类：
 *
 * <ul>
 *   <li>一个 turn 从改写到评分到落库必须自始至终只见**一个** revision。做不到的话，用户会得到一个
 *       按旧树改写、按新树评分的混合结果，而事后没有任何字段能解释它是怎么来的。
 *   <li>诊断是 best-effort。stage 写失败绝不能把一个已经算完的 turn 变成 FAILED，删除对话也绝不能
 *       连带删掉 {@code ai_*}——那是另一个所有者的审计数据（ADR 0005）。
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(QaResilienceE2ETests.ResilienceConfiguration.class)
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = "app.knowledge.ingestion.enabled=false")
class QaResilienceE2ETests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScriptedQaChat chat;

    @Autowired
    private ToggleableStageSink stages;

    @Autowired
    private IntentTreeStore trees;

    private final ObjectMapper mapper = new ObjectMapper();

    private UUID revisionId;
    private UUID groupId;
    private UUID nodeId;

    @BeforeEach
    void publishBaselineTree() {
        chat.reset();
        stages.failing = false;
        long knowledgeBaseId = jdbc.queryForObject("""
                insert into knowledge_base
                    (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'text-embedding-v3', 1024)
                returning id
                """, Long.class,
                "qa-resilience-" + UUID.randomUUID(),
                "qa-resilience-" + UUID.randomUUID());
        long revisionNo = jdbc.queryForObject(
                "select coalesce(max(revision_no), 0) + 1 from qa_intent_tree_revision", Long.class);
        revisionId = UUID.randomUUID();
        groupId = UUID.randomUUID();
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
                     name, description, examples, knowledge_base_id, enabled, deleted)
                values (?, ?, ?, 2, 0, 'KB', '退款政策', '退款到账与材料',
                        '["退款多久到账"]', ?, true, false)
                """, revisionId, nodeId, groupId, knowledgeBaseId);
        jdbc.update("""
                update qa_intent_tree_head
                   set current_revision_id = ?, current_revision_no = ?, updated_at = now()
                 where singleton_id = 1
                """, revisionId, revisionNo);
        chat.nodeId.set(nodeId);
        chat.reset();
    }

    @Test
    void e2e04_运营在本轮进行中发布新版本时该轮仍完整使用同一个revision() throws Exception {
        var published = new AtomicReference<UUID>();
        // 在"改写"这一步发布新版本：此时本轮已经取到快照，评分还没开始，正是最容易出混合结果的窗口。
        chat.beforeRewriteResponse = () -> published.set(publishAdditionalGroup());

        String first = submitQuestion("退款多久到账？");
        String turnId = mapper.readTree(first).at("/turn/turnId").asText();

        assertThat(published.get()).isNotNull().isNotEqualTo(revisionId);
        assertThat(mapper.readTree(first).at("/turn/intentRevisionId").asText())
                .isEqualTo(revisionId.toString());
        // 落库的每一条命中都必须挂在同一个 revision 上，而不是"响应里写旧的、库里写新的"。
        assertThat(jdbc.queryForList(
                        "select distinct intent_revision_id from qa_intent_match where turn_id = ?",
                        UUID.class, UUID.fromString(turnId)))
                .containsExactly(revisionId);
        assertThat(jdbc.queryForObject(
                        "select intent_revision_id from qa_turn where id = ?",
                        UUID.class, UUID.fromString(turnId)))
                .isEqualTo(revisionId);

        // 下一轮才切到新版本，且同样是完整的一个版本。
        chat.beforeRewriteResponse = () -> {};
        String second = submitQuestion("那退款材料呢？");
        assertThat(mapper.readTree(second).at("/turn/intentRevisionId").asText())
                .isEqualTo(published.get().toString());
    }

    @Test
    void e2e06_删除对话隐藏业务数据但不动ai诊断记录() throws Exception {
        String response = submitQuestion("退款多久到账？");
        var json = mapper.readTree(response);
        String conversationId = json.at("/conversation/conversationId").asText();
        UUID turnId = UUID.fromString(json.at("/turn/turnId").asText());

        UUID logicalCallId = jdbc.queryForObject("""
                select logical_call_id from qa_turn_stage
                 where turn_id = ? and logical_call_id is not null limit 1
                """, UUID.class, turnId);
        assertThat(logicalCallId).isNotNull();
        insertDiagnosticCall(logicalCallId);

        long version = jdbc.queryForObject(
                "select version from qa_conversation where id = ?",
                Long.class, UUID.fromString(conversationId));
        mvc.perform(delete("/api/qa/conversations/{id}", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":%d}".formatted(version)))
                .andExpect(status().isNoContent());

        mvc.perform(get("/api/qa/conversations/{id}", conversationId))
                .andExpect(status().isNotFound());
        // 业务数据只是被隐藏；诊断属于 com.app.ai，删除对话无权碰它。
        assertThat(jdbc.queryForObject(
                        "select count(*) from ai_logical_call where id = ?",
                        Integer.class, logicalCallId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_turn_stage where turn_id = ?", Integer.class, turnId))
                .isPositive();
    }

    @Test
    void e2e06_stage写入故障不改变本轮业务终态() throws Exception {
        stages.failing = true;

        String response = submitQuestion("退款多久到账？");
        UUID turnId = UUID.fromString(mapper.readTree(response).at("/turn/turnId").asText());

        assertThat(mapper.readTree(response).at("/turn/status").asText()).isEqualTo("ACTIONS_READY");
        assertThat(jdbc.queryForObject(
                        "select status from qa_turn where id = ?", String.class, turnId))
                .isEqualTo("ACTIONS_READY");
        // 诊断没写进去是可以接受的结果；把已经算完的一轮判成失败不是。
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_turn_stage where turn_id = ?", Integer.class, turnId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_retrieval_action where turn_id = ?",
                        Integer.class, turnId))
                .isEqualTo(1);
    }

    // ---------- 辅助 ----------

    private String submitQuestion(String text) throws Exception {
        return mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"%s",
                                  "conversationId":null,
                                  "message":{"type":"QUESTION","text":"%s"}
                                }
                                """.formatted(UUID.randomUUID(), text)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.turn.turnId").exists())
                .andReturn().getResponse().getContentAsString();
    }

    /** 走真实的写命令发布下一个 revision，包括原子替换缓存——不是直接改表制造假竞争。 */
    private UUID publishAdditionalGroup() {
        var committed = trees.create(revisionId, new IntentTreeStore.CreateNode(
                IntentTreeStore.VIRTUAL_ROOT_ID,
                IntentNodeType.GROUP,
                "物流与配送-" + UUID.randomUUID(),
                "运营说明",
                List.of(),
                null,
                null,
                null,
                1));
        return committed.snapshot().revisionId();
    }

    private void insertDiagnosticCall(UUID logicalCallId) {
        jdbc.update("""
                insert into ai_logical_call
                    (id, request_id, capability, stream, policy_version, config_fingerprint,
                     outcome, actual_provider_id, actual_deployment_id, actual_model_identity,
                     termination_reason, attempt_count, retry_count, failover_count,
                     mixed_execution, contributing_deployment_count, committed,
                     total_duration_ms, started_at, completed_at)
                values (?, 'req_****0001', 'CHAT', false, 'test-v1', ?, 'SUCCEEDED',
                        'fake-provider', 'fake-chat', 'fake-chat-v1',
                        'COMPLETED', 1, 0, 0, false, 1, true, 12, now(), now())
                """, logicalCallId, "a".repeat(64));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ResilienceConfiguration {

        @Bean
        @Primary
        ScriptedQaChat scriptedQaChat() {
            return new ScriptedQaChat();
        }

        @Bean
        @Primary
        ToggleableStageSink toggleableStageSink(JdbcTemplate jdbc) {
            return new ToggleableStageSink(new JdbcTurnStageSink(jdbc));
        }
    }

    /**
     * 可切换成"总是抛异常"的 stage sink。
     *
     * <p>刻意抛 {@link IllegalStateException} 而不是 {@code DataAccessException}：后者已经被
     * {@link JdbcTurnStageSink} 自己吞掉了，用它测不出编排层有没有守住 best-effort 边界。
     */
    static final class ToggleableStageSink implements TurnStageSink {

        private final TurnStageSink delegate;
        volatile boolean failing;

        ToggleableStageSink(TurnStageSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public void record(StageEvent event) {
            if (failing) {
                throw new IllegalStateException("simulated stage sink failure");
            }
            delegate.record(event);
        }
    }

    static final class ScriptedQaChat implements ChatClient {

        private final AtomicReference<UUID> nodeId = new AtomicReference<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<String> rewriteResponse = new AtomicReference<>();
        private final AtomicReference<String> scoringResponse = new AtomicReference<>();
        volatile Runnable beforeRewriteResponse = () -> {};

        @Override
        public ChatResult complete(ChatCommand command) {
            calls.incrementAndGet();
            String system = ((SystemMessage) command.messages().get(0)).text();
            String response;
            if (system.contains("长期记忆")) {
                response = "历史摘要";
            } else if (system.contains("改写当前问题")) {
                beforeRewriteResponse.run();
                response = rewriteResponse.get();
            } else if (system.contains("可执行意图叶子")) {
                response = scoringResponse.get();
            } else {
                throw new IllegalStateException("unexpected QA chat prompt");
            }
            return new ChatResult(
                    new AssistantMessage(response, null, List.of()),
                    ChatFinishReason.STOP,
                    new TokenUsage(1, 1, 2),
                    new ExecutionInfo(
                            UUID.randomUUID(),
                            new RequestId("qa-resilience-" + calls.get()),
                            new ExecutionTarget(
                                    new ProviderId("fake-provider"),
                                    new DeploymentId("fake-chat"),
                                    new ModelIdentity("fake-chat-v1")),
                            1,
                            0,
                            0));
        }

        void reset() {
            calls.set(0);
            beforeRewriteResponse = () -> {};
            rewriteResponse.set("{\"rewrittenQuestion\":\"退款通常多久到账？\","
                    + "\"subQuestions\":[\"退款多久到账？\"]}");
            scoringResponse.set("[{\"id\":\"" + nodeId.get() + "\",\"score\":0.91}]");
        }
    }
}
