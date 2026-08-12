package com.app.qa.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 3C-CFG-01~04：只从配置绑定与启动 seam 验证。 */
class QaGenerationConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(QaGenerationConfiguration.class);

    @Test
    void 默认配置与已批准的3C预算和执行资源一致() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            var properties = context.getBean(QaGenerationProperties.class);
            assertThat(properties.getMaxConcurrency()).isEqualTo(4);
            assertThat(properties.getQueueCapacity()).isEqualTo(32);
            assertThat(properties.getQueueWait()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.getFirstByteTimeout()).isEqualTo(Duration.ofSeconds(15));
            assertThat(properties.getModelDeadline()).isEqualTo(Duration.ofSeconds(120));
            assertThat(properties.getSseTimeout()).isEqualTo(Duration.ofSeconds(165));
            assertThat(properties.getPushThreads()).isEqualTo(4);
            assertThat(properties.getPushQueueCapacity()).isEqualTo(256);

            var generation = context.getBean("qaGenerationExecutor", ThreadPoolTaskExecutor.class);
            assertThat(generation.getCorePoolSize()).isEqualTo(4);
            assertThat(generation.getMaxPoolSize()).isEqualTo(4);
            assertThat(generation.getThreadNamePrefix()).isEqualTo("qa-generation-");

            var push = context.getBean("qaGenerationPushExecutor", ThreadPoolTaskExecutor.class);
            assertThat(push.getCorePoolSize()).isEqualTo(4);
            assertThat(push.getMaxPoolSize()).isEqualTo(4);
            assertThat(push.getThreadNamePrefix()).isEqualTo("qa-generation-push-");
        });
    }

    @Test
    void 队列容量小于并发许可时阻止启动并指出两个实际值() {
        contextRunner
                .withPropertyValues(
                        "app.qa.generation.max-concurrency=4",
                        "app.qa.generation.queue-capacity=3")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.generation.queue-capacity (3) must be greater than or equal to "
                                + "app.qa.generation.max-concurrency (4)"));
    }

    @Test
    void 执行器线程数漂移时阻止启动并指出配置值与实际值() {
        contextRunner
                .withBean("generationExecutorDrift", BeanPostProcessor.class, () -> new BeanPostProcessor() {
                    @Override
                    public Object postProcessBeforeInitialization(Object bean, String beanName) {
                        if (beanName.equals("qaGenerationExecutor")) {
                            var executor = (ThreadPoolTaskExecutor) bean;
                            executor.setCorePoolSize(3);
                            executor.setMaxPoolSize(3);
                        }
                        return bean;
                    }
                })
                .run(context -> assertThat(context.getStartupFailure())
                        .hasMessage("app.qa.generation.max-concurrency (4) must equal "
                                + "qaGenerationExecutor core/max pool size (3/3)"));
    }

    @Test
    void 执行资源数量必须为正且push队列不得小于任务队列() {
        contextRunner
                .withPropertyValues("app.qa.generation.max-concurrency=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.generation.max-concurrency must be positive"));
        contextRunner
                .withPropertyValues("app.qa.generation.push-threads=0")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.generation.push-threads must be positive"));
        contextRunner
                .withPropertyValues("app.qa.generation.push-queue-capacity=31")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.generation.push-queue-capacity (31) must be greater than or equal to "
                                + "app.qa.generation.queue-capacity (32)"));
    }

    @Test
    void 时间预算必须为正且保持首包小于模型并被SSE完整包住() {
        contextRunner
                .withPropertyValues("app.qa.generation.queue-wait=0ms")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.generation.queue-wait must be positive"));
        contextRunner
                .withPropertyValues(
                        "app.qa.generation.first-byte-timeout=15s",
                        "app.qa.generation.model-deadline=15s")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.generation.first-byte-timeout (PT15S) must be less than "
                                + "app.qa.generation.model-deadline (PT15S)"));
        contextRunner
                .withPropertyValues("app.qa.generation.sse-timeout=164999ms")
                .run(context -> assertThat(context.getStartupFailure())
                        .hasRootCauseMessage("app.qa.generation.sse-timeout (PT2M44.999S) must be greater than or "
                                + "equal to queue-wait + model-deadline + PT15S (PT2M45S)"));
    }
}
