package com.app.qa.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 3B 检索配置（架构 §22.1）。
 *
 * <p>本类只做**自洽性**校验——只看得见自己字段的那些约束。需要外部事实的两条
 * （并行度 &lt; 连接池、rerank 输入 &le; Provider 上限）在 {@link QaRetrievalRequirements}，
 * 因为它们要读 DataSource 与模型目录，放在这里会让配置类反向依赖运行期组件。
 */
@ConfigurationProperties("app.qa.retrieval")
public class QaRetrievalProperties implements InitializingBean {

    private int maxParallelism = 4;
    private int channelTopK = 50;
    private int rerankInputSize = 30;
    private int topN = 8;
    private int rrfK = 60;

    @Override
    public void afterPropertiesSet() {
        if (maxParallelism < 1) {
            throw new IllegalArgumentException("app.qa.retrieval.max-parallelism must be positive");
        }
        if (topN < 1) {
            throw new IllegalArgumentException("app.qa.retrieval.top-n must be positive");
        }
        if (rrfK < 1) {
            throw new IllegalArgumentException("app.qa.retrieval.rrf-k must be positive");
        }
        if (channelTopK < topN) {
            throw new IllegalArgumentException(
                    "app.qa.retrieval.channel-top-k must be greater than or equal to app.qa.retrieval.top-n");
        }
        if (rerankInputSize < topN) {
            throw new IllegalArgumentException(
                    "app.qa.retrieval.rerank-input-size must be greater than or equal to app.qa.retrieval.top-n");
        }
        if (rerankInputSize > channelTopK) {
            throw new IllegalArgumentException(
                    "app.qa.retrieval.rerank-input-size must not exceed app.qa.retrieval.channel-top-k");
        }
    }

    public int getMaxParallelism() {
        return maxParallelism;
    }

    public void setMaxParallelism(int maxParallelism) {
        this.maxParallelism = maxParallelism;
    }

    public int getChannelTopK() {
        return channelTopK;
    }

    public void setChannelTopK(int channelTopK) {
        this.channelTopK = channelTopK;
    }

    public int getRerankInputSize() {
        return rerankInputSize;
    }

    public void setRerankInputSize(int rerankInputSize) {
        this.rerankInputSize = rerankInputSize;
    }

    public int getTopN() {
        return topN;
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public int getRrfK() {
        return rrfK;
    }

    public void setRrfK(int rrfK) {
        this.rrfK = rrfK;
    }
}
