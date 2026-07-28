package com.app.knowledge.repository;

import com.app.knowledge.model.KnowledgeBase;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code knowledge_base} 表的数据访问。
 *
 * <p>**本类所有查询方法都必须带 {@code deleted = false}**。这条纪律靠约定维持、极易漏，
 * 漏了的症状是已删除的数据在某个查询路径上"复活"——测试矩阵 §7 为每个查询方法都安排了
 * 对应用例。本类不开事务，只参与调用方的事务。
 */
@Repository
public class KnowledgeBaseRepository {

    private static final String SELECT_WITH_COUNTS = """
            select kb.id, kb.name, kb.storage_alias, kb.description,
                   kb.embedding_model, kb.embedding_dimensions,
                   kb.create_time,
                   (select count(*) from source_document d where d.kb_id = kb.id and d.deleted = false)
                       as document_count,
                   (select count(*) from document_chunk c where c.kb_id = kb.id and c.deleted = false)
                       as chunk_count
            from knowledge_base kb
            where kb.deleted = false
            """;

    private static final RowMapper<KnowledgeBase> MAPPER = (rs, rowNum) -> new KnowledgeBase(
            rs.getLong("id"),
            rs.getString("name"),
            rs.getString("storage_alias"),
            rs.getString("description"),
            rs.getString("embedding_model"),
            rs.getInt("embedding_dimensions"),
            rs.getLong("document_count"),
            rs.getLong("chunk_count"),
            rs.getObject("create_time", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public KnowledgeBaseRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String name, String storageAlias, String description,
            String embeddingModel, int embeddingDimensions) {
        Long id = jdbc.queryForObject("""
                insert into knowledge_base
                    (name, storage_alias, description, embedding_model, embedding_dimensions)
                values (?, ?, ?, ?, ?)
                returning id
                """, Long.class, name, storageAlias, description, embeddingModel, embeddingDimensions);
        return id;
    }

    public Optional<KnowledgeBase> findById(long id) {
        return jdbc.query(SELECT_WITH_COUNTS + " and kb.id = ?", MAPPER, id).stream().findFirst();
    }

    /** 按 id 倒序 = 最近创建的在最前。列表页没有排序控件，这是唯一顺序。 */
    public List<KnowledgeBase> findPage(int page, int size) {
        return jdbc.query(SELECT_WITH_COUNTS + " order by kb.id desc limit ? offset ?",
                MAPPER, size, (long) (page - 1) * size);
    }

    public long count() {
        Long total = jdbc.queryForObject(
                "select count(*) from knowledge_base where deleted = false", Long.class);
        return total == null ? 0 : total;
    }

    public boolean existsByName(String name) {
        Long count = jdbc.queryForObject(
                "select count(*) from knowledge_base where name = ? and deleted = false", Long.class, name);
        return count != null && count > 0;
    }

    /** 改名时的重名检查要排除自己，否则"改成和原来一样的名字"会被判成重名。 */
    public boolean existsByNameExcluding(String name, long excludeId) {
        Long count = jdbc.queryForObject(
                "select count(*) from knowledge_base where name = ? and id <> ? and deleted = false",
                Long.class, name, excludeId);
        return count != null && count > 0;
    }

    public boolean hasDocuments(long kbId) {
        Long count = jdbc.queryForObject("""
                select count(*) from source_document
                 where kb_id = ? and deleted = false
                """, Long.class, kbId);
        return count != null && count > 0;
    }

    public boolean hasActiveIngestionRuns(long kbId) {
        Long count = jdbc.queryForObject("""
                select count(*) from ingestion_run
                 where kb_id = ? and status in ('QUEUED', 'RUNNING')
                """, Long.class, kbId);
        return count != null && count > 0;
    }

    public int update(long id, String name, String description) {
        return jdbc.update("""
                update knowledge_base
                   set name = ?, description = ?, update_time = now()
                 where id = ? and deleted = false
                """, name, description, id);
    }

    // ---- 级联逻辑删除（data-model.md §4）。四条按依赖顺序执行，由调用方包在一个事务里。 ----

    /** 向量是派生索引，**物理删除**（PRD §7.6 例外 1）。 */
    public int deleteEmbeddingsByKbId(long kbId) {
        return jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
    }

    public int softDeleteChunksByKbId(long kbId) {
        return jdbc.update("""
                update document_chunk set deleted = true, delete_time = now(), update_time = now()
                 where kb_id = ? and deleted = false
                """, kbId);
    }

    public int softDeleteDocumentsByKbId(long kbId) {
        return jdbc.update("""
                update source_document set deleted = true, delete_time = now(), update_time = now()
                 where kb_id = ? and deleted = false
                """, kbId);
    }

    public int softDelete(long id) {
        return jdbc.update("""
                update knowledge_base set deleted = true, delete_time = now(), update_time = now()
                 where id = ? and deleted = false
                """, id);
    }
}
