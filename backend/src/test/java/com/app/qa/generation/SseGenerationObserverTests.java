package com.app.qa.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 3C-SSE-01~08：业务事件到 emitter 的唯一传输 seam。 */
class SseGenerationObserverTests {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-12T08:00:00Z"), ZoneOffset.UTC);

    private ExecutorService pushExecutor;
    private ScheduledExecutorService timers;

    @BeforeEach
    void createExecutors() {
        pushExecutor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "qa-push-test"));
        timers = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void shutdownExecutors() {
        pushExecutor.shutdownNow();
        timers.shutdownNow();
    }

    @Test
    void sse01到03_映射闭集且Accepted第一Sequence递增终态唯一() throws Exception {
        var sink = new CapturingSink();
        var observer = observer(sink, 16);
        var taskId = new GenerationTaskId(UUID.randomUUID());
        var conversationId = UUID.randomUUID();
        var turnId = UUID.randomUUID();
        var userMessageId = UUID.randomUUID();

        observer.onEvent(new GenerationEvent.Accepted(1, taskId, conversationId, turnId, userMessageId));
        observer.onEvent(new GenerationEvent.GenerationStarted(2, false));
        observer.onEvent(new GenerationEvent.Completed(3, null, false));
        observer.onEvent(new GenerationEvent.AnswerDelta(4, "晚到内容"));

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(sink.completed).isTrue());
        assertThat(sink.frames).extracting(SseGenerationObserver.Frame::event)
                .containsExactly("accepted", "generation_started", "completed");
        assertThat(sink.frames).extracting(SseGenerationObserver.Frame::id)
                .containsExactly("1", "2", "3");
        var accepted = JSON.readTree(sink.frames.get(0).data());
        assertThat(accepted.path("conversationId").asText()).isEqualTo(conversationId.toString());
        assertThat(accepted.path("turnId").asText()).isEqualTo(turnId.toString());
        assertThat(accepted.path("taskId").asText()).isEqualTo(taskId.value().toString());
        assertThat(accepted.path("userMessageId").asText()).isEqualTo(userMessageId.toString());
        assertThat(accepted.path("occurredAt").asText()).isEqualTo("2026-08-12T08:00:00Z");
        assertThat(accepted.toString())
                .doesNotContain("provider", "model", "tier", "logicalCallId", "execution");
    }

    @Test
    void sse04_Delta在二十毫秒或二百五十六字符任一边界合并Flush() throws Exception {
        var sink = new CapturingSink();
        var observer = observer(sink, 16);

        observer.onEvent(new GenerationEvent.AnswerDelta(1, "a".repeat(100)));
        observer.onEvent(new GenerationEvent.AnswerDelta(2, "b".repeat(156)));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(sink.frames).hasSize(1));
        assertThat(sink.frames.get(0).event()).isEqualTo("answer_delta");
        assertThat(sink.frames.get(0).id()).isEqualTo("2");
        assertThat(JSON.readTree(sink.frames.get(0).data()).path("text").asText())
                .isEqualTo("a".repeat(100) + "b".repeat(156));

        observer.onEvent(new GenerationEvent.AnswerDelta(3, "按时间刷新"));
        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(sink.frames).hasSize(2));
        assertThat(JSON.readTree(sink.frames.get(1).data()).path("text").asText())
                .isEqualTo("按时间刷新");
    }

    @Test
    void sse05_Mailbox饱和显式抛出背压且为失败终态腾出空间() throws Exception {
        var sink = new CapturingSink();
        var blocker = new CountDownLatch(1);
        pushExecutor.execute(() -> {
            try {
                blocker.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        var observer = observer(sink, 1);
        observer.onEvent(new GenerationEvent.Queued(1));

        assertThatThrownBy(() -> observer.onEvent(new GenerationEvent.GenerationStarted(2, true)))
                .isInstanceOf(PushBackpressureException.class);
        observer.onEvent(new GenerationEvent.Failed(
                3, GenerationFailureCode.PUSH_BACKPRESSURE, true, null, false));
        blocker.countDown();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(sink.completed).isTrue());
        assertThat(sink.frames).singleElement().satisfies(frame -> {
            assertThat(frame.event()).isEqualTo("failed");
            assertThat(frame.data()).contains("PUSH_BACKPRESSURE");
        });
    }

    @Test
    void sse06_Send只在专用Push线程而非调用线程执行() {
        var sink = new CapturingSink();
        var observer = observer(sink, 8);

        var caller = new Thread(() -> observer.onEvent(new GenerationEvent.Queued(1)), "reactor-http-nio-7");
        caller.start();

        await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(sink.threads).hasSize(1));
        assertThat(sink.threads).containsExactly("qa-push-test");
    }

    @Test
    void sse07和08_同一Adapter覆盖拒绝与正常路径且终态后静默忽略() {
        for (GenerationEvent terminal : List.of(
                new GenerationEvent.Failed(2, GenerationFailureCode.MODEL_UNAVAILABLE, true, null, false),
                new GenerationEvent.TimedOut(2))) {
            var sink = new CapturingSink();
            var observer = observer(sink, 8);
            observer.onEvent(new GenerationEvent.Accepted(
                    1,
                    new GenerationTaskId(UUID.randomUUID()),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()));
            observer.onEvent(terminal);
            observer.onEvent(new GenerationEvent.AnswerDelta(3, "不得发送"));

            await().atMost(Duration.ofSeconds(2)).untilAsserted(() -> assertThat(sink.completed).isTrue());
            assertThat(sink.frames).hasSize(2);
            assertThat(sink.frames.get(1).event()).isIn("failed", "timed_out");
        }
    }

    private SseGenerationObserver observer(CapturingSink sink, int capacity) {
        return new SseGenerationObserver(sink, pushExecutor, timers, CLOCK, capacity);
    }

    private static final class CapturingSink implements SseGenerationObserver.Sink {
        private final List<SseGenerationObserver.Frame> frames = new CopyOnWriteArrayList<>();
        private final List<String> threads = new CopyOnWriteArrayList<>();
        private final AtomicBoolean completed = new AtomicBoolean();

        @Override
        public void send(SseGenerationObserver.Frame frame) {
            threads.add(Thread.currentThread().getName());
            frames.add(frame);
        }

        @Override
        public void complete() {
            completed.set(true);
        }
    }
}
