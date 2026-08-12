package com.app.qa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.model.TokenUsage;
import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.conversation.TurnProcessor;
import com.app.qa.model.TurnStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

/** 3C-API-01~04、3C-STOP-01~04：真实 Controller → GenerationTasks 接线。 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(Qa3cApiIntegrationTests.GenerationApiConfiguration.class)
@TestPropertySource(properties = "app.knowledge.ingestion.enabled=false")
@ExtendWith(OutputCaptureExtension.class)
class Qa3cApiIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ApiFakeStream model;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate qa_conversation cascade");
        model.events.set(Flux.just(
                new ChatStreamEvent.TextDelta("API答案"),
                new ChatStreamEvent.Completed(ChatFinishReason.STOP, new TokenUsage(1, 1, 2))));
        model.invocations.set(0);
    }

    @AfterEach
    void clean() {
        jdbc.execute("truncate qa_conversation cascade");
    }

    @Test
    void api01到03_流式提交返回Sse且Accepted第一终态后Get可恢复() throws Exception {
        String request = request(UUID.randomUUID());
        var started = mvc.perform(post("/api/qa/turns:stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(request))
                .andExpect(status().isOk())
                .andReturn();
        started.getAsyncResult(Duration.ofSeconds(5).toMillis());
        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body).startsWith("event:accepted\nid:1\n");
        assertThat(body).contains("event:generation_started", "event:answer_delta", "event:completed");
        assertThat(body.indexOf("event:completed")).isGreaterThan(body.indexOf("event:answer_delta"));
        assertThat(body).doesNotContain("provider", "logicalCallId", "modelIdentity");

        String acceptedData = body.lines()
                .filter(line -> line.startsWith("data:"))
                .findFirst()
                .orElseThrow()
                .substring(5);
        var accepted = json.readTree(acceptedData);
        UUID conversationId = UUID.fromString(accepted.path("conversationId").asText());
        UUID turnId = UUID.fromString(accepted.path("turnId").asText());

        mvc.perform(get("/api/qa/conversations/{conversationId}/turns/{turnId}", conversationId, turnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.status").value("COMPLETED"))
                .andExpect(jsonPath("$.assistantMessage.content").value("API答案"))
                .andExpect(jsonPath("$.assistantMessage.incomplete").value(false))
                .andExpect(jsonPath("$.generation.status").value("COMPLETED"))
                .andExpect(jsonPath("$.generation.answerSaved").value(true))
                .andExpect(jsonPath("$.retrieval").doesNotExist())
                .andExpect(jsonPath("$.tools").isArray());
    }

    @Test
    void stop01到04_生成中停止校验归属并保持幂等真实终态() throws Exception {
        model.events.set(Flux.never());
        var started = mvc.perform(post("/api/qa/turns:stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(request(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn();

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select count(*) from qa_generation_task", Integer.class))
                        .isEqualTo(1));
        var task = jdbc.queryForMap("select id, conversation_id, turn_id from qa_generation_task");
        UUID taskId = (UUID) task.get("id");
        UUID conversationId = (UUID) task.get("conversation_id");
        UUID turnId = (UUID) task.get("turn_id");

        mvc.perform(get("/api/qa/conversations/{conversationId}/turns/{turnId}", conversationId, turnId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.turn.status").value("GENERATING"))
                .andExpect(jsonPath("$.generation.status").value("GENERATING"))
                .andExpect(jsonPath("$.assistantMessage").doesNotExist());

        mvc.perform(post("/api/qa/generation-tasks/{taskId}/stop", taskId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"conversationId":"%s","turnId":"%s"}
                                """.formatted(UUID.randomUUID(), turnId)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GENERATION_TASK_NOT_FOUND"));

        String ownership = """
                {"conversationId":"%s","turnId":"%s"}
                """.formatted(conversationId, turnId);
        mvc.perform(post("/api/qa/generation-tasks/{taskId}/stop", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content(ownership))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.alreadyTerminal").value(false));
        mvc.perform(post("/api/qa/generation-tasks/{taskId}/stop", taskId)
                        .contentType(MediaType.APPLICATION_JSON).content(ownership))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("STOPPED"))
                .andExpect(jsonPath("$.alreadyTerminal").value(true));

        started.getAsyncResult(Duration.ofSeconds(5).toMillis());
        mvc.perform(asyncDispatch(started)).andExpect(status().isOk());
    }

    @Test
    void api01_Accepted前校验错误保持普通Json() throws Exception {
        mvc.perform(post("/api/qa/turns:stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"clientRequestId\":null,\"message\":null}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void priv02_供应商错误的Url和Key只归一化为失败码(CapturedOutput output) throws Exception {
        model.events.set(Flux.error(new IllegalStateException(
                "https://PRIV_PROVIDER_HOST.invalid?api_key=PRIV_PROVIDER_KEY")));
        var started = mvc.perform(post("/api/qa/turns:stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(request(UUID.randomUUID())))
                .andExpect(status().isOk())
                .andReturn();
        started.getAsyncResult(Duration.ofSeconds(5).toMillis());
        String body = mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(body)
                .contains("event:failed", "MODEL_UNAVAILABLE")
                .doesNotContain("PRIV_PROVIDER_HOST", "PRIV_PROVIDER_KEY", "api_key");
        assertThat(output.getAll()).doesNotContain("PRIV_PROVIDER_HOST", "PRIV_PROVIDER_KEY");
        assertThat(jdbc.queryForObject(
                        "select row_to_json(t)::text from qa_generation_task t", String.class))
                .doesNotContain("PRIV_PROVIDER_HOST", "PRIV_PROVIDER_KEY")
                .contains("MODEL_UNAVAILABLE");
    }

    @Test
    void e2e_超时与工具未结算都通过真实Http保持归一化契约() throws Exception {
        model.events.set(Flux.error(new java.util.concurrent.TimeoutException("provider timeout")));
        var timedOut = mvc.perform(post("/api/qa/turns:stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(request(UUID.randomUUID())))
                .andExpect(status().isOk()).andReturn();
        timedOut.getAsyncResult(Duration.ofSeconds(5).toMillis());
        String sse = mvc.perform(asyncDispatch(timedOut)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(sse).contains("event:failed", "GENERATION_TIMEOUT").doesNotContain("provider timeout");

        mvc.perform(post("/api/qa/turns:stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(request(UUID.randomUUID(), "工具未结算")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TOOLS_NOT_SETTLED"));
    }

    @Test
    void api03_流式幂等重放不重播且Get读取既有终态() throws Exception {
        UUID requestId = UUID.randomUUID();
        var first = mvc.perform(post("/api/qa/turns:stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(request(requestId)))
                .andExpect(status().isOk()).andReturn();
        first.getAsyncResult(Duration.ofSeconds(5).toMillis());
        String body = mvc.perform(asyncDispatch(first)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        var accepted = json.readTree(body.lines().filter(line -> line.startsWith("data:"))
                .findFirst().orElseThrow().substring(5));

        mvc.perform(post("/api/qa/turns:stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content(request(requestId, accepted.path("conversationId").asText(), "测试流式回答")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TURN_ALREADY_ACTIVE"));
        assertThat(model.invocations).hasValue(1);
        mvc.perform(get("/api/qa/conversations/{conversationId}/turns/{turnId}",
                        accepted.path("conversationId").asText(), accepted.path("turnId").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.generation.status").value("COMPLETED"));
    }

    private static String request(UUID clientRequestId) {
        return request(clientRequestId, null, "测试流式回答");
    }

    private static String request(UUID clientRequestId, String text) {
        return request(clientRequestId, null, text);
    }

    private static String request(UUID clientRequestId, String conversationId, String text) {
        String conversation = conversationId == null ? "null" : "\"" + conversationId + "\"";
        return """
                {
                  "clientRequestId":"%s",
                  "conversationId":%s,
                  "message":{"type":"QUESTION","text":"%s"}
                }
                """.formatted(clientRequestId, conversation, text);
    }

    @TestConfiguration
    static class GenerationApiConfiguration {

        @Bean
        ApiFakeStream apiFakeStream() {
            return new ApiFakeStream();
        }

        @Bean
        @Primary
        StreamingChatClient apiStreamingChatClient(ApiFakeStream fake) {
            return fake;
        }

        @Bean
        @Primary
        TurnProcessor generationReadyProcessor(
                ConversationPersistenceService conversations, JdbcTemplate jdbc) {
            return submission -> {
                conversations.markProcessing(submission.turnId());
                conversations.completeTurn(submission.turnId(), TurnStatus.ACTIONS_READY, "ACTIONS_MAPPED");
                String content = jdbc.queryForObject(
                        "select content from qa_message where id = ?", String.class, submission.messageId());
                if ("工具未结算".equals(content)) {
                    jdbc.update("""
                            insert into qa_tool_call
                                (id, turn_id, ordinal, tool_name, side_effect, status)
                            values (?, ?, 0, 'pending-tool', true, 'PENDING_CONFIRM')
                            """, UUID.randomUUID(), submission.turnId());
                }
            };
        }
    }

    static final class ApiFakeStream implements StreamingChatClient {
        private final AtomicReference<Flux<ChatStreamEvent>> events = new AtomicReference<>(Flux.never());
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public Flux<ChatStreamEvent> stream(ChatCommand command) {
            invocations.incrementAndGet();
            return events.get();
        }
    }
}
