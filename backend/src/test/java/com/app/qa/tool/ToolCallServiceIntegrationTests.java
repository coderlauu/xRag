package com.app.qa.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.ai.model.ToolCall;
import com.app.qa.config.QaToolProperties;
import com.app.web.ApiException;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * 3B-TOOL-09~18 的持久化与裁决路径。
 *
 * <p>本组最关键的是一条**否定命题**：确认之前工具一次都没被调用过。用可观测假工具的调用计数
 * 断言，而不是看日志或事后推断。
 */
@SpringBootTest
@Import(ToolCallServiceIntegrationTests.Tools.class)
@TestPropertySource(
        properties = {
            "app.knowledge.ingestion.enabled=false",
            "app.qa.tools.whitelist[0].name=submit-return-ticket",
            "app.qa.tools.whitelist[0].display-name=提交退货工单",
            "app.qa.tools.whitelist[0].description=在售后工单系统中新建一张退货工单。",
            "app.qa.tools.whitelist[0].side-effect=true",
            "app.qa.tools.whitelist[0].input-schema=classpath:tools/qa/3b/submit-return-ticket.schema.json",
            "app.qa.tools.whitelist[1].name=lookup-order",
            "app.qa.tools.whitelist[1].display-name=查询订单",
            "app.qa.tools.whitelist[1].description=按订单号查询当前状态。",
            "app.qa.tools.whitelist[1].side-effect=false",
            "app.qa.tools.whitelist[1].input-schema=classpath:tools/qa/3b/submit-return-ticket.schema.json",
        })
class ToolCallServiceIntegrationTests {

    /** 可观测假工具：记录被调用了几次，让"没被调用"成为可断言的事实。 */
    static final AtomicInteger SIDE_EFFECT_CALLS = new AtomicInteger();
    static final AtomicInteger READ_ONLY_CALLS = new AtomicInteger();
    static volatile ToolExecutionResult NEXT_RESULT = ToolExecutionResult.success("工单号：RT-1");

    @TestConfiguration
    static class Tools {
        @Bean
        ToolExecutor sideEffectTool() {
            return executor("submit-return-ticket", SIDE_EFFECT_CALLS);
        }

        @Bean
        ToolExecutor readOnlyTool() {
            return executor("lookup-order", READ_ONLY_CALLS);
        }

        private static ToolExecutor executor(String name, AtomicInteger counter) {
            return new ToolExecutor() {
                @Override
                public String name() {
                    return name;
                }

                @Override
                public ToolExecutionResult execute(String argumentsJson, Duration timeout) {
                    counter.incrementAndGet();
                    return NEXT_RESULT;
                }
            };
        }
    }

    @Autowired
    private ToolCallService service;

    @Autowired
    private ToolCallRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID turnId;

    @BeforeEach
    void setUp() {
        SIDE_EFFECT_CALLS.set(0);
        READ_ONLY_CALLS.set(0);
        NEXT_RESULT = ToolExecutionResult.success("工单号：RT-1");
        turnId = seedTurn();
    }

    private static ToolCall call(String name, String arguments) {
        return new ToolCall("c-" + UUID.randomUUID(), name, arguments);
    }

    private static final String VALID = "{\"orderNo\":\"ORD-1\",\"reason\":\"质量问题\"}";

    @Test
    void tool09_有副作用工具在待确认期间零外部调用() {
        UUID id = service.record(turnId, call("submit-return-ticket", VALID));

        assertThat(repository.find(turnId, id).orElseThrow().status())
                .isEqualTo(ToolCallStatus.PENDING_CONFIRM);
        assertThat(SIDE_EFFECT_CALLS).hasValue(0);
    }

    @Test
    void tool15_只读工具不经确认直接执行且参数可见() {
        UUID id = service.record(turnId, call("lookup-order", VALID));

        var row = repository.find(turnId, id).orElseThrow();
        assertThat(row.status()).isEqualTo(ToolCallStatus.SUCCEEDED);
        assertThat(READ_ONLY_CALLS).hasValue(1);
        assertThat(row.argumentsJson()).contains("ORD-1");
    }

    @Test
    void 确认后执行并落终态与耗时() {
        UUID id = service.record(turnId, call("submit-return-ticket", VALID));

        service.decide(turnId, id, ToolCallService.Decision.CONFIRM, UUID.randomUUID());

        var row = repository.find(turnId, id).orElseThrow();
        assertThat(row.status()).isEqualTo(ToolCallStatus.SUCCEEDED);
        assertThat(row.resultText()).isEqualTo("工单号：RT-1");
        assertThat(row.latencyMs()).isNotNull();
        assertThat(SIDE_EFFECT_CALLS).hasValue(1);
    }

    @Test
    void tool11_同一clientRequestId重复确认只执行一次() {
        UUID id = service.record(turnId, call("submit-return-ticket", VALID));
        UUID key = UUID.randomUUID();

        service.decide(turnId, id, ToolCallService.Decision.CONFIRM, key);
        service.decide(turnId, id, ToolCallService.Decision.CONFIRM, key);

        // 幂等重放：第二次返回同一结果而不是 409，也不再调一次工具。
        assertThat(SIDE_EFFECT_CALLS).hasValue(1);
        assertThat(repository.find(turnId, id).orElseThrow().status())
                .isEqualTo(ToolCallStatus.SUCCEEDED);
    }

