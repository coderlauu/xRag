package com.app.knowledge.embedding;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code app.embedding.*}，见 application.properties 与
 * tech/knowledge-base/architecture.md §6。
 */
@ConfigurationProperties(prefix = "app.embedding")
public class EmbeddingProperties {

    /** OpenAI 兼容端点的基地址，不含 {@code /embeddings} 路径段。 */
    private String baseUrl;

    /** 留空表示未配置——此时注入 {@link UnconfiguredEmbeddingClient}，应用照常启动。 */
    private String apiKey;

    private String model;

    private int dimensions;

    /** 单次请求的文本条数上限。 */
    private int batchSize;

    /** 收到 429 后最多重试几次。设为 0 关闭重试。 */
    private int maxRetries;

    /** 第一次重试前等多久，之后每次翻倍。 */
    private Duration retryBaseDelay;

    /** 相邻两批之间的最小间隔，用来从源头降低触发 429 的概率。设为 0 关闭。 */
    private Duration batchInterval;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public int getDimensions() {
        return dimensions;
    }

    public void setDimensions(int dimensions) {
        this.dimensions = dimensions;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public Duration getRetryBaseDelay() {
        return retryBaseDelay;
    }

    public void setRetryBaseDelay(Duration retryBaseDelay) {
        this.retryBaseDelay = retryBaseDelay;
    }

    public Duration getBatchInterval() {
        return batchInterval;
    }

    public void setBatchInterval(Duration batchInterval) {
        this.batchInterval = batchInterval;
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
