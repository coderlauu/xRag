package com.app.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.app.ai.model.AssistantMessage;
import com.app.ai.model.Capability;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.CancellationRegistration;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.ToolCall;
import com.app.ai.model.ToolDefinition;
import com.app.ai.model.UserMessage;
import com.app.ai.runtime.MonotonicDeadline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleChatProviderTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void syn01和30_请求形状与响应归一不泄漏供应商原始字段() throws Exception {
        var capturedBody = new AtomicReference<String>();
        var capturedAuthorization = new AtomicReference<String>();
        HttpServer server = server(exchange -> {
            capturedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = """
                    {
                      "choices": [{
                        "message": {
                          "role": "assistant",
                          "content": null,
                          "tool_calls": [{
                            "id": "call-1",
                            "type": "function",
                            "function": {"name": "search", "arguments": "{\\"q\\":\\"xrag\\"}"}
                          }]
                        },
                        "finish_reason": "future_reason"
                      }],
                      "usage": {"prompt_tokens": 9}
                    }
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("x-request-id", "req_provider_secret_9af2");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var dispatches = new AtomicInteger();

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key")) {
            var result = provider.complete(
                    deployment(),
                    new ProviderChatRequest(
                            List.of(new UserMessage("请搜索")),
                            List.of(new ToolDefinition("search", "搜索", "{\"type\":\"object\"}"))),
                    new ProviderAttemptContext(
                            UUID.randomUUID(),
                            1,
                            () -> Duration.ofSeconds(5),
                            CancellationToken.none(),
                            dispatches::incrementAndGet));

            assertThat(result.message()).isEqualTo(new AssistantMessage(
                    null,
                    null,
                    List.of(new ToolCall("call-1", "search", "{\"q\":\"xrag\"}"))));
            assertThat(result.finishReason()).isEqualTo(ChatFinishReason.UNKNOWN);
            assertThat(result.usage()).isNull();
            assertThat(result.providerRequestIdMasked()).isEqualTo("req_****9af2");
            assertThat(dispatches).hasValue(1);
        } finally {
            server.stop(0);
        }

        assertThat(capturedAuthorization.get()).isEqualTo("Bearer test-api-key");
        JsonNode request = JSON.readTree(capturedBody.get());
        assertThat(request.path("model").asText()).isEqualTo("chat-model");
        assertThat(request.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(request.path("messages").get(0).path("content").asText()).isEqualTo("请搜索");
        assertThat(request.path("tools").get(0).path("type").asText()).isEqualTo("function");
        assertThat(request.path("tools").get(0).path("function").path("parameters").isObject()).isTrue();
    }

    @Test
    void syn12_非法RetryAfter归一为缺失而不抛解析异常() throws Exception {
        HttpServer server = server(exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Retry-After", "not-a-duration");
            exchange.sendResponseHeaders(429, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key")) {
            var failure = catchThrowableOfType(
                    ProviderFailureException.class,
                    () -> provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofSeconds(2), null),
                                    CancellationToken.none(),
                                    () -> { })));

            assertThat(failure.failureType()).isEqualTo(AttemptFailureType.RATE_LIMITED_RESPONSE);
            assertThat(failure.httpStatus()).isEqualTo(429);
            assertThat(failure.retryAfter()).isNull();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syn26_实际Provider的200畸形响应归一为协议失败() throws Exception {
        HttpServer server = server(exchange -> {
            byte[] body = "{\"choices\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key")) {
            var failure = catchThrowableOfType(
                    ProviderFailureException.class,
                    () -> provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofSeconds(2), null),
                                    CancellationToken.none(),
                                    () -> { })));

            assertThat(failure.failureType())
                    .isEqualTo(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syn21_剩余总Deadline收紧传输超时并归类为总截止时间() throws Exception {
        var bounded = new SyncHttpTimeouts(
                        Duration.ofSeconds(3),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(1))
                .boundedBy(Duration.ofMillis(700));
        assertThat(bounded).isEqualTo(new SyncHttpTimeouts(
                Duration.ofMillis(700), Duration.ofMillis(700), Duration.ofMillis(700)));

        HttpServer server = server(exchange -> {
            Thread.sleep(2_000);
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        var dispatches = new AtomicInteger();
        long started = System.nanoTime();

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key")) {
            var failure = catchThrowableOfType(
                    ProviderFailureException.class,
                    () -> provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofMillis(250), null),
                                    CancellationToken.none(),
                                    dispatches::incrementAndGet)));

            assertThat(failure.failureType()).isEqualTo(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED);
            assertThat(dispatches).hasValue(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syn22_取消会主动终止在途请求且只注销一次registration() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var releaseServer = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            requestStarted.countDown();
            releaseServer.await(5, TimeUnit.SECONDS);
            exchange.close();
        });
        var token = new TestCancellationToken();
        var dispatches = new AtomicInteger();
        var caller = Executors.newSingleThreadExecutor();

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key")) {
            var future = caller.submit(() -> {
                try {
                    provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofSeconds(3), null),
                                    token,
                                    dispatches::incrementAndGet));
                    return null;
                } catch (ProviderFailureException failure) {
                    return failure;
                }
            });
            assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();

            token.cancel();
            ProviderFailureException failure = future.get(1, TimeUnit.SECONDS);

            assertThat(failure.failureType()).isEqualTo(AttemptFailureType.CALLER_CANCELED);
            assertThat(dispatches).hasValue(1);
            assertThat(token.registrationCloseCount()).isEqualTo(1);
            assertThat(token.listenerCount()).isZero();
        } finally {
            releaseServer.countDown();
            caller.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void syn23_取消与正常完成竞态只产生一个终态() throws Exception {
        var requestStarted = new CountDownLatch(1);
        var releaseResponse = new CountDownLatch(1);
        HttpServer server = server(exchange -> {
            requestStarted.countDown();
            releaseResponse.await(2, TimeUnit.SECONDS);
            byte[] body = """
                    {"choices":[{"message":{"content":"ok"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var token = new TestCancellationToken();
        var dispatches = new AtomicInteger();
        var executor = Executors.newFixedThreadPool(3);

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key")) {
            var terminal = executor.submit(() -> {
                try {
                    return provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofSeconds(2), null),
                                    token,
                                    dispatches::incrementAndGet));
                } catch (ProviderFailureException failure) {
                    return failure;
                }
            });
            assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
            var race = new CountDownLatch(1);
            executor.submit(() -> {
                race.await();
                token.cancel();
                return null;
            });
            executor.submit(() -> {
                race.await();
                releaseResponse.countDown();
                return null;
            });

            race.countDown();
            Object outcome = terminal.get(1, TimeUnit.SECONDS);

            assertThat(outcome instanceof ProviderChatResult
                            || outcome instanceof ProviderFailureException failure
                                    && failure.failureType() == AttemptFailureType.CALLER_CANCELED)
                    .isTrue();
            assertThat(dispatches).hasValue(1);
            assertThat(token.registrationCloseCount()).isEqualTo(1);
            assertThat(token.listenerCount()).isZero();
        } finally {
            releaseResponse.countDown();
            executor.shutdownNow();
            server.stop(0);
        }
    }

    @Test
    void syn25_预取消仍经过注册并精确清理且不发Http() throws Exception {
        var received = new AtomicInteger();
        HttpServer server = server(exchange -> {
            received.incrementAndGet();
            exchange.close();
        });
        var token = new TestCancellationToken();
        token.cancel();
        var dispatches = new AtomicInteger();

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key")) {
            var failure = catchThrowableOfType(
                    ProviderFailureException.class,
                    () -> provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofSeconds(3), null),
                                    token,
                                    dispatches::incrementAndGet)));

            assertThat(failure.failureType()).isEqualTo(AttemptFailureType.CALLER_CANCELED);
            assertThat(dispatches).hasValue(0);
            assertThat(received).hasValue(0);
            assertThat(token.registrationCloseCount()).isEqualTo(1);
            assertThat(token.listenerCount()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syn24_已收headers后body停滞归类为响应体超时() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            Thread.sleep(2_000);
            exchange.close();
        });
        var dispatches = new AtomicInteger();
        long started = System.nanoTime();

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key",
                new SyncHttpTimeouts(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(150)))) {
            var failure = catchThrowableOfType(
                    ProviderFailureException.class,
                    () -> provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofSeconds(2), null),
                                    CancellationToken.none(),
                                    dispatches::incrementAndGet)));

            assertThat(failure.failureType()).isEqualTo(AttemptFailureType.RESPONSE_BODY_TIMEOUT);
            assertThat(dispatches).hasValue(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syn27_持续滴入body字节仍被绝对总Deadline终止() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            for (int index = 0; index < 30; index++) {
                Thread.sleep(40);
                exchange.getResponseBody().write(' ');
                exchange.getResponseBody().flush();
            }
            exchange.close();
        });
        var dispatches = new AtomicInteger();
        long started = System.nanoTime();

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key",
                new SyncHttpTimeouts(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(1),
                        Duration.ofMillis(120)))) {
            var failure = catchThrowableOfType(
                    ProviderFailureException.class,
                    () -> provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofMillis(350), null),
                                    CancellationToken.none(),
                                    dispatches::incrementAndGet)));

            assertThat(failure.failureType()).isEqualTo(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED);
            assertThat(dispatches).hasValue(1);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void syn15_同步线程已中断时不发Http并保留中断标志() throws Exception {
        var received = new AtomicInteger();
        HttpServer server = server(exchange -> {
            received.incrementAndGet();
            byte[] body = """
                    {"choices":[{"message":{"content":"unexpected"},"finish_reason":"stop"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        var dispatches = new AtomicInteger();

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1"),
                "test-api-key")) {
            Thread.currentThread().interrupt();
            var failure = catchThrowableOfType(
                    ProviderFailureException.class,
                    () -> provider.complete(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            new ProviderAttemptContext(
                                    UUID.randomUUID(),
                                    1,
                                    MonotonicDeadline.start(Duration.ofSeconds(2), null),
                                    CancellationToken.none(),
                                    dispatches::incrementAndGet)));

            assertThat(failure.failureType()).isEqualTo(AttemptFailureType.CALLER_CANCELED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(dispatches).hasValue(0);
            assertThat(received).hasValue(0);
        } finally {
            Thread.interrupted();
            server.stop(0);
        }
    }

    private static HttpServer server(ThrowingHandler handler) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                handler.handle(exchange);
            } catch (Exception failure) {
                exchange.close();
            }
        });
        server.setExecutor(Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "chat-provider-stub");
            thread.setDaemon(true);
            return thread;
        }));
        server.start();
        return server;
    }

    private static ModelDeployment deployment() {
        return new ModelDeployment(
                new DeploymentId("chat-a"),
                new ProviderId("provider-a"),
                "chat-model",
                new ModelIdentity("chat-model-v1"),
                Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED),
                true,
                10,
                true,
                null);
    }

    @FunctionalInterface
    private interface ThrowingHandler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class TestCancellationToken implements CancellationToken {

        private boolean canceled;
        private Runnable listener;
        private int registrationCloseCount;

        @Override
        public synchronized boolean isCancelled() {
            return canceled;
        }

        @Override
        public synchronized CancellationRegistration onCancel(Runnable newListener) {
            if (canceled) {
                newListener.run();
                return new TestRegistration(null);
            }
            listener = newListener;
            return new TestRegistration(newListener);
        }

        synchronized void cancel() {
            if (canceled) {
                return;
            }
            canceled = true;
            Runnable current = listener;
            if (current != null) {
                current.run();
            }
        }

        synchronized int registrationCloseCount() {
            return registrationCloseCount;
        }

        synchronized int listenerCount() {
            return listener == null ? 0 : 1;
        }

        private final class TestRegistration implements CancellationRegistration {

            private final Runnable registered;
            private boolean closed;

            private TestRegistration(Runnable registered) {
                this.registered = registered;
            }

            @Override
            public void close() {
                synchronized (TestCancellationToken.this) {
                    if (closed) {
                        return;
                    }
                    closed = true;
                    registrationCloseCount++;
                    if (listener == registered) {
                        listener = null;
                    }
                }
            }
        }
    }
}
