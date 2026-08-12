package com.app.qa.generation;

import com.app.qa.prompt.GenerationPromptAssembler;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

public final class GenerationCommandFactory {

    private static final String SYSTEM_PROMPT = loadSystemPrompt();

    private final JdbcTemplate jdbc;

    GenerationCommandFactory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public GenerationCommand create(UUID turnId) {
        var current = jdbc.queryForObject("""
                select t.conversation_id, t.user_message_id, m.content
                  from qa_turn t
                  join qa_message m on m.id = t.user_message_id and m.deleted = false
                 where t.id = ? and t.deleted = false
                """, (rs, rowNum) -> new Current(
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_message_id", UUID.class),
                        rs.getString("content")), turnId);
        var evidence = jdbc.query("""
                select s.id, c.content
                  from qa_retrieval_source s
                  join document_chunk c on c.id = s.chunk_id and c.deleted = false
                 where s.turn_id = ?
                 order by s.ordinal
                """, (rs, rowNum) -> new GenerationPromptAssembler.Item(
                        rs.getObject("id", UUID.class).toString(), rs.getString("content")), turnId);
        var tools = jdbc.query("""
                select id, result_text
                  from qa_tool_call
                 where turn_id = ? and status = 'SUCCEEDED' and result_text is not null
                 order by ordinal
                """, (rs, rowNum) -> new GenerationPromptAssembler.Item(
                        rs.getObject("id", UUID.class).toString(), rs.getString("result_text")), turnId);
        var intentHints = jdbc.query("""
                select node_id, full_path_snapshot
                  from qa_intent_match
                 where turn_id = ? and deleted = false and rank = 1
                 order by subquestion_id
                """, (rs, rowNum) -> new GenerationPromptAssembler.Item(
                        rs.getObject("node_id", UUID.class).toString(), rs.getString("full_path_snapshot")), turnId);
        return new GenerationCommand(
                current.conversationId,
                turnId,
                current.userMessageId,
                new GenerationPromptAssembler.Material(
                        SYSTEM_PROMPT,
                        new GenerationPromptAssembler.Item("current-question", current.question),
                        List.of(),
                        intentHints,
                        evidence,
                        tools,
                        List.of()));
    }

    private static String loadSystemPrompt() {
        var resource = new ClassPathResource("prompts/qa/3c/generation-system.md");
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8).strip();
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load generation system prompt", exception);
        }
    }

    private record Current(UUID conversationId, UUID userMessageId, String question) {}
}
