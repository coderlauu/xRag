package com.app.knowledge.ingestion;

import java.time.Duration;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

/**
 * 定时同步表达式的校验（api.md §3）。
 *
 * <p>存在的理由不是"表达式要合法"，而是**防住 `* * * * * ?` 这类每秒执行的表达式**：每次同步
 * 在内容变化时会重新分块并为每个分块调用一次 Embedding，一个高频 cron 能在无人察觉的情况下
 * 把模型调用费用打穿。这是本模块唯一一处直接与钱挂钩的输入校验。
 *
 * <p>判据是**实际算出的相邻两次执行间隔**而不是解析表达式的字段——后者要自己实现一套语义，
 * 而且对 `0/30 * * * * ?` 这种写法很容易判错。
 */
@Component
public class SyncCronValidator {

    /** 表达式非法或间隔过密时抛出，调用方翻译成 {@code 400}。 */
    public static class InvalidCronException extends RuntimeException {
        public InvalidCronException(String message) {
            super(message);
        }
    }

    private final Duration minInterval;

    public SyncCronValidator(@Value("${app.knowledge.sync.min-interval:10m}") Duration minInterval) {
        this.minInterval = minInterval;
    }

    public Duration minInterval() {
        return minInterval;
    }

    /** @return 解析后的表达式，调用方可直接拿去算 {@code next_sync_time} */
    public CronExpression validate(String cron) {
        if (cron == null || cron.isBlank()) {
            throw new InvalidCronException("开启定时同步时必须填写同步规则。");
        }
        CronExpression expression;
        try {
            expression = CronExpression.parse(cron.trim());
        } catch (IllegalArgumentException invalid) {
            throw new InvalidCronException("同步规则不是合法的 cron 表达式。");
        }

        // 往后连算三次触发时间，检查后两段间隔。
        // **第一段有意跳过**：它是"此刻到下一次触发"，长度取决于现在几点，与表达式的疏密无关
        // ——每小时执行一次的表达式在 10:59:59 调用也会得到 1 秒的第一段。
        ZonedDateTime cursor = ZonedDateTime.now();
        for (int i = 0; i < 3; i++) {
            ZonedDateTime next = expression.next(cursor);
            if (next == null) {
                throw new InvalidCronException("同步规则在未来不会再触发，请检查表达式。");
            }
            if (i > 0 && Duration.between(cursor, next).compareTo(minInterval) < 0) {
                throw new InvalidCronException(
                        "两次同步的间隔不能小于 %d 分钟，请调整同步规则。".formatted(minInterval.toMinutes()));
            }
            cursor = next;
        }
        return expression;
    }
}
