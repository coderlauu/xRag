package com.app.qa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class QaUnderstandingConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QaUnderstandingConfiguration.class);

    @Test
    void 默认配置与已批准的3A边界完全一致() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(QaUnderstandingProperties.class);
            assertThat(properties.getMaxSubQuestions()).isEqualTo(3);
            assertThat(properties.getMaxTotalIntents()).isEqualTo(3);
            assertThat(properties.getHistoryKeepTurns()).isEqualTo(8);
            assertThat(properties.getSummaryStartTurns()).isEqualTo(12);
            assertThat(properties.getMaxIntentPromptBytes()).isEqualTo(65_536);
            assertThat(properties.getThresholds().getRetain()).isEqualTo(0.35);
            assertThat(properties.getThresholds().getTargeted()).isEqualTo(0.40);
            assertThat(properties.getThresholds().getFallback()).isEqualTo(0.60);
            assertThat(properties.getThresholds().getSupplement()).isEqualTo(0.80);
            assertThat(properties.getThresholds().getAmbiguityMargin()).isEqualTo(0.15);
        });
    }

    @Test
    void 摘要水位配置矛盾会阻止启动并指出完整路径() {
        contextRunner
                .withPropertyValues(
                        "app.qa.understanding.history-keep-turns=8",
                        "app.qa.understanding.summary-start-turns=8")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "app.qa.understanding.summary-start-turns must be greater than "
                                        + "app.qa.understanding.history-keep-turns"));
    }

    @Test
    void 总意图少于子问题会阻止启动() {
        contextRunner
                .withPropertyValues("app.qa.understanding.max-total-intents=2")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "app.qa.understanding.max-total-intents must be greater than or equal to "
                                        + "app.qa.understanding.max-sub-questions"));
    }

    @Test
    void 阈值乱序会阻止启动() {
        contextRunner
                .withPropertyValues("app.qa.understanding.thresholds.targeted=0.2")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "app.qa.understanding.thresholds must satisfy "
                                        + "retain <= targeted <= fallback <= supplement"));
    }

    @Test
    void 非法歧义差值会阻止启动() {
        contextRunner
                .withPropertyValues("app.qa.understanding.thresholds.ambiguity-margin=0.5")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage(
                                "app.qa.understanding.thresholds.ambiguity-margin must be finite, greater than 0, "
                                        + "and less than 0.5"));
    }

    @Test
    void 评分执行器只有三个线程和三个排队位且有诊断名称() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var executor = context.getBean("qaIntentScoringExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(3);
            assertThat(executor.getMaxPoolSize()).isEqualTo(3);
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(3);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("qa-intent-score-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        });
    }
}
