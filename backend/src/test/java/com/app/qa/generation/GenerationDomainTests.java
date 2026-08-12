package com.app.qa.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class GenerationDomainTests {

    @Test
    void 任务状态闭集明确区分活动态与唯一可结算终态() {
        assertThat(GenerationTaskStatus.QUEUED.isTerminal()).isFalse();
        assertThat(GenerationTaskStatus.GENERATING.isTerminal()).isFalse();
        assertThat(GenerationTaskStatus.COMPLETED.isTerminal()).isTrue();
        assertThat(GenerationTaskStatus.STOPPED.isTerminal()).isTrue();
        assertThat(GenerationTaskStatus.FAILED.isTerminal()).isTrue();
        assertThat(GenerationTaskStatus.TIMED_OUT.isTerminal()).isTrue();
    }

    @Test
    void 任务标识和问答事件在构造时拒绝模糊值() {
        assertThatThrownBy(() -> new GenerationTaskId(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new GenerationEvent.AnswerDelta(0, "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sequence");
        assertThatThrownBy(() -> new GenerationEvent.AnswerDelta(1, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("text");

        var taskId = new GenerationTaskId(UUID.randomUUID());
        var accepted = new GenerationEvent.Accepted(
                1, taskId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThat(accepted.taskId()).isEqualTo(taskId);
    }

    @Test
    void 事件与失败原因都是协议批准的封闭集合() {
        assertThat(GenerationEvent.class.getPermittedSubclasses())
                .containsExactlyInAnyOrder(
                        GenerationEvent.Accepted.class,
                        GenerationEvent.Queued.class,
                        GenerationEvent.GenerationStarted.class,
                        GenerationEvent.AnswerDelta.class,
                        GenerationEvent.Completed.class,
                        GenerationEvent.Stopped.class,
                        GenerationEvent.Failed.class,
                        GenerationEvent.TimedOut.class);
        assertThat(GenerationFailureCode.values())
                .containsExactly(
                        GenerationFailureCode.MODEL_UNAVAILABLE,
                        GenerationFailureCode.FIRST_BYTE_TIMEOUT,
                        GenerationFailureCode.GENERATION_TIMEOUT,
                        GenerationFailureCode.PROMPT_BUDGET_EXCEEDED,
                        GenerationFailureCode.TOOLS_NOT_SETTLED,
                        GenerationFailureCode.PUSH_BACKPRESSURE,
                        GenerationFailureCode.SERVICE_RESTARTED,
                        GenerationFailureCode.SERVICE_SHUTDOWN,
                        GenerationFailureCode.INTERNAL_CAPACITY_MISMATCH);
    }
}
