package com.app.qa.conversation;

import java.time.Duration;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class JdbcTurnStageSink implements TurnStageSink {

    private final JdbcTemplate jdbc;

    public JdbcTurnStageSink(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(StageEvent event) {
        try {
            jdbc.update("""
                    insert into qa_turn_stage
                        (id, turn_id, stage, work_unit_ordinal, outcome, reason_code,
                         logical_call_id, intent_revision_id, started_at, completed_at, duration_ms)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    on conflict (turn_id, stage, work_unit_ordinal) do nothing
                    """,
                    UUID.randomUUID(),
                    event.turnId(),
                    event.stage().name(),
                    event.workUnitOrdinal(),
                    event.outcome().name(),
                    event.reasonCode(),
                    event.logicalCallId(),
                    event.intentRevisionId(),
                    event.startedAt(),
                    event.completedAt(),
                    Math.max(0, Duration.between(event.startedAt(), event.completedAt()).toMillis()));
        } catch (DataAccessException ignored) {
            // Stage diagnostics are best effort and never change the business outcome.
        }
    }
}