    @Test
    void tool12_取消后再确认返回冲突且始终未执行() {
        UUID id = service.record(turnId, call("submit-return-ticket", VALID));
        service.decide(turnId, id, ToolCallService.Decision.CANCEL, UUID.randomUUID());

        assertThatThrownBy(() -> service.decide(
                        turnId, id, ToolCallService.Decision.CONFIRM, UUID.randomUUID()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("已经处理过");
        assertThat(SIDE_EFFECT_CALLS).hasValue(0);
        assertThat(repository.find(turnId, id).orElseThrow().status())
                .isEqualTo(ToolCallStatus.CANCELLED);
    }

    @Test
    void tool13_失败后重试产生新记录且有副作用者重新回到待确认() {
        NEXT_RESULT = ToolExecutionResult.failure(ToolFailureCode.EXECUTION_TIMEOUT);
        UUID id = service.record(turnId, call("submit-return-ticket", VALID));
        service.decide(turnId, id, ToolCallService.Decision.CONFIRM, UUID.randomUUID());
        assertThat(repository.find(turnId, id).orElseThrow().status()).isEqualTo(ToolCallStatus.FAILED);

        UUID retried = service.retry(turnId, id);

        assertThat(retried).isNotEqualTo(id);
        assertThat(repository.find(turnId, retried).orElseThrow().status())
                .isEqualTo(ToolCallStatus.PENDING_CONFIRM);
        // 上一次的确认不能被复用：重试后工具没有再被调用，直到用户重新确认。
        assertThat(SIDE_EFFECT_CALLS).hasValue(1);
    }

    @Test
    void tool14_超时归一化为闭集码且不带自由文本() {
        NEXT_RESULT = ToolExecutionResult.failure(ToolFailureCode.EXECUTION_TIMEOUT);
        UUID id = service.record(turnId, call("lookup-order", VALID));

        var row = repository.find(turnId, id).orElseThrow();
        assertThat(row.status()).isEqualTo(ToolCallStatus.FAILED);
        assertThat(row.failureCode()).isEqualTo(ToolFailureCode.EXECUTION_TIMEOUT);
        assertThat(row.resultText()).isNull();
    }

    @Test
    void tool16_工具返回按纯文本存取不做任何解析() {
        NEXT_RESULT = ToolExecutionResult.success("<data>x</data> [link](http://x) <script>1</script>");
        UUID id = service.record(turnId, call("lookup-order", VALID));

        assertThat(repository.find(turnId, id).orElseThrow().resultText())
                .isEqualTo("<data>x</data> [link](http://x) <script>1</script>");
    }

    @Test
    void tool18_待确认状态持久化刷新后仍可裁决() {
        UUID id = service.record(turnId, call("submit-return-ticket", VALID));

        // 换一个 Repository 实例读，等价于"新请求重新读库"——状态不在前端内存里。
        var reread = new ToolCallRepository(jdbc).find(turnId, id).orElseThrow();
        assertThat(reread.status()).isEqualTo(ToolCallStatus.PENDING_CONFIRM);

        service.decide(turnId, id, ToolCallService.Decision.CONFIRM, UUID.randomUUID());
        assertThat(SIDE_EFFECT_CALLS).hasValue(1);
    }

    @Test
    void tool03_未注册工具落不可用且零调用() {
        UUID id = service.record(turnId, call("refund", VALID));

        var row = repository.find(turnId, id).orElseThrow();
        assertThat(row.status()).isEqualTo(ToolCallStatus.UNAVAILABLE);
        // 状态本身就是原因，不额外存 failure_code——那只属于"执行了但失败了"（数据模型 §15.1）。
        assertThat(row.failureCode()).isNull();
        assertThat(row.argumentsJson()).isNull();
        assertThat(SIDE_EFFECT_CALLS.get() + READ_ONLY_CALLS.get()).isZero();
    }

    @Test
    void tool07_参数不合法落已中止且零调用() {
        UUID id = service.record(turnId, call("submit-return-ticket", "{\"reason\":\"质量问题\"}"));

        assertThat(repository.find(turnId, id).orElseThrow().status()).isEqualTo(ToolCallStatus.ABORTED);
        assertThat(SIDE_EFFECT_CALLS).hasValue(0);
    }

    @Test
    void 同一轮的多次调用序号递增且可整批读出() {
        service.record(turnId, call("lookup-order", VALID));
        service.record(turnId, call("submit-return-ticket", VALID));

        assertThat(service.findByTurn(turnId))
                .extracting(ToolCallRepository.ToolCallRow::ordinal)
                .containsExactly(0, 1);
    }

    private UUID seedTurn() {
        UUID conversationId = UUID.randomUUID();
        jdbc.update(
                "insert into qa_conversation (id, title, last_active_at) values (?, '工具测试', now())",
                conversationId);
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
}
