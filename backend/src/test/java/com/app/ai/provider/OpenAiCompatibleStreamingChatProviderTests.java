package com.app.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.UserMessage;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

class OpenAiCompatibleStreamingChatProviderTests {

    @Test
    void str01_Lf分隔的Sse在订阅后才发Http并归一文本与完成事件() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(Flux.just(
                "data: {\"choices\":[{\"delta\":{\"content\":\"你\"},\"finish_reason\":\"stop\"}]}\n\n",
                "data: [DONE]\n\n"), requests);
        var dispatches = new AtomicInteger();
        var connects = new AtomicInteger();
        var headers = new AtomicInteger();
        AttemptLifecycle lifecycle = new AttemptLifecycle() {
            @Override
            public void dispatched() {
                dispatches.incrementAndGet();
            }

            @Override
            public void connected() {
                connects.incrementAndGet();
            }

            @Override
            public void responseHeadersReceived() {
                headers.incrementAndGet();
            }
        };

        try (var provider = provider(server)) {
            Flux<ProviderChatEvent> events = provider.stream(
                    deployment(),
                    new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                    context(lifecycle));

            assertThat(requests).hasValue(0);
            assertThat(events.collectList().block(Duration.ofSeconds(2)))
                    .containsExactly(
                            new ProviderChatEvent.TextDelta("你"),
                            new ProviderChatEvent.Completed(ChatFinishReason.STOP, null));
            assertThat(requests).hasValue(1);
            assertThat(dispatches).hasValue(1);
            assertThat(connects).hasValue(1);
            assertThat(headers).hasValue(1);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void str02至04_Crlf单Cr多行Data与心跳都按Sse语义处理() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(Flux.just(
                ": keep-alive\r\r",
                "data: {\"choices\":[{\"delta\":\r",
                "data: {\"content\":\"甲\"},\"finish_reason\":null}]}\r\n\r\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"乙\"},"
                        + "\"finish_reason\":\"stop\"}]}\r\r",
                "data: [DONE]\r\r"), requests);

        try (var provider = provider(server)) {
            assertThat(provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .collectList()
                    .block(Duration.ofSeconds(2)))
                    .satisfiesExactly(
                            event -> assertThat(event)
                                    .isEqualTo(new ProviderChatEvent.ProbeSignal(0)),
                            event -> assertThat(event)
                                    .isEqualTo(new ProviderChatEvent.TextDelta("甲")),
                            event -> assertThat(event)
                                    .isEqualTo(new ProviderChatEvent.TextDelta("乙")),
                            event -> assertThat(event)
                                    .isEqualTo(new ProviderChatEvent.Completed(
                                            ChatFinishReason.STOP, null)));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void str05与06_Json跨TcpChunk且同一Chunk含多个事件时逐事件输出() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(Flux.just(
                "data: {\"choices\":[{\"delta\":{\"content\":\"跨",
                "片\"},\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"同\"},"
                        + "\"finish_reason\":null}]}\n\n"
                        + "data: {\"choices\":[{\"delta\":{\"content\":\"块\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\n"
                        + "data: [DONE]\n\n"), requests);

        try (var provider = provider(server)) {
            assertThat(provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .collectList()
                    .block(Duration.ofSeconds(2)))
                    .filteredOn(event -> !(event instanceof ProviderChatEvent.ProbeSignal))
                    .containsExactly(
                            new ProviderChatEvent.TextDelta("跨片"),
                            new ProviderChatEvent.TextDelta("同"),
                            new ProviderChatEvent.TextDelta("块"),
                            new ProviderChatEvent.Completed(ChatFinishReason.STOP, null));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void 工具拒答Usage与显式Done都归一为Provider事件() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(Flux.just(
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"id\":\"call-1\",\"type\":\"function\",\"function\":{"
                        + "\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\"}}]},"
                        + "\"finish_reason\":null}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"x\\\"}\"}}]},"
                        + "\"finish_reason\":null}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"refusal\":\"不能执行\"},"
                        + "\"finish_reason\":\"tool_calls\"}]}\n\n",
                "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,"
                        + "\"completion_tokens\":2,\"total_tokens\":5}}\n\n",
                "event: done\n\n"), requests);

        try (var provider = provider(server)) {
            assertThat(provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .collectList()
                    .block(Duration.ofSeconds(2)))
                    .filteredOn(event -> !(event instanceof ProviderChatEvent.ProbeSignal))
                    .containsExactly(
                            new ProviderChatEvent.ToolCallDelta(
                                    "call-1", 0, "search", "{\"q\":"),
                            new ProviderChatEvent.ToolCallDelta(
                                    "call-1", 0, "search", "\"x\"}"),
                            new ProviderChatEvent.RefusalDelta("不能执行"),
                            new ProviderChatEvent.Completed(
                                    ChatFinishReason.TOOL_CALLS, new TokenUsage(3, 2, 5)));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void str07_未知合法事件跳过且不解析其Data() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(Flux.just(
                "event: vendor.metadata\ndata: 这不是 JSON，也不能越过 seam\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"继续\"},"
                        + "\"finish_reason\":\"stop\"}]}\n\n",
                "data: [DONE]\n\n"), requests);

        try (var provider = provider(server)) {
            assertThat(provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .collectList()
                    .block(Duration.ofSeconds(2)))
                    .satisfiesExactly(
                            event -> assertThat(event)
                                    .isInstanceOf(ProviderChatEvent.ProbeSignal.class),
                            event -> assertThat(event)
                                    .isEqualTo(new ProviderChatEvent.TextDelta("继续")),
                            event -> assertThat(event)
                                    .isEqualTo(new ProviderChatEvent.Completed(
                                            ChatFinishReason.STOP, null)));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void 合法Error事件归一且不泄露原始Body() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(Flux.just(
                "event: error\ndata: {\"error\":{\"message\":\"raw-secret-marker\"}}\n\n"),
                requests);

        try (var provider = provider(server)) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .collectList()
                    .block(Duration.ofSeconds(2)));

            assertThat(failure).isInstanceOf(ProviderFailureException.class);
            assertThat(((ProviderFailureException) failure).failureType())
                    .isEqualTo(AttemptFailureType.UPSTREAM_ERROR_EVENT);
            assertThat(failure).hasMessageNotContaining("raw-secret-marker");
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void str08_畸形Json归一为Provider协议失败() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(Flux.just("data: {not-json}\n\n"), requests);

        try (var provider = provider(server)) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .blockLast(Duration.ofSeconds(2)));

            assertThat(failure).isInstanceOfSatisfying(ProviderFailureException.class,
                    typed -> assertThat(typed.failureType())
                            .isEqualTo(AttemptFailureType.PROVIDER_RESPONSE_INVALID));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void str09_单个SseEvent超过64KiB时有界失败() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(
                Flux.just("data: " + "x".repeat(65 * 1024) + "\n\n"), requests);

        try (var provider = provider(server)) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .blockLast(Duration.ofSeconds(2)));

            assertThat(failure).isInstanceOfSatisfying(ProviderFailureException.class,
                    typed -> assertThat(typed.failureType())
                            .isEqualTo(AttemptFailureType.PROVIDER_RESPONSE_INVALID));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void 未收到Done就结束的上游流归一为中断() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = server(Flux.just(
                "data: {\"choices\":[{\"delta\":{\"content\":\"部分\"},"
                        + "\"finish_reason\":null}]}\n\n"), requests);

        try (var provider = provider(server)) {
            List<ProviderChatEvent> received = new java.util.ArrayList<>();
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .doOnNext(received::add)
                    .blockLast(Duration.ofSeconds(2)));

            assertThat(received).containsExactly(new ProviderChatEvent.TextDelta("部分"));
            assertThat(failure).isInstanceOfSatisfying(ProviderFailureException.class,
                    typed -> assertThat(typed.failureType())
                            .isEqualTo(AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void 响应Body中途断开也归一为上游流中断() throws Exception {
        var requests = new AtomicInteger();
        String event = "data: {\"choices\":[{\"delta\":{\"content\":\"部分\"},"
                + "\"finish_reason\":null}]}\n\n";
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) -> {
                    requests.incrementAndGet();
                    Flux<String> interruptedBody = Flux.just(event)
                            .doOnNext(ignored -> response.withConnection(connection ->
                                    connection.channel().eventLoop().schedule(
                                            connection::dispose, 50, TimeUnit.MILLISECONDS)))
                            .concatWith(Flux.never());
                    return response.header("Content-Type", "text/event-stream")
                            .sendString(interruptedBody);
                }))
                .bindNow();

        try (var provider = provider(server)) {
            List<ProviderChatEvent> received = new java.util.ArrayList<>();
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .doOnNext(received::add)
                    .blockLast(Duration.ofSeconds(2)));

            assertThat(received).containsExactly(new ProviderChatEvent.TextDelta("部分"));
            assertThat(failure).isInstanceOfSatisfying(ProviderFailureException.class,
                    typed -> assertThat(typed.failureType())
                            .isEqualTo(AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED));
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void 取消Provider订阅会关闭上游响应订阅() throws Exception {
        var requests = new AtomicInteger();
        var bodySubscribed = new CountDownLatch(1);
        var bodyCancelled = new CountDownLatch(1);
        Flux<String> body = Flux.concat(
                        Flux.just(": keep-alive\n\n"),
                        Flux.<String>never())
                .doOnSubscribe(ignored -> bodySubscribed.countDown())
                .doOnCancel(bodyCancelled::countDown);
        DisposableServer server = server(body, requests);

        try (var provider = provider(server)) {
            Disposable subscription = provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .subscribe();

            assertThat(bodySubscribed.await(2, TimeUnit.SECONDS)).isTrue();
            subscription.dispose();
            assertThat(bodyCancelled.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(requests).hasValue(1);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void 非二百响应沿用统一Http失败分类且不泄露响应Body() throws Exception {
        var requests = new AtomicInteger();
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) -> {
                    requests.incrementAndGet();
                    return response.status(429)
                            .header("Retry-After", "3")
                            .header("x-request-id", "request-123456")
                            .sendString(Flux.just("raw-secret-marker"));
                }))
                .bindNow();

        try (var provider = provider(server)) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .blockLast(Duration.ofSeconds(2)));

            assertThat(failure).isInstanceOfSatisfying(ProviderFailureException.class, typed -> {
                assertThat(typed.failureType()).isEqualTo(AttemptFailureType.RATE_LIMITED_RESPONSE);
                assertThat(typed.httpStatus()).isEqualTo(429);
                assertThat(typed.retryAfter()).isEqualTo(Duration.ofSeconds(3));
                assertThat(typed.providerRequestIdMasked()).isEqualTo("requ****3456");
                assertThat(typed).hasMessageNotContaining("raw-secret-marker");
            });
            assertThat(requests).hasValue(1);
        } finally {
            server.disposeNow();
        }
    }

