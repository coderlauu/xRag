package com.app.knowledge.embedding;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
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

    private final RestClient restClient;
    private final EmbeddingProperties properties;

    OpenAiCompatibleEmbeddingClient(RestClient.Builder builder, EmbeddingProperties properties) {
        this.properties = properties;
        this.restClient = builder
                .baseUrl(properties.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .defaultStatusHandler(status -> status.isError(), (request, response) -> {
                    throw new EmbeddingException("Embedding API 返回 %d：%s"
                            .formatted(response.getStatusCode().value(),
                                    new String(response.getBody().readAllBytes())));
                })
                .build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }

        List<float[]> vectors = new ArrayList<>(texts.size());
        int batchSize = properties.getBatchSize();
        for (int from = 0; from < texts.size(); from += batchSize) {
            int to = Math.min(from + batchSize, texts.size());
            vectors.addAll(embedBatch(texts.subList(from, to)));
        }
        return vectors;
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
