package com.app.qa.generation;

import org.springframework.jdbc.core.JdbcTemplate;

final class JdbcAssistantAnswerStore implements AssistantAnswerStore {

    private final JdbcTemplate jdbc;

    JdbcAssistantAnswerStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(AnswerWrite answer) {
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content, incomplete)
                values (?, ?, ?, ?, 'ASSISTANT', 'ASSISTANT_ANSWER', ?, ?)
                """,
                answer.messageId(),
                answer.conversationId(),
                answer.turnId(),
                answer.sequenceNo(),
                answer.content(),
                answer.incomplete());
    }
}