    @Test
    void 建连失败在Provider边界归一为ConnectFailed() throws Exception {
        DisposableServer server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .bindNow();
        int closedPort = server.port();
        server.disposeNow();

        try (var provider = new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + closedPort + "/v1"),
                "test-api-key")) {
            Throwable failure = org.assertj.core.api.Assertions.catchThrowable(() -> provider.stream(
                            deployment(),
                            new ProviderChatRequest(List.of(new UserMessage("你好")), List.of()),
                            context(new AtomicInteger()))
                    .blockLast(Duration.ofSeconds(2)));

            assertThat(failure).isInstanceOfSatisfying(ProviderFailureException.class,
                    typed -> assertThat(typed.failureType())
                            .isEqualTo(AttemptFailureType.CONNECT_FAILED));
        }
    }

    private static DisposableServer server(Flux<String> body, AtomicInteger requests) {
        return HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes.post("/v1/chat/completions", (request, response) -> {
                    requests.incrementAndGet();
                    return response.header("Content-Type", "text/event-stream")
                            .sendString(body);
                }))
                .bindNow();
    }

    private static OpenAiCompatibleChatProvider provider(DisposableServer server) {
        return new OpenAiCompatibleChatProvider(
                URI.create("http://127.0.0.1:" + server.port() + "/v1"),
                "test-api-key");
    }

    private static ProviderAttemptContext context(AtomicInteger dispatches) {
        return context(dispatches::incrementAndGet);
    }

    private static ProviderAttemptContext context(AttemptLifecycle lifecycle) {
        return new ProviderAttemptContext(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                1,
                () -> Duration.ofSeconds(5),
                CallOptions.defaults().cancellation(),
                lifecycle);
    }

    private static ModelDeployment deployment() {
        return new ModelDeployment(
                new DeploymentId("chat-a"),
                new ProviderId("provider-a"),
                "chat-model",
                new ModelIdentity("chat-model-v1"),
                Set.of(Capability.STREAM_CHAT),
                Set.of(ServiceTier.BALANCED),
                true,
                10,
                true,
                null);
    }

}
