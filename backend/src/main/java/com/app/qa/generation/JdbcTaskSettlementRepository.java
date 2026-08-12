package com.app.qa.generation;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

final class JdbcTaskSettlementRepository implements TaskSettlementRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final AssistantAnswerStore answers;

    JdbcTaskSettlementRepository(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            AssistantAnswerStore answers) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.answers = answers;
    }

    @Override
    public PersistenceResult trySettle(GenerationTaskId taskId, TaskSettlement.Request request) {
        try {
            return transactions.execute(status -> settleInTransaction(taskId, request, true));
        } catch (AnswerPersistenceFailure exception) {
            return transactions.execute(status -> settleInTransaction(taskId, request, false));
        }
    }

    private PersistenceResult settleInTransaction(
            GenerationTaskId taskId,
            TaskSettlement.Request request,
            boolean saveAnswer) {
        boolean timedOut = request.status() == GenerationTaskStatus.TIMED_OUT;
        boolean shouldSave = saveAnswer && request.hasAnswer() && !timedOut;
        String reason = !saveAnswer && request.status() == GenerationTaskStatus.COMPLETED
                ? "COMPLETED_UNSAVED"
                : request.terminalReason();
        Boolean initiallySaved = timedOut ? null : false;
        int answerCharacters = request.hasAnswer()
                ? request.answer().codePointCount(0, request.answer().length())
                : 0;

        int claimed = jdbc.update("""
                update qa_generation_task
                   set status = ?, terminal_reason = ?, completed_at = now(),
                       answer_char_count = ?, assistant_message_id = null,
                       answer_saved = ?, updated_at = now()
                 where id = ? and status in ('QUEUED', 'GENERATING')
                """,
                request.status().name(),
                reason,
                answerCharacters,
                initiallySaved,
                taskId.value());
        if (claimed == 0) {
            return read(taskId, false);
        }

        var owner = jdbc.queryForObject("""
                select turn_id, conversation_id
                  from qa_generation_task
                 where id = ?
                """, (rs, rowNum) -> new Owner(
                        rs.getObject("turn_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class)), taskId.value());
        int turnUpdated = jdbc.update("""
                update qa_turn
                   set status = ?, terminal_reason = ?, completed_at = now(), updated_at = now()
                 where id = ? and deleted = false and status in ('QUEUED', 'GENERATING')
                """, request.status().name(), reason, owner.turnId());
        if (turnUpdated != 1) {
            throw new IllegalStateException("generation task and turn state diverged");
        }

        if (shouldSave) {
            jdbc.queryForObject(
                    "select id from qa_conversation where id = ? for update",
                    UUID.class,
                    owner.conversationId());
            Long sequence = jdbc.queryForObject(
                    "select coalesce(max(sequence_no), 0) + 1 from qa_message where conversation_id = ?",
                    Long.class,
                    owner.conversationId());
            UUID messageId = UUID.randomUUID();
            try {
                answers.save(new AssistantAnswerStore.AnswerWrite(
                        messageId,
                        owner.conversationId(),
                        owner.turnId(),
                        sequence,
                        request.answer(),
                        request.incomplete()));
            } catch (RuntimeException exception) {
                throw new AnswerPersistenceFailure(exception);
            }
            jdbc.update("""
                    update qa_generation_task
                       set assistant_message_id = ?, answer_saved = true, updated_at = now()
                     where id = ?
                    """, messageId, taskId.value());
        }
        jdbc.update("""
                update qa_conversation
                   set last_active_at = now(), updated_at = now()
                 where id = ?
                """, owner.conversationId());
        return read(taskId, true);
    }

    private PersistenceResult read(GenerationTaskId taskId, boolean won) {
        return jdbc.queryForObject("""
                select status, terminal_reason, assistant_message_id, answer_saved
                  from qa_generation_task
                 where id = ?
                """, (rs, rowNum) -> new PersistenceResult(
                        won,
                        GenerationTaskStatus.valueOf(rs.getString("status")),
                        rs.getString("terminal_reason"),
                        rs.getObject("assistant_message_id", UUID.class),
                        rs.getObject("answer_saved", Boolean.class) != null
                                && rs.getBoolean("answer_saved")), taskId.value());
    }

    private record Owner(UUID turnId, UUID conversationId) {}

    private static final class AnswerPersistenceFailure extends RuntimeException {
        private AnswerPersistenceFailure(RuntimeException cause) {
            super(cause);
        }
    }
}
