package com.app.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.app.ai.model.Capability;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RerankCandidate;
import com.app.ai.model.RerankProfile;
import com.app.ai.model.ServiceTier;
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

/**
 * DashScope 原生 Rerank 适配器的契约测试。
 *
 * <p>夹具照抄 `2026-08-07` 实测到的真实响应形状（含 {@code usage} 与 {@code request_id}），
 * **不做精简**——理由同 {@link OpenAiCompatibleEmbeddingProviderTests}：夹具越干净，
 * {@code FAIL_ON_UNKNOWN_PROPERTIES} 这类问题越容易从测试里漏过去。
 */
class DashScopeNativeRerankProviderTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void 成功响应从output下取结果并按index原样带回() throws Exception {
        var received = new AtomicReference<Map<String, Object>>();
        start(exchange -> {
            received.set(readJson(exchange));
            respond(exchange, 200, """
                    {"output":{"results":[{"index":1,"relevance_score":0.859321376046141},
                                          {"index":0,"relevance_score":0.19590106588237954}]},
                     "usage":{"total_tokens":53},
                     "request_id":"247fa51c-ced6-9512-8ca4-8b8df37a679b"}
                    """);
        });

        ProviderRerankResult result = provider().rerank(
                deployment(),
                new ProviderRerankRequest(
                        "向量检索",
                        List.of(new RerankCandidate("a", "文档 A"), new RerankCandidate("b", "文档 B")),
                        2),
                context());

        assertThat(result.items()).extracting(ProviderRankedItem::index).containsExactly(1, 0);
        assertThat(result.items().get(0).relevanceScore()).isEqualTo(0.859321376046141);
    }

    /**
     * 请求体必须是 DashScope 的**嵌套**形状，不是 OpenAI 风格的平铺。
     *
     * <p>这条断言是本适配器存在的全部理由所在：平铺形状发到百炼会 404，而 404 被归一化成
     * INVALID_REQUEST，排查时极易误读成"请求参数写错了"。
     */
    @Test
    void 请求体使用input与parameters嵌套结构() throws Exception {
        var received = new AtomicReference<Map<String, Object>>();
        start(exchange -> {
            received.set(readJson(exchange));
            respond(exchange, 200, """
                    {"output":{"results":[{"index":0,"relevance_score":0.5}]},"request_id":"r-1"}
                    """);
        });

        provider().rerank(
                deployment(),
                new ProviderRerankRequest(
                        "查询词", List.of(new RerankCandidate("a", "文档 A")), 1),
                context());

        assertThat(received.get()).containsEntry("model", "rerank-model");
        assertThat(received.get()).doesNotContainKeys("query", "documents", "top_n");

        @SuppressWarnings("unchecked")
        Map<String, Object> input = (Map<String, Object>) received.get().get("input");
        assertThat(input).containsEntry("query", "查询词");
        assertThat(input).containsEntry("documents", List.of("文档 A"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parameters = (Map<String, Object>) received.get().get("parameters");
        assertThat(parameters).containsEntry("top_n", 1);
        assertThat(parameters).containsEntry("return_documents", false);
    }

    /** 原生协议把 request id 放在响应体，不是 {@code x-request-id} 响应头。 */
    @Test
    void 响应体里的requestId被脱敏后带回() throws Exception {
        start(exchange -> respond(exchange, 200, """
                {"output":{"results":[{"index":0,"relevance_score":0.5}]},
                 "request_id":"247fa51c-ced6-9512-8ca4-8b8df37a679b"}
                """));

        ProviderRerankResult result = provider().rerank(
                deployment(),
                new ProviderRerankRequest("q", List.of(new RerankCandidate("a", "A")), 1),
                context());

        assertThat(result.providerRequestIdMasked())
                .isNotNull()
                .isNotEqualTo("247fa51c-ced6-9512-8ca4-8b8df37a679b")
                .isEqualTo("247f****679b");
    }

    /** 结果不在顶层 {@code results} 而在 {@code output} 下；缺 output 属协议不符，不能当空结果放过。 */
    @Test
    void 缺少output时判协议不符() throws Exception {
        start(exchange -> respond(exchange, 200,
                """
                {"results":[{"index":0,"relevance_score":0.5}],"request_id":"r-1"}
                """));

        ProviderFailureException failure = catchThrowableOfType(
                () -> provider().rerank(
                        deployment(),
                        new ProviderRerankRequest("q", List.of(new RerankCandidate("a", "A")), 1),
                        context()),
                ProviderFailureException.class);

        assertThat(failure.failureType()).isEqualTo(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
    }

    @Test
    void 上游404归类为请求被拒绝() throws Exception {
        start(exchange -> respond(exchange, 404, ""));

        ProviderFailureException failure = catchThrowableOfType(
                () -> provider().rerank(
                        deployment(),
                        new ProviderRerankRequest("q", List.of(new RerankCandidate("a", "A")), 1),
                        context()),
                ProviderFailureException.class);

        assertThat(failure.failureType()).isEqualTo(AttemptFailureType.UPSTREAM_REQUEST_REJECTED);
    }

    /**
     * 端点必须丢掉 base-url 的路径，只保留 scheme 与 host；专属实例域名同样成立。
     *
     * <p>注意这条**不是**在证明"必须手写、不能用 resolve"：实测 {@code resolve} 结果与手写
     * 完全一致。手写的唯一收益是相对 base-url 会显式失败，那由下一条用例守。
     */
    @Test
    void 端点只取scheme与host并丢弃baseUrl路径() {
        assertThat(DashScopeNativeRerankProvider.nativeEndpoint(
                URI.create("https://dashscope.aliyuncs.com/compatible-mode/v1")))
                .isEqualTo(URI.create(
                        "https://dashscope.aliyuncs.com" + DashScopeNativeRerankProvider.NATIVE_PATH));

        assertThat(DashScopeNativeRerankProvider.nativeEndpoint(
                URI.create("https://llm-abc123.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")))
                .isEqualTo(URI.create("https://llm-abc123.cn-beijing.maas.aliyuncs.com"
                        + DashScopeNativeRerankProvider.NATIVE_PATH));
    }

    @Test
    void 相对baseUrl直接拒绝() {
        assertThatThrownBy(() -> DashScopeNativeRerankProvider.nativeEndpoint(URI.create("/v1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DashScopeNativeRerankProvider provider() {
        return new DashScopeNativeRerankProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()), "test-key");
    }

    private void start(Handler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(DashScopeNativeRerankProvider.NATIVE_PATH, exchange -> {
            try {
                handler.handle(exchange);
            } catch (IOException failure) {
                exchange.sendResponseHeaders(500, -1);
                exchange.close();
            }
        });
        server.start();
    }

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
                new DeploymentId("rerank-a"),
                new ProviderId("provider-a"),
                "rerank-model",
                new ModelIdentity("rerank-identity"),
                Set.of(Capability.RERANK),
                Set.of(ServiceTier.BALANCED),
                true,
                10,
                true,
                null,
                Set.of(),
                new RerankProfile(100, "dashscope-native"));
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }
}
