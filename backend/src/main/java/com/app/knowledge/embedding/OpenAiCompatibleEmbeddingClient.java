package com.app.knowledge.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 走 OpenAI 兼容的 {@code POST /embeddings} 协议。
 *
 * <p>绑定的不是 OpenAI，而是一个事实标准——阿里云百炼（DashScope）、智谱、硅基流动、
 * 本地 Ollama 都提供兼容该协议的端点，一份实现全部覆盖，换供应商只改配置。
 */
class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final RestClient restClient;
    private final EmbeddingProperties properties;

    OpenAiCompatibleEmbeddingClient(RestClient.Builder builder, EmbeddingProperties properties) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultStatusHandler(status -> status.isError(), (request, response) -> {
                    String body = new String(response.getBody().readAllBytes());
                    int code = response.getStatusCode().value();
                    String message = "Embedding API 返回 %d：%s".formatted(code, body);
                    // 429 单独成类：它是唯一"等一会儿再来就行"的失败，其余重试多少次都一样
                    throw code == 429 ? new EmbeddingRateLimitException(message)
                            : new EmbeddingException(message);
                })
                .build();
    }

    /**
     * 分批串行调用。**批次之间有最小间隔、429 会退避重试**，两者都是必需的而非保险措施：
     * 实测一份 700 分块的文档要串行发出约 70 次请求，**8.4 秒后**就撞上
     * {@code AccountRateLimitExceeded}，整个入库任务失败；用户重试也没用，因为重试同样
     * 是一次性把几十个请求打出去。
     *
     * <p>这个缺口在拿到真实 Key 之前不可能发现——假 Embedding 实现不会限流。而设计阶段
     * 漏掉它是因为 architecture.md 里所有"限流"讨论讲的都是**入站**方向（用信号量保护
     * 自己的磁盘 IO），**出站被供应商限流**是同一个词的反方向。
     *
     * <p>两个手段分工不同：间隔从源头降低触发概率，退避处理仍然撞上的情况。只做后者的话，
     * 大文档会在每一批都撞一次、退避一次，总耗时反而更长。
     */
    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        int batchSize = properties.getBatchSize();
        for (int from = 0; from < texts.size(); from += batchSize) {
            if (from > 0) {
                pause(properties.getBatchInterval());
            }
            int to = Math.min(from + batchSize, texts.size());
            vectors.addAll(embedBatchWithRetry(texts.subList(from, to)));
        }
        return vectors;
    }

    /**
     * 指数退避重试，只对 429 生效。
     *
     * <p>失败信息里带上"重试 N 次后仍然失败"——用户看到的是"试过了"，而不是一次性放弃；
     * 少了这句话，排查的人会先怀疑根本没有重试机制。
     */
    private List<float[]> embedBatchWithRetry(List<String> batch) {
        int maxRetries = properties.getMaxRetries();
        Duration delay = properties.getRetryBaseDelay();
        for (int attempt = 0; ; attempt++) {
            try {
                return embedBatch(batch);
            } catch (EmbeddingRateLimitException rateLimited) {
                if (attempt >= maxRetries) {
                    throw new EmbeddingRateLimitException(
                            "%s（已重试 %d 次仍被限流，可调大 app.embedding.batch-interval 或 max-retries）"
                                    .formatted(rateLimited.getMessage(), maxRetries));
                }
                // 必须留痕：退避会让一份大文档的处理时间从几十秒涨到几分钟，没有这条日志，
                // 运维看到的只是"入库怎么这么慢"，而慢的真正原因（被供应商限流）无从查起。
                LOGGER.warn("Embedding 被限流，{} 后重试第 {}/{} 次", delay, attempt + 1, maxRetries);
                pause(delay);
                delay = delay.multipliedBy(2);
            }
        }
    }

    /**
     * **中断标志必须恢复**：这段代码跑在入库线程池的工作线程上，优雅停机时
     * {@code shutdownNow()} 正是靠中断来叫停它。吞掉标志会让线程在停机时继续把剩下的
     * 几十批发完，应用迟迟退不出去。
     */
    private void pause(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException("向量计算被中断（应用可能正在关闭）", interrupted);
        }
    }

    @Override
    public int dimensions() {
        return properties.getDimensions();
    }

    private List<float[]> embedBatch(List<String> batch) {
        Response response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(properties.getModel(), batch, properties.getDimensions()))
                    .retrieve()
                    .body(Response.class);
        } catch (EmbeddingException alreadyDescriptive) {
            throw alreadyDescriptive;
        } catch (RestClientException exception) {
            throw new EmbeddingException(
                    "调用 Embedding API 失败（%s，模型 %s）：%s".formatted(
                            properties.getBaseUrl(), properties.getModel(), exception.toString()),
                    exception);
        }

        if (response == null || response.data() == null || response.data().size() != batch.size()) {
            throw new EmbeddingException("Embedding API 返回条数与请求不符：请求 %d 条，返回 %s"
                    .formatted(batch.size(), response == null || response.data() == null
                            ? "空响应" : response.data().size() + " 条"));
        }

        // 按 index 重排而不是假定有序：协议允许供应商乱序返回，一旦乱序又不重排，
        // 症状是"向量都在、但每个分块配错了向量"——检索结果会莫名其妙，而且没有任何
        // 报错，是最难排查的一类 bug。
        float[][] ordered = new float[batch.size()][];
        for (Datum datum : response.data()) {
            int index = datum.index();
            if (index < 0 || index >= batch.size()) {
                throw new EmbeddingException(
                        "Embedding API 返回了越界的 index %d（本批 %d 条）".formatted(index, batch.size()));
            }
            if (ordered[index] != null) {
                throw new EmbeddingException("Embedding API 返回了重复的 index %d".formatted(index));
            }
            if (datum.embedding() == null || datum.embedding().length != properties.getDimensions()) {
                throw new EmbeddingException(
                        "Embedding 维度不符：配置 %d 维，第 %d 条返回 %s".formatted(
                                properties.getDimensions(), index,
                                datum.embedding() == null ? "null" : datum.embedding().length + " 维"));
            }
            ordered[index] = datum.embedding();
        }
        return List.of(ordered);
    }

    /**
     * {@code dimensions} 始终显式发送：它是让返回宽度与配置一致的唯一手段，
     * 若某个供应商不认这个参数，会立刻以 400 暴露出来，好过悄悄返回另一个维度的向量。
     */
    private record Request(String model, List<String> input, Integer dimensions) {}

    private record Response(List<Datum> data) {}

    private record Datum(@JsonProperty("index") int index, @JsonProperty("embedding") float[] embedding) {}
}
