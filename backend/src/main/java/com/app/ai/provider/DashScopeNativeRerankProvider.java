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
 * DashScope 原生 Rerank adapter：{@code POST /api/v1/services/rerank/text-rerank/text-rerank}。
 *
 * <p>存在的理由是实测事实（`2026-08-07`）：百炼**没有** OpenAI 风格的 Rerank 端点，
 * {@code {base}/reranks} 与单数 {@code /rerank} 都返回 404 空响应体，而同一把 Key 打这里
 * 返回 200 与真实 {@code relevance_score}。所以 {@link OpenAiCompatibleRerankProvider}
 * 对百炼不可用——两者是并列的协议实现，不是新旧关系。
 *
 * <p>与 OpenAI 兼容形状的三处实质差异：
 * <ol>
 *   <li>请求体嵌套：{@code {input:{query, documents}, parameters:{top_n}}}，不是平铺；</li>
 *   <li>响应体嵌套：结果在 {@code output.results} 下，不是顶层 {@code results}；</li>
 *   <li><b>request id 在响应体里</b>（{@code request_id} 字段），不是 {@code x-request-id}
 *       响应头。成功路径优先取响应头、缺失时回落到响应体，两边都没有就记 null——诊断表允许
 *       该列为空，但不允许写入未脱敏的原值。</li>
 * </ol>
 *
 * <p>端点从 Provider 的 {@code base-url} **只取 scheme 与 host** 拼出：目录里配的是
 * {@code .../compatible-mode/v1}，而原生路径与它同级而非父子。细节见 {@link #nativeEndpoint}。
 *
 * <p>与 {@link OpenAiCompatibleRerankProvider} 一致：适配器只发一次请求并把上游结果分类，
 * **不做 index 校验也不映射稳定 ID**——那是调度层的事，只有它握着请求里的 candidate 列表。
 */
public final class DashScopeNativeRerankProvider implements RerankProvider {

    static final String NATIVE_PATH = "/api/v1/services/rerank/text-rerank/text-rerank";

    private static final String REQUEST_ID_HEADER = "x-request-id";

    private final URI endpoint;
    private final RestClient restClient;

    /** 未知字段必须忽略，理由同 {@link OpenAiCompatibleEmbeddingProvider}：供应商加字段是常态。 */
    private final ObjectMapper json = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    public DashScopeNativeRerankProvider(URI baseUrl, String apiKey) {
        this.endpoint = nativeEndpoint(Objects.requireNonNull(baseUrl));
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
                            deployment.upstreamModel(),
                            new Input(request.query(), documents),
                            new Parameters(request.topN(), false)))
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
        String headerRequestId = IdentifierMasker.mask(
                response.getHeaders().getFirst(REQUEST_ID_HEADER));
        if (status < 200 || status >= 300) {
            Duration retryAfter = parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
            if (status == 401 || status == 403 || status == 429 || status >= 500) {
                throw ProviderFailureException.http(status, retryAfter, headerRequestId);
            }
            if (status >= 400) {
                throw new ProviderFailureException(
                        AttemptFailureType.UPSTREAM_REQUEST_REJECTED,
                        status,
                        retryAfter,
                        headerRequestId);
            }
            throw new ProviderFailureException(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, headerRequestId);
        }
        Response body;
        try {
            body = json.readValue(response.getBody(), Response.class);
        } catch (RuntimeException | java.io.IOException invalid) {
            throw new ProviderFailureException(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, headerRequestId);
        }
        if (body == null || body.output() == null || body.output().results() == null) {
            throw new ProviderFailureException(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, headerRequestId);
        }
        String requestIdMasked = headerRequestId != null
                ? headerRequestId
                : IdentifierMasker.mask(body.requestId());
        List<ProviderRankedItem> items = new ArrayList<>(body.output().results().size());
        for (Result result : body.output().results()) {
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

    /**
     * 只保留 scheme 与 authority，丢掉 base-url 上的路径——目录里的 base-url 是
     * {@code https://dashscope.aliyuncs.com/compatible-mode/v1}，而原生 rerank 挂在
     * {@code /api/v1/...} 下，两者同级而非父子。
     *
     * <p>{@code baseUrl.resolve(NATIVE_PATH)} 的结果与这里**完全相同**（实测确认：
     * {@link #NATIVE_PATH} 以 {@code /} 开头，按 RFC 3986 属绝对路径，会整体替换 base 的 path）。
     * 之所以仍然手写，是为了在 base-url 缺少 scheme 或 host 时**显式失败**：那种情况下
     * {@code resolve} 会安静地返回一个无 host 的 {@code /api/v1/...}，错误要等到发请求时
     * 才以完全无关的形式冒出来。
     */
    static URI nativeEndpoint(URI baseUrl) {
        String scheme = baseUrl.getScheme();
        String authority = baseUrl.getAuthority();
        if (scheme == null || authority == null) {
            throw new IllegalArgumentException(
                    "rerank base-url must be absolute with a host: " + baseUrl);
        }
        return URI.create(scheme + "://" + authority + NATIVE_PATH);
    }

    private record Request(String model, Input input, Parameters parameters) {
    }

    private record Input(String query, List<String> documents) {
    }

    private record Parameters(
            @JsonProperty("top_n") int topN,
            @JsonProperty("return_documents") boolean returnDocuments) {
    }

    private record Response(
            Output output,
            @JsonProperty("request_id") String requestId) {
    }

    private record Output(List<Result> results) {
    }

    private record Result(
            @JsonProperty("index") int index,
            @JsonProperty("relevance_score") double relevanceScore) {
    }
}
