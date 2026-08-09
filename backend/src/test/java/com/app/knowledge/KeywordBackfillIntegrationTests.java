package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.knowledge.keyword.ChunkKeywordRepository;
import com.app.knowledge.keyword.KeywordBackfillService;
import com.app.knowledge.search.ChunkSearchPort;
import com.app.knowledge.vector.ChunkVectorRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** 3B-KW-07、3B-KW-08。 */
@SpringBootTest
@Import(FakeEmbeddingConfig.class)
@TestPropertySource(properties = {
    "app.knowledge.keyword-backfill.enabled=true",
    "app.knowledge.keyword-backfill.batch-size=2",
})
@Transactional
class KeywordBackfillIntegrationTests {

    @Autowired
    private KeywordBackfillService backfill;

    @Autowired
    private ChunkVectorRepository vectors;

    @Autowired
    private ChunkKeywordRepository keywords;

    @Autowired
    private ChunkSearchPort search;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void kw07_只补缺失的行且重复执行幂等() {
        var fixture = seedIndexed("回填幂等", 5);
        // 人为制造缺口：删掉三条关键词行，模拟 V9 之前就存在的存量分块。
        jdbc.update(
                "delete from document_chunk_keyword where chunk_id in (?, ?, ?)",
                fixture.get(0),
                fixture.get(2),
                fixture.get(4));
        assertThat(countKeywords(fixture)).isEqualTo(2);

        int first = backfill.backfill();
        assertThat(first).isEqualTo(3);
        assertThat(countKeywords(fixture)).isEqualTo(5);

        // 幂等：第二次没有缺口可补，行数不变。
        assertThat(backfill.backfill()).isZero();
        assertThat(countKeywords(fixture)).isEqualTo(5);
    }

    @Test
    void kw07_分批游标跨批不跳过也不重复() {
        // batch-size=2，五条缺口要跨三批补齐。
        var fixture = seedIndexed("回填分批", 5);
        jdbc.update(
                "delete from document_chunk_keyword where chunk_id = any (?)",
                fixture.stream().mapToLong(Long::longValue).toArray());
        assertThat(countKeywords(fixture)).isZero();

        assertThat(backfill.backfill()).isEqualTo(5);
        assertThat(countKeywords(fixture)).isEqualTo(5);
    }

    @Test
    void kw08_回填未完成时关键词通道照常工作只是召回不全() {
        var fixture = seedIndexed("回填期间可用", 2);
        jdbc.update("delete from document_chunk_keyword where chunk_id = ?", fixture.get(0));

        // 少了一条索引，但查询不抛错，剩下那条照常召回。
        var hits = search.searchByKeyword(Set.of(), "回填期间可用", 10);
        assertThat(hits).extracting(ChunkSearchPort.ChunkMatch::chunkId).containsExactly(fixture.get(1));

        backfill.backfill();
        assertThat(search.searchByKeyword(Set.of(), "回填期间可用", 10)).hasSize(2);
    }

    @Test
    void 回填后的行与正常写入的行内容一致() {
        // 回填走的是同一个 ChunkKeywordRepository.indexAll，所以两条路径产出的 tsv 必须相同——
        // 否则"什么时候入的库"会影响召回，那是最难查的一类问题。
        var fixture = seedIndexed("回填一致性 退货政策", 1);
        String direct = jdbc.queryForObject(
                "select tsv::text from document_chunk_keyword where chunk_id = ?",
                String.class,
                fixture.get(0));

        jdbc.update("delete from document_chunk_keyword where chunk_id = ?", fixture.get(0));
        backfill.backfill();

        String backfilled = jdbc.queryForObject(
                "select tsv::text from document_chunk_keyword where chunk_id = ?",
                String.class,
                fixture.get(0));
        assertThat(backfilled).isEqualTo(direct);
    }

    private long countKeywords(List<Long> chunkIds) {
        return jdbc.queryForObject(
                "select count(*) from document_chunk_keyword where chunk_id = any (?)",
                Long.class,
                chunkIds.stream().mapToLong(Long::longValue).toArray());
    }

    private List<Long> seedIndexed(String content, int count) {
        String alias = "backfill-" + UUID.randomUUID();
        long kbId = jdbc.queryForObject(
                """
                insert into knowledge_base (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'test', 1024) returning id
                """,
                Long.class,
                alias,
                alias);
        long docId = jdbc.queryForObject(
                """
                insert into source_document (kb_id, name, source_type, storage_object_id)
                values (?, 'doc.txt', 'FILE', ?) returning id
                """,
                Long.class,
                kbId,
                UUID.randomUUID().toString());
        var chunkIds = new java.util.ArrayList<Long>();
        for (int i = 0; i < count; i++) {
            chunkIds.add(jdbc.queryForObject(
                    """
                    insert into document_chunk
                        (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                    values (?, ?, 1, ?, ?, ?, 1,
                            '0000000000000000000000000000000000000000000000000000000000000000')
                    returning id
                    """,
                    Long.class,
                    kbId,
                    docId,
                    i,
                    content,
                    content.length()));
        }
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        vectors.insertAll(kbId, docId, chunkIds, chunkIds.stream().map(id -> vector).toList());
        assertThat(keywords.countByDocId(docId)).isEqualTo(count);
        return chunkIds;
    }
}
