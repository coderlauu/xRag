package com.app.qa.intent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
final class JdbcKnowledgeBaseLookup implements KnowledgeBaseLookup {

    private final JdbcTemplate jdbc;

    JdbcKnowledgeBaseLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isAvailable(long knowledgeBaseId) {
        Integer count = jdbc.queryForObject(
                "select count(*) from knowledge_base where id = ? and deleted = false",
                Integer.class,
                knowledgeBaseId);
        return count != null && count == 1;
    }
}
