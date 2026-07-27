package com.app.knowledge;

import com.app.knowledge.embedding.DeterministicEmbeddingClient;
import com.app.knowledge.embedding.EmbeddingClient;
import com.app.knowledge.embedding.EmbeddingException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * 让下一次 embed 调用抛 {@link Error} 而不是 {@link EmbeddingException}。
     *
     * <p>造的是 {@code Error} 而不是随便一个 RuntimeException，因为要守的正是
     * {@code catch (Exception)} 与 {@code catch (Throwable)} 的差别——用 RuntimeException
     * 的话两种写法都能通过，测试就没有区分力了。
     */
    public static final AtomicBoolean SHOULD_THROW_ERROR = new AtomicBoolean(false);

    /**
     * 累计调用次数。这是"没有触发向量重算"唯一可靠的断言手段——CHK-21 要验的是
     * **一次 Embedding 调用都没发生**，而不是"结果看起来没变"。观察向量表本身做不到：
     * 确定性假实现对同样的内容会算出同样的向量，删旧插新之后表里的值与之前一模一样。
     */
    public static final AtomicInteger CALLS = new AtomicInteger();

    /**
     * 最近一次 embed 调用发生时，当前线程上**是否正处在一个数据库事务中**。
     *
     * <p>这是"Embedding 必须在事务外算完再开事务"这条纪律唯一的确定性断言手段。测试矩阵
     * 原本把它列为手工项（跑 psql 盯 `pg_stat_activity` 里有没有长事务），那个办法能覆盖
     * 异步入库那条链路，但对同步接口来说太贵也太容易漏——而这条纪律最容易被破坏的方式
     * 恰恰是**给方法加一个 {@code @Transactional}**，加了之后内部的编程式事务会静默地
     * 加入外层事务，什么报错都不会有。这里一行断言就能守住。
     */
    public static final AtomicBoolean IN_TRANSACTION_AT_CALL = new AtomicBoolean(false);

    @Bean
    @Primary
    EmbeddingClient fakeEmbeddingClient() {
        DeterministicEmbeddingClient delegate = new DeterministicEmbeddingClient(1024);
        return new EmbeddingClient() {
            @Override
            public List<float[]> embed(List<String> texts) {
                CALLS.incrementAndGet();
                IN_TRANSACTION_AT_CALL.set(TransactionSynchronizationManager.isActualTransactionActive());
                if (SHOULD_THROW_ERROR.get()) {
                    throw new OutOfMemoryError("模拟的 Error：向量化时堆内存耗尽");
                }
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
