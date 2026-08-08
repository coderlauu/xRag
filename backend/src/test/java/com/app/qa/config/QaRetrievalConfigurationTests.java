package com.app.qa.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 3B-CFG-01~04、3B-CFG-06。 */
class QaRetrievalConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QaRetrievalConfiguration.class);

    @Test
    void 默认配置与已批准的3B基线完全一致() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(QaRetrievalProperties.class);
            assertThat(properties.getMaxParallelism()).isEqualTo(4);
            assertThat(properties.getChannelTopK()).isEqualTo(50);
            assertThat(properties.getRerankInputSize()).isEqualTo(30);
            assertThat(properties.getTopN()).isEqualTo(8);
            assertThat(properties.getRrfK()).isEqualTo(60);
            assertThat(context.getBean(QaToolProperties.class).getWhitelist()).isEmpty();
        });
    }

    @Test
    void 检索执行器有界且与意图评分执行器不共享队列() {
        contextRunner.run(context -> {
            var executor = context.getBean("qaRetrievalExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("qa-retrieval-");
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
            // 3B-CFG-06：两个执行器是两个 Bean，队列不共享。
            assertThat(context.getBeanNamesForType(ThreadPoolTaskExecutor.class))
                    .contains("qaRetrievalExecutor")
                    .doesNotContain("qaIntentScoringExecutor");
        });
    }

    @Test
    void channelTopK小于topN时阻止启动并指出完整路径() {
        contextRunner
                .withPropertyValues("app.qa.retrieval.channel-top-k=4", "app.qa.retrieval.top-n=8")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.retrieval.channel-top-k must be greater than or equal to "
                                + "app.qa.retrieval.top-n"));
    }

    @Test
    void rerank输入上限不得超过通道topK() {
        contextRunner
                .withPropertyValues("app.qa.retrieval.rerank-input-size=51")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.retrieval.rerank-input-size must not exceed "
                                + "app.qa.retrieval.channel-top-k"));
    }

    @Test
    void 非正数配置全部阻止启动() {
        contextRunner
                .withPropertyValues("app.qa.retrieval.rrf-k=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.retrieval.rrf-k must be positive"));
        contextRunner
                .withPropertyValues("app.qa.retrieval.max-parallelism=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.retrieval.max-parallelism must be positive"));
    }

    @Test
    void 并行度不小于连接池时阻止启动且错误含两个实际值() {
        assertThatThrownBy(() -> QaRetrievalRequirements.requireParallelismWithinPool(20, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-parallelism (20)")
                .hasMessageContaining("maximum-pool-size (20)")
                .hasMessageContaining("read-only transaction connection");
        assertThatThrownBy(() -> QaRetrievalRequirements.requireParallelismWithinPool(21, 20))
                .isInstanceOf(IllegalStateException.class);
        // 边界：19 < 20 通过。
        QaRetrievalRequirements.requireParallelismWithinPool(19, 20);
    }

    @Test
    void rerank输入超过目录声明上限时阻止启动() {
        assertThatThrownBy(() -> QaRetrievalRequirements.requireRerankInputWithinProviderLimit(101, 100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rerank-input-size (101)")
                .hasMessageContaining("max-documents (100)");
        QaRetrievalRequirements.requireRerankInputWithinProviderLimit(100, 100);
    }

    @Test
    void 目录没有可用rerank部署时不拒绝启动() {
        // 精排降级为 Noop 是 PRD §12.5 要求的合法产品状态，不该因此启动失败。
        assertThat(QaRetrievalRequirements.maxRerankDocuments(List.of())).isEmpty();
    }

    // 刻意不构造 ModelDeployment / RerankProfile 夹具来覆盖"多部署取最小上限"：
    // 并行会话正在改 RerankProfile 的构造签名（HEAD 是 1 参、工作区是 2 参），照它写夹具
    // 会让本工单的测试跟着别人的未提交改动一起红。等 Phase 2 工单 23 提交后补这条。
}
