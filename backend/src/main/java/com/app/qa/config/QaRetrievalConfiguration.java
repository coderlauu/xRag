package com.app.qa.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({QaRetrievalProperties.class, QaToolProperties.class})
public class QaRetrievalConfiguration {

    /**
     * 检索专用执行器，**与意图评分执行器分开**（架构 §19.1）。
     *
     * <p>两者不共享队列的理由不是"隔离更好听"：意图评分是纯模型调用，检索任务每个都占着一条
     * 数据库连接。混在一个队列里，一次评分风暴会把检索任务顶到排队，而排队的检索任务仍然
     * 持有连接——这正是 ADR 0008 决策 5 要拦的那种"看起来像检索慢了"的症状。
     *
     * <p>拒绝策略是调用线程执行：宁可请求变慢，也不丢掉某个子问题的某个通道。但要清楚它的代价，
     * 退化成串行恰好发生在负载最高、最不能忍受延迟的时刻。
     */
    @Bean("qaRetrievalExecutor")
    ThreadPoolTaskExecutor qaRetrievalExecutor(QaRetrievalProperties properties) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getMaxParallelism());
        executor.setMaxPoolSize(properties.getMaxParallelism());
        executor.setQueueCapacity(properties.getMaxParallelism());
        executor.setThreadNamePrefix("qa-retrieval-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
