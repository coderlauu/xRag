package com.app.qa.conversation;

import com.app.qa.model.StageOutcome;
import com.app.qa.model.TurnStage;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@FunctionalInterface
public interface TurnStageSink {

    Logger LOGGER = LoggerFactory.getLogger(TurnStageSink.class);

    void record(StageEvent event);

    /**
     * 阶段诊断的唯一对外写入口：**失败绝不改变业务终态**。
     *
     * <p>这条保证放在端口上而不是每个调用点，是因为四个编排组件都要写 stage，而漏掉任何一个的
     * 后果都一样严重——一次 stage 写失败会被 {@code QuestionUnderstandingService.process()} 的
     * catch 当成理解失败，把一个已经算完的 turn 判成 FAILED，等于让诊断反过来决定业务结果。
     *
     * <p>{@link JdbcTurnStageSink} 自己只吞 {@code DataAccessException}；别的实现（未来的异步
     * writer、被装饰的 sink）不保证只抛这一种，所以兜底必须在这里。
     */
    default void recordBestEffort(StageEvent event) {
        try {
            record(event);
        } catch (RuntimeException diagnosticFailure) {
            LOGGER.warn(
                    "QA stage diagnostics failed for turn {} stage {}; business outcome unchanged",
                    event.turnId(),
                    event.stage());
        }
    }

    record StageEvent(
            UUID turnId,
            TurnStage stage,
            int workUnitOrdinal,
            StageOutcome outcome,
            String reasonCode,
            UUID logicalCallId,
            UUID intentRevisionId,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {}
}
