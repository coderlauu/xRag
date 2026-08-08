package com.app.qa.intent;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public final class BindableKnowledgeBaseQuery {

    private final JdbcTemplate jdbc;

    public BindableKnowledgeBaseQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<BindableKnowledgeBase> find(String query, Long beforeId, int limit) {
        String pattern = "%" + escapeLike(query == null ? "" : query.strip()) + "%";
        if (beforeId == null) {
            return jdbc.query("""
                    select kb.id, kb.name,
                           (select count(*) from document_chunk chunk
                             where chunk.kb_id = kb.id and chunk.deleted = false) as chunk_count
                      from knowledge_base kb
                     where kb.deleted = false
                       and kb.name ilike ? escape '\\'
                     order by kb.id desc
                     limit ?
                    """, (rs, rowNum) -> new BindableKnowledgeBase(
                            rs.getLong("id"), rs.getString("name"), true, rs.getLong("chunk_count")),
                    pattern, limit);
        }
        return jdbc.query("""
                select kb.id, kb.name,
                       (select count(*) from document_chunk chunk
                         where chunk.kb_id = kb.id and chunk.deleted = false) as chunk_count
                  from knowledge_base kb
                 where kb.deleted = false and kb.id < ?
                   and kb.name ilike ? escape '\\'
                 order by kb.id desc
                 limit ?
                """, (rs, rowNum) -> new BindableKnowledgeBase(
                        rs.getLong("id"), rs.getString("name"), true, rs.getLong("chunk_count")),
                beforeId, pattern, limit);
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    public record BindableKnowledgeBase(long knowledgeBaseId, String name, boolean enabled, long chunkCount) {}
}
