package com.app.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.app.ai.model.Capability;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.EmbeddingProfile;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.runtime.MonotonicDeadline;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingProviderTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 成功响应按index原样带回并透传脱敏请求ID() throws Exception {
        var received = new AtomicReference<Map<String, Object>>();
        start(exchange -> {
            received.set(readJson(exchange));
            exchange.getResponseHeaders().add("x-request-id", "req_1234567890abcdef");
            respond(exchange, 200, """
                    {"data":[{"index":1,"embedding":[0.5,0.5]},{"index":0,"embedding":[0.25,0.25]}]}
                    """);
        });

        ProviderEmbeddingResult result = provider().embed(deployment(), List.of("a", "b"), context());

        assertThat(result.embeddings()).extracting(IndexedEmbedding::index).containsExactly(1, 0);
        assertThat(result.embeddings().get(1).vector()).containsExactly(0.25f, 0.25f);
        assertThat(result.providerRequestIdMasked())
                .isNotNull()
                .isNotEqualTo("req_1234567890abcdef");
        assertThat(received.get()).containsEntry("model", "embed-model");
        assertThat(received.get()).containsEntry("dimensions", 2);
        assertThat(received.get()).containsEntry("input", List.of("a", "b"));
    }

    @Test
    void 限流响应映射为可等待的RateLimited并保留RetryAfter() throws Exception {
        start(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "2");
            respond(exchange, 429, "{\"error\":\"rate limited\"}");
        });

        ProviderFailureException failure = catchThrowableOfType(
                () -> provider().embed(deployment(), List.of("a"), context()),
                ProviderFailureException.class);

        assertThat(failure.failureType()).isEqualTo(AttemptFailureType.RATE_LIMITED_RESPONSE);
        assertThat(failure.httpStatus()).isEqualTo(429);
        assertThat(failure.retryAfter()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void 服务端错误与请求错误分别映射为可切换和不可恢复() throws Exception {
        start(exchange -> respond(exchange, 503, "{}"));
        assertThat(catchThrowableOfType(
                        () -> provider().embed(deployment(), List.of("a"), context()),
                        ProviderFailureException.class)
                .failureType())
                .isEqualTo(AttemptFailureType.SERVER_ERROR_RESPONSE);
        server.stop(0);

        start(exchange -> respond(exchange, 400, "{}"));
        assertThat(catchThrowableOfType(
                        () -> provider().embed(deployment(), List.of("a"), context()),
                        ProviderFailureException.class)
                .failureType())
                .isEqualTo(AttemptFailureType.UPSTREAM_REQUEST_REJECTED);
    }

    @Test
    void 畸形响应体映射为Provider协议失败() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"data\":[{\"index\":0}]}"));

        ProviderFailureException failure = catchThrowableOfType(
                () -> provider().embed(deployment(), List.of("a"), context()),
                ProviderFailureException.class);

        assertThat(failure.failureType()).isEqualTo(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
    }

    @Test
    void 相邻请求之间保持最小间隔以避免账号级限流() throws Exception {
        start(exchange -> respond(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1,1]}]}"));
        var paced = new OpenAiCompatibleEmbeddingProvider(
                baseUrl(), "test-key", Duration.ofMillis(120));

        long startedNanos = System.nanoTime();
        paced.embed(deployment(), List.of("a"), context());
        paced.embed(deployment(), List.of("b"), context());
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(110);
    }

    @Test
    void 未配置密钥直接拒绝构造() {
        assertThat(catchThrowableOfType(
                () -> new OpenAiCompatibleEmbeddingProvider(URI.create("http://127.0.0.1:1/v1"), " "),
                IllegalArgumentException.class))
                .hasMessage("apiKey must not be blank");
    }

    private OpenAiCompatibleEmbeddingProvider provider() {
        return new OpenAiCompatibleEmbeddingProvider(baseUrl(), "test-key");
    }

    private URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException failure) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        return JSON.readValue(exchange.getRequestBody().readAllBytes(), Map.class);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static ProviderAttemptContext context() {
        return new ProviderAttemptContext(
                UUID.randomUUID(),
                1,
                MonotonicDeadline.start(Duration.ofSeconds(30), null),
                CancellationToken.none());
    }

    private static ModelDeployment deployment() {
        return new ModelDeployment(
                new DeploymentId("embed-a"),
                new ProviderId("provider-a"),
                "embed-model",
                new ModelIdentity("embed-identity"),
                Set.of(Capability.EMBEDDING),
                Set.of(),
                true,
                10,
                true,
                new EmbeddingProfile(true, null, 2, 10, 512, null));
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
