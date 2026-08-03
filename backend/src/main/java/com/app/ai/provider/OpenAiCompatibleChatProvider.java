package com.app.ai.provider;

import com.app.ai.model.AssistantMessage;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatMessage;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.SystemMessage;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.ToolCall;
import com.app.ai.model.ToolDefinition;
import com.app.ai.model.ToolResultMessage;
import com.app.ai.model.UserMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

public final class OpenAiCompatibleChatProvider
        implements ChatProvider, StreamingChatProvider, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleChatProvider.class);
    private static final String REQUEST_ID_HEADER = "x-request-id";
    private static final int MAX_SSE_EVENT_BYTES = 64 * 1024;
    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final URI endpoint;
    private final String apiKey;
    private final ObjectMapper json;
    private final CloseableHttpClient httpClient;
    private final ScheduledExecutorService deadlineScheduler;
    private final SyncHttpTimeouts timeouts;
    private final ConnectionProvider streamingConnections;
    private final WebClient streamingClient;

    public OpenAiCompatibleChatProvider(URI baseUrl, String apiKey) {
        this(baseUrl, apiKey, SyncHttpTimeouts.defaults());
    }

    public OpenAiCompatibleChatProvider(
            URI baseUrl, String apiKey, SyncHttpTimeouts timeouts) {
        this.endpoint = chatEndpoint(Objects.requireNonNull(baseUrl));
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey must not be blank");
        }
        this.apiKey = apiKey;
        this.timeouts = Objects.requireNonNull(timeouts);
        this.json = new ObjectMapper();
        this.httpClient = HttpClients.custom().build();
        this.deadlineScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "ai-sync-http-deadline");
            thread.setDaemon(true);
            return thread;
        });
        this.streamingConnections = ConnectionProvider.create("ai-stream-http");
        HttpClient streamingHttpClient = HttpClient.create(streamingConnections);
        this.streamingClient = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(streamingHttpClient))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs()
                                .maxInMemorySize(MAX_SSE_EVENT_BYTES))
                        .build())
                .build();
    }

    @Override
    public Flux<ProviderChatEvent> stream(
            ModelDeployment deployment,
            ProviderChatRequest request,
            ProviderAttemptContext context) {
        Objects.requireNonNull(deployment);
        Objects.requireNonNull(request);
        Objects.requireNonNull(context);
        return Flux.defer(() -> {
            Duration remaining = context.deadline().remaining();
            if (remaining.isZero() || remaining.isNegative()) {
                return Flux.error(ProviderFailureException.of(
                        AttemptFailureType.TOTAL_DEADLINE_EXCEEDED));
            }
            if (context.cancellation().isCancelled()) {
                return Flux.error(ProviderFailureException.of(
                        AttemptFailureType.CALLER_CANCELED));
            }
            context.lifecycle().dispatched();
            var state = new StreamDecodeState();
            return streamingClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .header("Authorization", "Bearer " + apiKey)
                    .bodyValue(streamRequestBody(deployment, request))
                    .exchangeToFlux(this::decodeStreamingResponse)
                    .concatMap(event -> Flux.fromIterable(state.decode(event)))
                    .concatWith(Flux.defer(state::endOfInput))
                    .takeUntil(ProviderChatEvent.Completed.class::isInstance)
                    .onErrorMap(this::normalizedStreamFailure);
        });
    }

    @Override
    public ProviderChatResult complete(
            ModelDeployment deployment,
            ProviderChatRequest request,
            ProviderAttemptContext context) {
        Objects.requireNonNull(deployment);
        Objects.requireNonNull(request);
        Objects.requireNonNull(context);
        Duration remaining = context.deadline().remaining();
        if (remaining.isZero() || remaining.isNegative()) {
            throw ProviderFailureException.of(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED);
        }
        var control = new AttemptControl();
        var registration = context.cancellation().onCancel(control::callerCanceled);
        ScheduledFuture<?> deadlineTask = null;
        var headersReceived = new AtomicBoolean();
        try {
            if (Thread.currentThread().isInterrupted()) {
                control.callerCanceled();
            }
            if (context.cancellation().isCancelled()) {
                control.callerCanceled();
            }
            if (control.hasFailure()) {
                throw control.failure();
            }
            deadlineTask = deadlineScheduler.schedule(
                    control::totalDeadline,
                    remaining.toNanos(),
                    TimeUnit.NANOSECONDS);
            var requestFactory = new LifecycleRequestFactory(httpClient, context.lifecycle(), control);
            SyncHttpTimeouts attemptTimeouts = timeouts.boundedBy(remaining);
            RequestConfig requestConfig = RequestConfig.custom()
                    .setConnectionRequestTimeout(timeout(attemptTimeouts.connectionRequest()))
                    .setConnectTimeout(timeout(attemptTimeouts.connect()))
                    .setResponseTimeout(timeout(attemptTimeouts.response()))
                    .build();
            requestFactory.setHttpContextFactory((method, uri) -> {
                HttpClientContext httpContext = HttpClientContext.create();
                httpContext.setRequestConfig(requestConfig);
                return httpContext;
            });
            RestClient restClient = RestClient.builder().requestFactory(requestFactory).build();
            ProviderChatResult result = restClient.post()
                    .uri(endpoint)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + apiKey)
                    .body(requestBody(deployment, request))
                    .exchange((outbound, response) -> decode(response, headersReceived));
            if (!control.completed()) {
                throw control.failure();
            }
            return result;
        } catch (ProviderFailureException failure) {
            if (control.hasFailure()) {
                throw control.failure();
            }
            throw failure;
        } catch (RestClientException failure) {
            if (context.deadline().isExpired()) {
                control.totalDeadline();
            }
            if (control.hasFailure()) {
                throw control.failure();
            }
            throw ProviderFailureException.of(transportFailure(failure, headersReceived.get()));
        } catch (CancellationException failure) {
            control.callerCanceled();
            Thread.currentThread().interrupt();
            throw control.failure();
        } finally {
            if (deadlineTask != null) {
                deadlineTask.cancel(false);
            }
            registration.close();
        }
    }

    private ProviderChatResult decode(
            ClientHttpResponse response, AtomicBoolean headersReceived) throws IOException {
        headersReceived.set(true);
        int status = response.getStatusCode().value();
        String requestIdMasked = IdentifierMasker.mask(response.getHeaders().getFirst(REQUEST_ID_HEADER));
        if (status < 200 || status >= 300) {
            Duration retryAfter = parseRetryAfter(response.getHeaders().getFirst("Retry-After"));
            if (status == 401 || status == 403 || status == 429 || status >= 500) {
                throw ProviderFailureException.http(status, retryAfter, requestIdMasked);
            }
            if (status >= 400 && status < 500) {
                throw new ProviderFailureException(
                        AttemptFailureType.UPSTREAM_REQUEST_REJECTED,
                        status,
                        retryAfter,
                        requestIdMasked);
            }
            throw new ProviderFailureException(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID,
                    status,
                    null,
                    requestIdMasked);
        }
        try {
            JsonNode root = json.readTree(response.getBody());
            return response(root, requestIdMasked);
        } catch (JsonProcessingException invalidJson) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        }
    }

    private ProviderChatResult response(JsonNode root, String requestIdMasked) {
        try {
            JsonNode choice = requiredArrayItem(root, "choices", 0);
            JsonNode message = requiredObject(choice, "message");
            String content = optionalText(message, "content");
            String refusal = optionalText(message, "refusal");
            List<ToolCall> toolCalls = toolCalls(message.path("tool_calls"));
            var assistant = new AssistantMessage(content, refusal, toolCalls);
            ChatFinishReason finishReason = finishReason(optionalText(choice, "finish_reason"));
            TokenUsage usage = usage(root.path("usage"));
            return new ProviderChatResult(assistant, finishReason, usage, requestIdMasked);
        } catch (IllegalArgumentException invalidShape) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        }
    }

    private Map<String, Object> requestBody(
            ModelDeployment deployment, ProviderChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", deployment.upstreamModel());
        body.put("messages", request.messages().stream().map(this::message).toList());
        if (!request.tools().isEmpty()) {
            body.put("tools", request.tools().stream().map(this::tool).toList());
        }
        return body;
    }

    private Map<String, Object> streamRequestBody(
            ModelDeployment deployment, ProviderChatRequest request) {
        Map<String, Object> body = new LinkedHashMap<>(requestBody(deployment, request));
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        return body;
    }

    private RuntimeException normalizedStreamFailure(Throwable failure) {
        if (failure instanceof ProviderFailureException providerFailure) {
            return providerFailure;
        }
        return ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
    }

    private Flux<ServerSentEvent<String>> decodeStreamingResponse(ClientResponse response) {
        if (!response.statusCode().is2xxSuccessful()) {
            ProviderFailureException failure = streamHttpFailure(response);
            return response.releaseBody().thenMany(Flux.error(failure));
        }
        return response.mutate()
                .body(OpenAiCompatibleChatProvider::normalizeSseLineEndings)
                .build()
                .bodyToFlux(SSE_TYPE)
                .onErrorMap(this::normalizedStreamingBodyFailure);
    }

    private RuntimeException normalizedStreamingBodyFailure(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof IOException) {
                return ProviderFailureException.of(
                        AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED);
            }
            current = current.getCause();
        }
        return normalizedStreamFailure(failure);
    }

    private static ProviderFailureException streamHttpFailure(ClientResponse response) {
        int status = response.statusCode().value();
        Duration retryAfter = parseRetryAfter(response.headers().header("Retry-After")
                .stream().findFirst().orElse(null));
        String requestIdMasked = IdentifierMasker.mask(response.headers()
                .header(REQUEST_ID_HEADER).stream().findFirst().orElse(null));
        if (status == 401 || status == 403 || status == 429
                || (status >= 500 && status <= 599)) {
            return ProviderFailureException.http(status, retryAfter, requestIdMasked);
        }
        if (status >= 400 && status < 500) {
            return new ProviderFailureException(
                    AttemptFailureType.UPSTREAM_REQUEST_REJECTED,
                    status,
                    retryAfter,
                    requestIdMasked);
        }
        return new ProviderFailureException(
                AttemptFailureType.PROVIDER_RESPONSE_INVALID,
                status,
                null,
                requestIdMasked);
    }

    private static Flux<DataBuffer> normalizeSseLineEndings(Flux<DataBuffer> body) {
        return Flux.defer(() -> {
            var normalizer = new SseLineEndingNormalizer();
            return body.concatMap(normalizer::normalize)
                    .concatWith(Mono.defer(normalizer::finish))
                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
        });
    }

    private Map<String, Object> message(ChatMessage message) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (message instanceof SystemMessage system) {
            result.put("role", "system");
            result.put("content", system.text());
        } else if (message instanceof UserMessage user) {
            result.put("role", "user");
            result.put("content", user.text());
        } else if (message instanceof AssistantMessage assistant) {
            result.put("role", "assistant");
            if (assistant.text() != null) {
                result.put("content", assistant.text());
            }
            if (assistant.refusal() != null) {
                result.put("refusal", assistant.refusal());
            }
            if (!assistant.toolCalls().isEmpty()) {
                result.put("tool_calls", assistant.toolCalls().stream()
                        .map(this::toolCall)
                        .toList());
            }
        } else if (message instanceof ToolResultMessage toolResult) {
            result.put("role", "tool");
            result.put("tool_call_id", toolResult.toolCallId());
            result.put("content", toolResult.text());
        } else {
            throw new IllegalArgumentException("unsupported Chat message");
        }
        return result;
    }

    private Map<String, Object> tool(ToolDefinition definition) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", definition.name());
        if (definition.description() != null) {
            function.put("description", definition.description());
        }
        function.put("parameters", parseObject(definition.inputSchemaJson()));
        return Map.of("type", "function", "function", function);
    }

    private Map<String, Object> toolCall(ToolCall call) {
        return Map.of(
                "id", call.id(),
                "type", "function",
                "function", Map.of("name", call.name(), "arguments", call.argumentsJson()));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String raw) {
        try {
            return json.readValue(raw, Map.class);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("tool schema must be valid JSON");
        }
    }

    private static List<ToolCall> toolCalls(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("tool_calls must be an array");
        }
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode call : node) {
            JsonNode function = requiredObject(call, "function");
            calls.add(new ToolCall(
                    requiredText(call, "id"),
                    requiredText(function, "name"),
                    requiredText(function, "arguments")));
        }
        return List.copyOf(calls);
    }

    private static TokenUsage usage(JsonNode node) {
        if (!node.isObject()
                || !node.path("prompt_tokens").canConvertToLong()
                || !node.path("completion_tokens").canConvertToLong()
                || !node.path("total_tokens").canConvertToLong()) {
            return null;
        }
        return new TokenUsage(
                node.path("prompt_tokens").longValue(),
                node.path("completion_tokens").longValue(),
                node.path("total_tokens").longValue());
    }

    private static ChatFinishReason finishReason(String raw) {
        if (raw == null) {
            return ChatFinishReason.UNKNOWN;
        }
        return switch (raw) {
            case "stop" -> ChatFinishReason.STOP;
            case "length" -> ChatFinishReason.LENGTH;
            case "tool_calls" -> ChatFinishReason.TOOL_CALLS;
            case "content_filter" -> ChatFinishReason.CONTENT_FILTER;
            default -> ChatFinishReason.UNKNOWN;
        };
    }

    private static JsonNode requiredArrayItem(JsonNode node, String field, int index) {
        JsonNode array = node.path(field);
        if (!array.isArray() || array.size() <= index) {
            throw new IllegalArgumentException(field + " must contain an item");
        }
        return array.get(index);
    }

    private static JsonNode requiredObject(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
        return value;
    }

    private static String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.textValue() : null;
    }

    private static Duration parseRetryAfter(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static AttemptFailureType transportFailure(
            RestClientException failure, boolean headersReceived) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConnectionRequestTimeoutException
                    || current instanceof ConnectTimeoutException) {
                return AttemptFailureType.CONNECT_TIMEOUT;
            }
            if (current instanceof SocketTimeoutException) {
                return headersReceived
                        ? AttemptFailureType.RESPONSE_BODY_TIMEOUT
                        : AttemptFailureType.RESPONSE_HEADERS_TIMEOUT;
            }
            if (current instanceof ConnectException) {
                return AttemptFailureType.CONNECT_FAILED;
            }
            current = current.getCause();
        }
        return headersReceived
                ? AttemptFailureType.RESPONSE_BODY_FAILED
                : AttemptFailureType.RESPONSE_HEADERS_FAILED;
    }

    private static Timeout timeout(Duration duration) {
        return Timeout.of(duration);
    }

    private static URI chatEndpoint(URI baseUrl) {
        String base = baseUrl.toString();
        return URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
                + "/chat/completions");
    }

    private final class StreamDecodeState {

        private ChatFinishReason finishReason = ChatFinishReason.UNKNOWN;
        private TokenUsage streamUsage;
        private boolean completed;
        private final Map<Integer, ToolCallIdentity> toolCallIdentities = new LinkedHashMap<>();

        private List<ProviderChatEvent> decode(ServerSentEvent<String> event) {
            if (completed) {
                return List.of();
            }
            String eventType = event.event();
            if (eventType != null && !eventType.isBlank() && !"message".equals(eventType)) {
                if ("error".equals(eventType)) {
                    throw ProviderFailureException.of(AttemptFailureType.UPSTREAM_ERROR_EVENT);
                }
                if ("done".equals(eventType)) {
                    return completeStream();
                }
                log.warn("Skipping unknown upstream SSE event type={}",
                        diagnosticEventType(eventType));
                return List.of(probeSignal(event.data()));
            }
            String data = event.data();
            if (data == null || data.isBlank()) {
                return List.of(probeSignal(data));
            }
            if ("[DONE]".equals(data.trim())) {
                return completeStream();
            }
            try {
                JsonNode root = json.readTree(data);
                JsonNode usageNode = root.path("usage");
                TokenUsage eventUsage = null;
                if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                    eventUsage = usage(usageNode);
                    if (eventUsage == null) {
                        throw new IllegalArgumentException("usage must be valid");
                    }
                    streamUsage = eventUsage;
                }
                JsonNode choices = root.path("choices");
                if (!choices.isArray()) {
                    throw new IllegalArgumentException("choices must be an array");
                }
                if (choices.isEmpty()) {
                    if (eventUsage == null) {
                        throw new IllegalArgumentException("empty choices require usage");
                    }
                    return List.of(probeSignal(data));
                }
                JsonNode choice = requiredArrayItem(root, "choices", 0);
                String rawFinishReason = optionalText(choice, "finish_reason");
                if (rawFinishReason != null) {
                    finishReason = finishReason(rawFinishReason);
                }
                JsonNode delta = requiredObject(choice, "delta");
                List<ProviderChatEvent> events = new ArrayList<>();
                String content = optionalText(delta, "content");
                if (content != null && !content.isEmpty()) {
                    events.add(new ProviderChatEvent.TextDelta(content));
                }
                String refusal = optionalText(delta, "refusal");
                if (refusal != null && !refusal.isEmpty()) {
                    events.add(new ProviderChatEvent.RefusalDelta(refusal));
                }
                decodeToolCalls(delta.path("tool_calls"), events);
                return events.isEmpty()
                        ? List.of(probeSignal(data))
                        : List.copyOf(events);
            } catch (JsonProcessingException | IllegalArgumentException invalid) {
                throw ProviderFailureException.of(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID);
            }
        }

        private void decodeToolCalls(
                JsonNode toolCallsNode, List<ProviderChatEvent> events) {
            if (toolCallsNode.isMissingNode() || toolCallsNode.isNull()) {
                return;
            }
            if (!toolCallsNode.isArray()) {
                throw new IllegalArgumentException("tool_calls must be an array");
            }
            for (JsonNode toolCall : toolCallsNode) {
                JsonNode indexNode = toolCall.path("index");
                if (!indexNode.isIntegralNumber()
                        || !indexNode.canConvertToInt()
                        || indexNode.intValue() < 0) {
                    throw new IllegalArgumentException("tool call index must be non-negative");
                }
                int index = indexNode.intValue();
                String type = optionalText(toolCall, "type");
                if (type != null && !"function".equals(type)) {
                    throw new IllegalArgumentException("unsupported tool call type");
                }
                JsonNode function = requiredObject(toolCall, "function");
                String callId = optionalNonBlankText(toolCall, "id");
                String name = optionalNonBlankText(function, "name");
                ToolCallIdentity identity = toolCallIdentities.get(index);
                if (identity == null) {
                    if (callId == null || name == null) {
                        throw new IllegalArgumentException("first tool delta requires identity");
                    }
                    identity = new ToolCallIdentity(callId, name);
                    toolCallIdentities.put(index, identity);
                } else if ((callId != null && !identity.callId().equals(callId))
                        || (name != null && !identity.name().equals(name))) {
                    throw new IllegalArgumentException("tool call identity changed");
                }
                String arguments = optionalText(function, "arguments");
                if (arguments != null && !arguments.isEmpty()) {
                    events.add(new ProviderChatEvent.ToolCallDelta(
                            identity.callId(), index, identity.name(), arguments));
                }
            }
        }

        private List<ProviderChatEvent> completeStream() {
            completed = true;
            return List.of(new ProviderChatEvent.Completed(finishReason, streamUsage));
        }

        private Flux<ProviderChatEvent> endOfInput() {
            return completed
                    ? Flux.empty()
                    : Flux.error(ProviderFailureException.of(
                            AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED));
        }

        private ProviderChatEvent.ProbeSignal probeSignal(String data) {
            return new ProviderChatEvent.ProbeSignal(
                    data == null ? 0 : data.getBytes(StandardCharsets.UTF_8).length);
        }
    }

    private static String optionalNonBlankText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return null;
        }
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static String diagnosticEventType(String eventType) {
        if (eventType.length() > 64) {
            return "other";
        }
        for (int index = 0; index < eventType.length(); index++) {
            char character = eventType.charAt(index);
            if (!Character.isLetterOrDigit(character)
                    && character != '.' && character != '_' && character != '-') {
                return "other";
            }
        }
        return eventType;
    }

    private record ToolCallIdentity(String callId, String name) {
    }

    private static final class SseLineEndingNormalizer {

        private boolean pendingCarriageReturn;

        private Mono<DataBuffer> normalize(DataBuffer source) {
            byte[] input = new byte[source.readableByteCount()];
            try {
                source.read(input);
            } finally {
                DataBufferUtils.release(source);
            }
            byte[] output = new byte[input.length + (pendingCarriageReturn ? 1 : 0)];
            int sourceIndex = 0;
            int targetIndex = 0;
            if (pendingCarriageReturn) {
                output[targetIndex++] = '\n';
                pendingCarriageReturn = false;
                if (input.length > 0 && input[0] == '\n') {
                    sourceIndex++;
                }
            }
            while (sourceIndex < input.length) {
                byte current = input[sourceIndex++];
                if (current != '\r') {
                    output[targetIndex++] = current;
                    continue;
                }
                if (sourceIndex == input.length) {
                    pendingCarriageReturn = true;
                    break;
                }
                output[targetIndex++] = '\n';
                if (input[sourceIndex] == '\n') {
                    sourceIndex++;
                }
            }
            return targetIndex == 0
                    ? Mono.empty()
                    : Mono.just(DefaultDataBufferFactory.sharedInstance
                            .wrap(java.util.Arrays.copyOf(output, targetIndex)));
        }

        private Mono<DataBuffer> finish() {
            if (!pendingCarriageReturn) {
                return Mono.empty();
            }
            pendingCarriageReturn = false;
            return Mono.just(DefaultDataBufferFactory.sharedInstance.wrap(new byte[] {'\n'}));
        }
    }

    @Override
    public void close() throws IOException {
        deadlineScheduler.shutdownNow();
        streamingConnections.disposeLater().block(Duration.ofSeconds(5));
        httpClient.close();
    }

    private static final class LifecycleRequestFactory extends HttpComponentsClientHttpRequestFactory {

        private final AttemptLifecycle lifecycle;
        private final AttemptControl control;

        private LifecycleRequestFactory(
                CloseableHttpClient httpClient,
                AttemptLifecycle lifecycle,
                AttemptControl control) {
            super(httpClient);
            this.lifecycle = lifecycle;
            this.control = control;
        }

        @Override
        protected ClassicHttpRequest createHttpUriRequest(HttpMethod httpMethod, URI uri) {
            ClassicHttpRequest request = super.createHttpUriRequest(httpMethod, uri);
            if (request instanceof Cancellable cancellable && control.bind(cancellable)) {
                lifecycle.dispatched();
            }
            return request;
        }
    }

    private static final class AttemptControl {

        private final AtomicReference<AttemptState> state = new AtomicReference<>(AttemptState.ACTIVE);
        private final AtomicReference<Cancellable> request = new AtomicReference<>();

        private boolean bind(Cancellable cancellable) {
            request.set(cancellable);
            if (state.get() == AttemptState.ACTIVE) {
                return true;
            }
            cancellable.cancel();
            return false;
        }

        private void callerCanceled() {
            cancel(AttemptState.CALLER_CANCELED);
        }

        private void totalDeadline() {
            cancel(AttemptState.TOTAL_DEADLINE);
        }

        private void cancel(AttemptState cause) {
            if (state.compareAndSet(AttemptState.ACTIVE, cause)) {
                Cancellable current = request.get();
                if (current != null) {
                    current.cancel();
                }
            }
        }

        private boolean completed() {
            return state.compareAndSet(AttemptState.ACTIVE, AttemptState.COMPLETED);
        }

        private boolean hasFailure() {
            AttemptState current = state.get();
            return current == AttemptState.CALLER_CANCELED
                    || current == AttemptState.TOTAL_DEADLINE;
        }

        private ProviderFailureException failure() {
            return switch (state.get()) {
                case CALLER_CANCELED -> ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED);
                case TOTAL_DEADLINE -> ProviderFailureException.of(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED);
                default -> throw new IllegalStateException("attempt has no terminal failure");
            };
        }
    }

    private enum AttemptState {
        ACTIVE,
        COMPLETED,
        CALLER_CANCELED,
        TOTAL_DEADLINE
    }
}
