package com.app.ai.provider;

import com.app.ai.model.ModelDeployment;
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
 * OpenAI-compatible {@code POST /embeddings} adapter for one batch.
 *
 * <p>Batching, ordering and dimension validation belong to the scheduling layer; this adapter only
 * encodes one request, classifies the upstream outcome and hands back indexed vectors.
 *
 * <p>It does keep a minimum gap between consecutive requests. That is not a safety belt: a 700
 * chunk document issues roughly 70 serial requests and trips the provider's account rate limit
 * within seconds, failing the whole ingestion run. The scheduling layer only reacts to a 429 after
 * it happens, so the proactive spacing has to live next to the account it protects.
 */
public final class OpenAiCompatibleEmbeddingProvider implements EmbeddingProvider {

    private static final String REQUEST_ID_HEADER = "x-request-id";

    private final URI endpoint;
    private final RestClient restClient;

    /**
     * Unknown properties must be ignored. The OpenAI-compatible contract only fixes the fields this
     * adapter reads; providers are free to add their own, and real ones do — Volcengine Ark returns
     * {@code created}, {@code id}, {@code model}, {@code usage} and a per-item {@code object}. With
     * Jackson's default the whole 200 response fails to bind and surfaces as PROTOCOL_CONTRACT,
     * which reads like a broken provider rather than an over-strict client.
     */
    private final ObjectMapper json = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Duration minRequestInterval;

    private final Object pacingLock = new Object();
    private long earliestNextRequestNanos;

    public OpenAiCompatibleEmbeddingProvider(URI baseUrl, String apiKey) {
        this(baseUrl, apiKey, Duration.ZERO);
    }

    public OpenAiCompatibleEmbeddingProvider(
            URI baseUrl, String apiKey, Duration minRequestInterval) {
        this.endpoint = embeddingsEndpoint(Objects.requireNonNull(baseUrl));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.minRequestInterval = Objects.requireNonNull(minRequestInterval);
        if (minRequestInterval.isNegative()) {
            throw new IllegalArgumentException("minRequestInterval must not be negative");
        }
        this.restClient = RestClient.builder()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .build();
        this.earliestNextRequestNanos = System.nanoTime();
    }

    @Override
    public ProviderEmbeddingResult embed(
            ModelDeployment deployment, List<String> texts, ProviderAttemptContext context) {
        Objects.requireNonNull(deployment);
        Objects.requireNonNull(texts);
        Objects.requireNonNull(context);
        if (deployment.embedding() == null) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        }
        awaitRequestSlot();
        context.lifecycle().dispatched();
        try {
            return restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new Request(
                            deployment.upstreamModel(), texts, deployment.embedding().dimensions()))
                    .exchange((request, response) -> decode(response));
        } catch (ProviderFailureException failure) {
            throw failure;
        } catch (ResourceAccessException network) {
            throw networkFailure(network);
        } catch (RestClientException unexpected) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        }
    }

    private ProviderEmbeddingResult decode(ClientHttpResponse response) throws java.io.IOException {
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
        if (body == null || body.data() == null) {
            throw new ProviderFailureException(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, requestIdMasked);
        }
        List<IndexedEmbedding> embeddings = new ArrayList<>(body.data().size());
        for (Datum datum : body.data()) {
            if (datum == null || datum.embedding() == null || datum.index() < 0) {
                throw new ProviderFailureException(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID, status, null, requestIdMasked);
            }
            embeddings.add(new IndexedEmbedding(datum.index(), datum.embedding()));
        }
        return new ProviderEmbeddingResult(embeddings, requestIdMasked);
    }

    /**
     * Restores the interrupt flag before failing: this runs on ingestion worker threads and
     * {@code shutdownNow()} stops them by interrupting, so swallowing the flag would keep the pool
     * working through the remaining batches while the application tries to shut down.
     */
    private void awaitRequestSlot() {
        if (minRequestInterval.isZero()) {
            return;
        }
        long waitNanos;
        synchronized (pacingLock) {
            long now = System.nanoTime();
            waitNanos = Math.max(0, earliestNextRequestNanos - now);
            earliestNextRequestNanos =
                    Math.max(now, earliestNextRequestNanos) + minRequestInterval.toNanos();
        }
        if (waitNanos <= 0) {
            return;
        }
        try {
            Thread.sleep(Duration.ofNanos(waitNanos).toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED);
        }
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

    private static URI embeddingsEndpoint(URI baseUrl) {
        String base = baseUrl.toString();
        return URI.create(base.endsWith("/") ? base + "embeddings" : base + "/embeddings");
    }

    /**
     * {@code dimensions} is always sent explicitly — it is the only way to make the returned width
     * match the catalog profile, and a provider that rejects it fails loudly with a 400 instead of
     * quietly returning vectors of another width.
     */
    private record Request(String model, List<String> input, Integer dimensions) {
    }

    private record Response(List<Datum> data) {
    }

    private record Datum(
            @JsonProperty("index") int index, @JsonProperty("embedding") float[] embedding) {
    }
}
