package com.app.knowledge;

import com.app.knowledge.embedding.DeterministicEmbeddingClient;
import com.app.knowledge.embedding.EmbeddingClient;
import com.app.knowledge.embedding.EmbeddingException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * 全部集成测试共用的假 Embedding 实现。
 *
 * <p>**必须被所有集成测试类共同导入，这不是为了省事，是为了让它们共享同一个 Spring
 * 上下文。** Spring 按配置组合缓存上下文：只要有一个测试类的导入集合不同，就会另起一个
 * 上下文——而每个上下文都有自己的 {@code IngestionDispatcher}，旧上下文的调度器**不会
 * 停**。两个调度器同时活着时，后一个上下文的启动回收会把前一个正在跑的任务判成僵尸、
 * 标记为 FAILED，表现就是入库测试随机失败（实测 4 条时好时坏）。
 *
 * <p>生产环境是单实例、单上下文，不存在这个问题；但它恰好演示了 ADR 0002 里"启动回收依赖
 * 单实例假设"这条约束一旦被破坏会怎样——症状不是报错，是别人的任务被莫名其妙杀掉。
 */
@TestConfiguration
public class FakeEmbeddingConfig {

    /** 让下一次 embed 调用抛异常，用于验证失败路径的事务回滚。 */
    public static final AtomicBoolean SHOULD_FAIL = new AtomicBoolean(false);

    @Bean
    @Primary
    EmbeddingClient fakeEmbeddingClient() {
        DeterministicEmbeddingClient delegate = new DeterministicEmbeddingClient(1024);
        return new EmbeddingClient() {
            @Override
            public List<float[]> embed(List<String> texts) {
                if (SHOULD_FAIL.get()) {
                    throw new EmbeddingException("模拟的 Embedding 调用失败");
                }
                return delegate.embed(texts);
            }

            @Override
            public int dimensions() {
                return delegate.dimensions();
            }
        };
    }
}
