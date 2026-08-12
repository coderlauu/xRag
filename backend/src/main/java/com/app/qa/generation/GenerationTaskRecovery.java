package com.app.qa.generation;

import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class GenerationTaskRecovery {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    GenerationTaskRecovery(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAfterRestart() {
        transactions.executeWithoutResult(status -> {
            var turnIds = jdbc.queryForList("""
                    select turn_id
                      from qa_generation_task
                     where status in ('QUEUED', 'GENERATING')
                     for update
                    """, UUID.class);
            if (turnIds.isEmpty()) {
                return;
            }
            jdbc.update("""
                    update qa_generation_task
                       set status = 'FAILED', terminal_reason = 'SERVICE_RESTARTED',
                           completed_at = now(), answer_saved = false,
                           assistant_message_id = null, updated_at = now()
                     where status in ('QUEUED', 'GENERATING')
                    """);
            jdbc.batchUpdate("""
                    update qa_turn
                       set status = 'FAILED', terminal_reason = 'SERVICE_RESTARTED',
                           completed_at = now(), updated_at = now()
                     where id = ? and status in ('QUEUED', 'GENERATING')
                    """, turnIds, turnIds.size(), (statement, turnId) -> statement.setObject(1, turnId));
        });
    }
}
