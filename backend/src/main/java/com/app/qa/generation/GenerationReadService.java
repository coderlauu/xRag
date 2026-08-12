package com.app.qa.generation;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

public final class GenerationReadService {

    private final JdbcTemplate jdbc;

    GenerationReadService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<GenerationView> find(UUID turnId) {
        return jdbc.query("""
                select id, status, queued_once, answer_saved, assistant_message_id
                  from qa_generation_task
                 where turn_id = ?
                """, (rs, rowNum) -> new GenerationView(
                        new GenerationTaskId(rs.getObject("id", UUID.class)),
                        GenerationTaskStatus.valueOf(rs.getString("status")),
                        rs.getBoolean("queued_once"),
                        rs.getObject("answer_saved", Boolean.class),
                        rs.getObject("assistant_message_id", UUID.class)), turnId)
                .stream()
                .findFirst();
    }

    public Optional<AssistantMessageView> findAssistant(UUID turnId) {
        return jdbc.query("""
                select id, content, incomplete
                  from qa_message
                 where turn_id = ? and message_type = 'ASSISTANT_ANSWER' and deleted = false
                """, (rs, rowNum) -> new AssistantMessageView(
                        rs.getObject("id", UUID.class),
                        rs.getString("content"),
                        rs.getBoolean("incomplete")), turnId)
                .stream()
                .findFirst();
    }

    public record GenerationView(
            GenerationTaskId taskId,
            GenerationTaskStatus status,
            boolean queuedOnce,
            Boolean answerSaved,
            UUID assistantMessageId) {}

    public record AssistantMessageView(UUID messageId, String content, boolean incomplete) {}
}
