package com.app.qa.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 3B-TOOL-01、3B-TOOL-02 的配置侧断言。 */
class QaToolPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QaRetrievalConfiguration.class);

    @Test
    void 空白名单是合法状态() {
        // "没有配置工具"与"配置了但不可用"是两回事，前者不该产生任何提示（架构 §21.2）。
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(QaToolProperties.class).getWhitelist()).isEmpty();
        });
    }

    @Test
    void 合法工具被完整绑定且Schema正文启动时读出() {
        contextRunner.withPropertyValues(whitelist(0, "submit-return-ticket")).run(context -> {
            assertThat(context).hasNotFailed();
            var tool = context.getBean(QaToolProperties.class).getWhitelist().get(0);
            assertThat(tool.getName()).isEqualTo("submit-return-ticket");
            assertThat(tool.getDisplayName()).isEqualTo("提交退货工单");
            assertThat(tool.isSideEffect()).isTrue();
            // 工具定义启动后不变，Schema 正文启动时读出并缓存，不每次调用重拼。
            assertThat(tool.getInputSchemaJson()).contains("\"type\"");
        });
    }

    @Test
    void 重复工具名阻止启动并点名两处配置() {
        contextRunner
                .withPropertyValues(whitelist(0, "dup"))
                .withPropertyValues(whitelist(1, "dup"))
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("duplicate name 'dup'")
                        .hasMessageContaining("not an override"));
    }

    @Test
    void 缺少description阻止启动() {
        // description 同时是给模型的说明与确认卡上给用户看的那句话；缺了它，确认就只能
        // 依赖模型自己的说法（ui-spec §13.7.2）。
        contextRunner
                .withPropertyValues(
                        "app.qa.tools.whitelist[0].name=t",
                        "app.qa.tools.whitelist[0].display-name=T",
                        "app.qa.tools.whitelist[0].input-schema=classpath:tools/qa/3b/submit-return-ticket.schema.json")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("whitelist[t].description must not be blank"));
    }

    @Test
    void Schema文件缺失阻止启动() {
        contextRunner
                .withPropertyValues(
                        "app.qa.tools.whitelist[0].name=t",
                        "app.qa.tools.whitelist[0].display-name=T",
                        "app.qa.tools.whitelist[0].description=D",
                        "app.qa.tools.whitelist[0].input-schema=classpath:tools/qa/3b/does-not-exist.json")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("input-schema not found"));
    }

    @Test
    void 非正超时阻止启动() {
        contextRunner
                .withPropertyValues(whitelist(0, "t"))
                .withPropertyValues("app.qa.tools.whitelist[0].timeout=0s")
                .run(context -> assertThat(context.getStartupFailure())
                        .rootCause()
                        .hasMessageContaining("timeout must be positive"));
    }

    private static String[] whitelist(int index, String name) {
        String prefix = "app.qa.tools.whitelist[" + index + "].";
        return new String[] {
            prefix + "name=" + name,
            prefix + "display-name=提交退货工单",
            prefix + "description=在售后工单系统中新建一张退货工单，并把工单号回写到本次对话。",
            prefix + "side-effect=true",
            prefix + "input-schema=classpath:tools/qa/3b/submit-return-ticket.schema.json",
        };
    }
}
