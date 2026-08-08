package com.app.qa.tool;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.ToolCall;
import com.app.qa.config.QaToolProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 3B-TOOL-02~08。 */
class ToolAdmissionTests {

    private static final String SCHEMA =
            """
            {
              "type": "object",
              "properties": {
                "orderNo": {"type": "string"},
                "reason": {"type": "string", "enum": ["质量问题", "尺码不合适"]},
                "quantity": {"type": "integer"},
                "urgent": {"type": "boolean"},
                "note": {"type": "string", "default": "无"}
              },
              "required": ["orderNo", "reason"]
            }
            """;

    private final ToolArgumentValidator validator = new ToolArgumentValidator();

    private ToolCallAdmission admission(boolean sideEffect, boolean whitelisted) {
        var properties = new QaToolProperties();
        if (whitelisted) {
            properties.getWhitelist().add(tool(sideEffect));
        }
        return new ToolCallAdmission(new ToolRegistry(properties), validator);
    }

    private static QaToolProperties.Tool tool(boolean sideEffect) {
        var tool = new QaToolProperties.Tool();
        tool.setName("submit-return-ticket");
        tool.setDisplayName("提交退货工单");
        tool.setDescription("在售后工单系统中新建一张退货工单。");
        tool.setSideEffect(sideEffect);
        tool.setInputSchema("classpath:tools/qa/3b/submit-return-ticket.schema.json");
        // 单测直接注入 Schema 正文，绕开配置绑定期的资源读取。
        setSchemaJson(tool);
        return tool;
    }

    private static void setSchemaJson(QaToolProperties.Tool tool) {
        try {
            var field = QaToolProperties.Tool.class.getDeclaredField("inputSchemaJson");
            field.setAccessible(true);
            field.set(tool, SCHEMA);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static ToolCall call(String arguments) {
        return new ToolCall("call-1", "submit-return-ticket", arguments);
    }

    @Test
    void tool02_空白名单不产生任何工具定义() {
        // "没有配置工具"与"配置了但不可用"是两回事，前者不该产生"不可用"提示。
        var registry = new ToolRegistry(new QaToolProperties());
        assertThat(registry.definitions()).isEmpty();
        assertThat(registry.isWhitelisted("anything")).isFalse();
    }

    @Test
    void tool03_未注册工具判为不可用而不是静默跳过() {
        var decision = admission(true, false).admit(call("{}"));

        assertThat(decision.status()).isEqualTo(ToolCallStatus.UNAVAILABLE);
        assertThat(decision.failureCode()).isEqualTo(ToolFailureCode.NOT_WHITELISTED);
    }

    @Test
    void tool04_模型编造的字段被白名单过滤且不进入参数() {
        var decision = admission(false, true)
                .admit(call("{\"orderNo\":\"ORD-1\",\"reason\":\"质量问题\",\"format\":\"excel\"}"));

        assertThat(decision.status()).isEqualTo(ToolCallStatus.RUNNING);
        assertThat(decision.sanitized().has("format")).isFalse();
        assertThat(decision.sanitized().get("orderNo").asText()).isEqualTo("ORD-1");
    }

    @Test
    void tool05_整数参数收到小数或字符串一律中止() {
        for (String bad : new String[] {"10.5", "\"10\""}) {
            var decision = admission(false, true)
                    .admit(call("{\"orderNo\":\"O\",\"reason\":\"质量问题\",\"quantity\":" + bad + "}"));
            assertThat(decision.status()).as(bad).isEqualTo(ToolCallStatus.ABORTED);
            assertThat(decision.violations())
                    .extracting(ToolArgumentViolation::kind)
                    .contains(ToolArgumentViolation.Kind.TYPE_MISMATCH);
        }
    }

    @Test
    void tool06_枚举外取值被拒绝且提示给允许值而不是Schema原文() {
        var decision = admission(false, true)
                .admit(call("{\"orderNo\":\"O\",\"reason\":\"无理由\"}"));

        assertThat(decision.status()).isEqualTo(ToolCallStatus.ABORTED);
        var violation = decision.violations().stream()
                .filter(v -> v.parameter().equals("reason"))
                .findFirst()
                .orElseThrow();
        assertThat(violation.kind()).isEqualTo(ToolArgumentViolation.Kind.VALUE_NOT_ALLOWED);
        assertThat(violation.allowedValues()).containsExactly("质量问题", "尺码不合适");
    }

    @Test
    void tool07_缺必填参数不产生可执行状态() {
        var decision = admission(true, true).admit(call("{\"reason\":\"质量问题\"}"));

        assertThat(decision.status()).isEqualTo(ToolCallStatus.ABORTED);
        assertThat(decision.violations())
                .extracting(ToolArgumentViolation::parameter)
                .containsExactly("orderNo");
        // 关键否定命题：没有任何可执行状态被产生，不存在"过滤掉后照常执行"的路径。
        assertThat(decision.sanitized()).isNull();
    }

    @Test
    void tool08_有默认值的可选参数不被代码补位() {
        // "抽取失败就退全默认值"对只读查询勉强说得通，对提交工单是灾难（PRD §12.7）。
        var decision = admission(false, true)
                .admit(call("{\"orderNo\":\"O\",\"reason\":\"质量问题\"}"));

        assertThat(decision.status()).isEqualTo(ToolCallStatus.RUNNING);
        assertThat(decision.sanitized().has("note")).isFalse();
    }

    @Test
    void tool08_参数不是JSON对象时视为必填全缺失而不是空参数执行() {
        // 真正的畸形 JSON 到不了这里——`ToolCall` 构造期就用 JsonTextValidator 拦住了。
        // 可达的是"合法 JSON 但不是 object"：模型返回数组或裸字符串。
        for (String notAnObject : new String[] {"[]", "\"reason\"", "123"}) {
            var decision = admission(true, true).admit(call(notAnObject));

            assertThat(decision.status()).as(notAnObject).isEqualTo(ToolCallStatus.ABORTED);
            assertThat(decision.violations())
                    .extracting(ToolArgumentViolation::parameter)
                    .containsExactlyInAnyOrder("orderNo", "reason");
        }
    }

    @Test
    void 有副作用工具复核通过后停在待确认而只读工具直接执行() {
        var arguments = "{\"orderNo\":\"O\",\"reason\":\"质量问题\"}";

        assertThat(admission(true, true).admit(call(arguments)).status())
                .isEqualTo(ToolCallStatus.PENDING_CONFIRM);
        assertThat(admission(false, true).admit(call(arguments)).status())
                .isEqualTo(ToolCallStatus.RUNNING);
    }

    @Test
    void 待确认与执行中都不是终态其余都是() {
        assertThat(ToolCallStatus.PENDING_CONFIRM.isTerminal()).isFalse();
        assertThat(ToolCallStatus.RUNNING.isTerminal()).isFalse();
        assertThat(List.of(
                        ToolCallStatus.SUCCEEDED,
                        ToolCallStatus.FAILED,
                        ToolCallStatus.CANCELLED,
                        ToolCallStatus.UNAVAILABLE,
                        ToolCallStatus.ABORTED))
                .allMatch(ToolCallStatus::isTerminal);
    }
}
