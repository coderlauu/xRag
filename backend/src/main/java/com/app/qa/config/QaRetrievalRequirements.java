package com.app.qa.config;

import com.app.ai.model.Capability;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Collection;
import java.util.OptionalInt;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * 需要外部事实的两条启动校验（ADR 0008 决策 5、架构 §19.3 / §22.1）。
 *
 * <p>拆出来是因为它们读的不是配置本身：一条要问 DataSource 实际的连接池大小，
 * 一条要问模型目录里 Provider 声明的 rerank 文档上限。
 */
@Component
public final class QaRetrievalRequirements implements InitializingBean {

    private final QaRetrievalProperties retrieval;
    private final DataSource dataSource;
    private final ModelCatalogSnapshot catalog;

    public QaRetrievalRequirements(
            QaRetrievalProperties retrieval, DataSource dataSource, ModelCatalogSnapshot catalog) {
        this.retrieval = retrieval;
        this.dataSource = dataSource;
        this.catalog = catalog;
    }

    @Override
    public void afterPropertiesSet() {
        requireParallelismWithinPool(retrieval.getMaxParallelism(), resolvePoolSize(dataSource));
        maxRerankDocuments(catalog.deployments().values())
                .ifPresent(limit -> requireRerankInputWithinProviderLimit(retrieval.getRerankInputSize(), limit));
    }

    /**
     * 每个并行检索任务独占一条只读事务连接（{@code set local hnsw.iterative_scan} 要求事务），
     * 所以并行度必须严格小于连接池。放行的代价不是"慢一点"，而是一个会被误诊的症状：
     * 多出来的线程阻塞在获取连接上，看起来完全像"向量检索变慢了"，排查会直奔索引和 SQL，
     * 而真正的瓶颈在连接池。这与 Embedding 维度不一致时直接启动失败是同一条纪律。
     */
    public static void requireParallelismWithinPool(int parallelism, int poolSize) {
        if (parallelism >= poolSize) {
            throw new IllegalStateException(
                    "app.qa.retrieval.max-parallelism (" + parallelism + ") must be less than "
                            + "spring.datasource.hikari.maximum-pool-size (" + poolSize + "); "
                            + "each parallel retrieval task holds one read-only transaction connection");
        }
    }

    public static void requireRerankInputWithinProviderLimit(int rerankInputSize, int maxDocuments) {
        if (rerankInputSize > maxDocuments) {
            throw new IllegalStateException(
                    "app.qa.retrieval.rerank-input-size (" + rerankInputSize + ") must not exceed the smallest "
                            + "enabled rerank deployment max-documents (" + maxDocuments + ")");
        }
    }

    /**
     * 没有启用的 RERANK deployment 时返回空——那是合法状态，精排会降级为 Noop
     * （PRD §12.5），不该因此拒绝启动。
     */
    public static OptionalInt maxRerankDocuments(Collection<ModelDeployment> deployments) {
        return deployments.stream()
                .filter(ModelDeployment::enabled)
                .filter(deployment -> deployment.capabilities().contains(Capability.RERANK))
                .filter(deployment -> deployment.rerank() != null)
                .mapToInt(deployment -> deployment.rerank().maxDocuments())
                .min();
    }

    private static int resolvePoolSize(DataSource dataSource) {
        if (dataSource instanceof HikariDataSource hikari) {
            return hikari.getMaximumPoolSize();
        }
        // 不认识的 DataSource 就无法核对这条不变量。这里选择失败而不是跳过：
        // 静默跳过等于把"并行度超过连接池"留到运行期，而那正是本校验要拦的事。
        throw new IllegalStateException(
                "cannot verify app.qa.retrieval.max-parallelism against the connection pool: "
                        + "expected a HikariDataSource but found " + dataSource.getClass().getName());
    }
}
