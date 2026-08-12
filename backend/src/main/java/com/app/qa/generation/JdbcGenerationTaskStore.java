package com.app.qa.generation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class JdbcGenerationTaskStore implements GenerationTaskStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    JdbcGenerationTaskStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void validateReady(GenerationCommand command) {
        Integer ready = jdbc.queryForObject("""
                select count(*)
                  from qa_turn
                 where id = ? and conversation_id = ? and user_message_id = ? and deleted = false
                   and status = 'ACTIONS_READY'
                """, Integer.class, command.turnId(), command.conversationId(), command.userMessageId());
        if (ready == null || ready != 1) {
            throw new IllegalArgumentException("turn is not ready for generation");
        }
        Integer unsettled = jdbc.queryForObject("""
                select count(*)
                  from qa_tool_call
                 where turn_id = ? and status in ('PENDING_CONFIRM', 'RUNNING')
                """, Integer.class, command.turnId());
        if (unsettled != null && unsettled > 0) {
            throw new ToolsNotSettledException();
        }
    }

    @Override
    public void create(GenerationTaskId taskId, GenerationCommand command, boolean queued) {
        Boolean created = transactions.execute(status -> {
            String generationStatus = queued ? "QUEUED" : "GENERATING";
            int turn = jdbc.update("""
                    update qa_turn
                       set status = ?, started_at = coalesce(started_at, now()),
                           terminal_reason = null, completed_at = null, updated_at = now()
                     where id = ? and conversation_id = ? and deleted = false
                       and status = 'ACTIONS_READY'
                    """, generationStatus, command.turnId(), command.conversationId());
            if (turn != 1) {
                return false;
            }
            jdbc.update("""
                    insert into qa_generation_task
                        (id, turn_id, conversation_id, status, queued_at,
                         generation_started_at, queued_once)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    taskId.value(),
                    command.turnId(),
                    command.conversationId(),
                    generationStatus,
                    queued ? java.time.OffsetDateTime.now() : null,
                    queued ? null : java.time.OffsetDateTime.now(),
                    queued);
            return true;
        });
        if (!Boolean.TRUE.equals(created)) {
            throw new IllegalStateException("turn stopped being ready before task creation");
        }
    }

    @Override
    public boolean markGenerating(GenerationTaskId taskId) {
        Boolean updated = transactions.execute(status -> {
            var turnIds = jdbc.queryForList("""
                    select turn_id from qa_generation_task
                     where id = ? and status = 'QUEUED'
                     for update
                    """, UUID.class, taskId.value());
            if (turnIds.isEmpty()) {
                return false;
            }
            int task = jdbc.update("""
                    update qa_generation_task
                       set status = 'GENERATING', generation_started_at = now(), updated_at = now()
                     where id = ? and status = 'QUEUED'
                    """, taskId.value());
            int turn = jdbc.update("""
                    update qa_turn set status = 'GENERATING', updated_at = now()
                     where id = ? and status = 'QUEUED'
                    """, turnIds.get(0));
            return task == 1 && turn == 1;
        });
        return Boolean.TRUE.equals(updated);
    }

    @Override
    public Optional<StoredTask> findOwned(GenerationTasks.OwnedTask task) {
        return jdbc.query("""
                select status, conversation_id, turn_id
                  from qa_generation_task
                 where id = ? and conversation_id = ? and turn_id = ?
                """, (rs, rowNum) -> new StoredTask(
                        GenerationTaskStatus.valueOf(rs.getString("status")),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("turn_id", UUID.class)),
                task.taskId().value(), task.conversationId(), task.turnId())
                .stream()
                .findFirst();
    }
}
