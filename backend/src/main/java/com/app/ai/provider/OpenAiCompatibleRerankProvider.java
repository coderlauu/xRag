package com.app.ai.provider;

import com.app.ai.model.ModelDeployment;
import com.app.ai.model.RerankCandidate;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * {@code POST /reranks} adapter（百炼的 OpenAI 兼容扩展）。
 *
 * <p>标准 OpenAI 协议里没有 Rerank，这里跟随的是 Cohere/Jina/百炼共用的
 * {@code {query, documents, top_n}} → {@code {results:[{index, relevance_score}]}} 形状。
 *
 * <p>适配器只发一次请求并把上游结果分类，**不做 index 校验也不映射稳定 ID**：那是调度层的事，
 * 因为只有它握着请求里的 candidate 列表。这里返回的 index 原样来自上游，可能越界或重复。
 *
 * <p>真实线路形状（路径、字段名、是否需要 {@code return_documents}）必须在工单 23 的
 * Provider 准入里对着当天官方文档核实一遍，不要把这里的实现当成已验证事实。
 */
public final class OpenAiCompatibleRerankProvider implements RerankProvider {

    private static final String REQUEST_ID_HEADER = "x-request-id";

    private final URI endpoint;
    private final RestClient restClient;

    /**
     * 未知字段必须忽略，理由同 {@link OpenAiCompatibleEmbeddingProvider}：这套形状只固定了本适配器
     * 读取的字段，供应商普遍会额外返回 {@code id}、{@code results[].document}、{@code meta} 等，
     * 用 Jackson 的默认严格模式会让一个正常的 200 响应整体绑定失败。
     */
    private final ObjectMapper json = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public OpenAiCompatibleRerankProvider(URI baseUrl, String apiKey) {
        this.endpoint = reranksEndpoint(Objects.requireNonNull(baseUrl));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
    }

    @Override
    public ProviderRerankResult rerank(
            ModelDeployment deployment,
            ProviderRerankRequest request,
            ProviderAttemptContext context) {
        Objects.requireNonNull(deployment);
        Objects.requireNonNull(request);
        Objects.requireNonNull(context);
        List<String> documents = request.candidates().stream()
                .map(RerankCandidate::text)
                .toList();
        context.lifecycle().dispatched();
        try {
            return restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(
                            deployment.upstreamModel(), request.query(), documents, request.topN()))
                    .exchange((httpRequest, response) -> decode(response));
        } catch (ProviderFailureException failure) {
            throw failure;
        } catch (ResourceAccessException network) {
            throw networkFailure(network);
        } catch (RestClientException unexpected) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        }
    }

    private ProviderRerankResult decode(ClientHttpResponse response) throws java.io.IOException {
        int status = response.getStatusCode().value();
        String requestIdMasked = IdentifierMasker.mask(
                response.getHeaders().getFirst(REQUEST_ID_HEADER));
        if (status < 200 || status >= 300) {
            Duration retryAfter = parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
            if (status == 401 || status == 403 || status == 429 || status >= 500) {
                throw ProviderFailureException.http(status, retryAfter, requestIdMasked);
            }
            if (status >= 400) {
                throw new ProviderFailureException(
                        AttemptFailureType.UPSTREAM_REQUEST_REJECTED,
                        status,
                        retryAfter,
                        requestIdMasked);
            }
            throw new ProviderFailureException(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, requestIdMasked);
        }
        Response body;
        try {
            body = json.readValue(response.getBody(), Response.class);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw new ProviderFailureException(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, requestIdMasked);
        }
        if (body == null || body.results() == null) {
            throw new ProviderFailureException(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, requestIdMasked);
        }
        List<ProviderRankedItem> items = new ArrayList<>(body.results().size());
        for (Result result : body.results()) {
            if (result == null) {
                throw new ProviderFailureException(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, requestIdMasked);
            }
            items.add(new ProviderRankedItem(result.index(), result.relevanceScore()));
        }
        return new ProviderRerankResult(items, requestIdMasked);
    }

    private static ProviderFailureException networkFailure(ResourceAccessException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof java.net.SocketTimeoutException) {
                return ProviderFailureException.of(AttemptFailureType.RESPONSE_BODY_TIMEOUT);
            }
            if (current instanceof ConnectException) {
                return ProviderFailureException.of(AttemptFailureType.CONNECT_FAILED);
            }
            if (current instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED);
            }
            current = current.getCause();
        }
        return ProviderFailureException.of(AttemptFailureType.RESPONSE_BODY_FAILED);
    }

    private static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException notSeconds) {
            return null;
        }
    }

    private static URI reranksEndpoint(URI baseUrl) {
        String base = baseUrl.toString();
        return URI.create(base.endsWith("/") ? base + "reranks" : base + "/reranks");
    }

    private record Request(
            String model,
            String query,
            List<String> documents,
            @JsonProperty("top_n") int topN) {
    }

    private record Response(List<Result> results) {
    }

    private record Result(
            @JsonProperty("index") int index,
            @JsonProperty("relevance_score") double relevanceScore) {
    }
}
