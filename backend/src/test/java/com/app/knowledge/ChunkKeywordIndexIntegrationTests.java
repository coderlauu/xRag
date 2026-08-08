package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.knowledge.keyword.ChunkKeywordRepository;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * 3B-KW-01~04 与 3B-CH 的真实 SQL 路径。
 *
 * <p>这一组里最重要的是**行集合一致**：漏写关键词行不会报错，症状只是关键词通道悄悄少召回。
 * 所以它必须被正面断言，而不是靠"写的时候记得"。
 */
@SpringBootTest
@Import(FakeEmbeddingConfig.class)
@Transactional
class ChunkKeywordIndexIntegrationTests {

    @Autowired
    private ChunkVectorRepository vectors;

    @Autowired
    private ChunkKeywordRepository keywords;

    @Autowired
    private ChunkSearchPort search;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void kw01_写向量的同一处也写关键词行且行集合一致() {
        var fixture = fixture("关键词一致性", "签收后 7 日内可发起退货，生鲜类商品除外");

        vectors.insertAll(fixture.kbId(), fixture.docId(), List.of(fixture.chunkId()), List.of(vector()));

        assertThat(chunkIdsIn("document_chunk_embedding", fixture.docId()))
                .isEqualTo(chunkIdsIn("document_chunk_keyword", fixture.docId()))
                .containsExactly(fixture.chunkId());
    }

    @Test
    void kw03_内容更新走删旧插新不产生重复行() {
        var fixture = fixture("关键词更新", "旧内容：退货政策");
        vectors.insertAll(fixture.kbId(), fixture.docId(), List.of(fixture.chunkId()), List.of(vector()));

        jdbc.update("update document_chunk set content = '新内容：退款到账' where id = ?", fixture.chunkId());
        vectors.deleteByChunkId(fixture.chunkId());
        vectors.insertAll(fixture.kbId(), fixture.docId(), List.of(fixture.chunkId()), List.of(vector()));

        assertThat(keywords.countByDocId(fixture.docId())).isEqualTo(1);
        // tsv 从 document_chunk.content 现读，所以更新后索引的是新正文而不是调用方传的旧文本。
        assertThat(search.searchByKeyword(Set.of(fixture.kbId()), "退款到账", 10))
                .extracting(ChunkSearchPort.ChunkMatch::chunkId)
                .containsExactly(fixture.chunkId());
        assertThat(search.searchByKeyword(Set.of(fixture.kbId()), "生鲜", 10)).isEmpty();
    }

    @Test
    void kw04_删除文档时两张派生表一起清空() {
        var fixture = fixture("关键词删除", "退货政策与退款时效");
        vectors.insertAll(fixture.kbId(), fixture.docId(), List.of(fixture.chunkId()), List.of(vector()));

        vectors.deleteByDocId(fixture.docId());

        assertThat(vectors.countByDocId(fixture.docId())).isZero();
        assertThat(keywords.countByDocId(fixture.docId())).isZero();
    }

    @Test
    void 关键词检索按知识库限定且全局不带过滤() {
        var first = fixture("关键词范围一", "海外仓入库时效说明");
        var second = fixture("关键词范围二", "海外仓退货流程说明");
        vectors.insertAll(first.kbId(), first.docId(), List.of(first.chunkId()), List.of(vector()));
        vectors.insertAll(second.kbId(), second.docId(), List.of(second.chunkId()), List.of(vector()));

        assertThat(search.searchByKeyword(Set.of(first.kbId()), "海外仓", 10))
                .extracting(ChunkSearchPort.ChunkMatch::chunkId)
                .containsExactly(first.chunkId());
        assertThat(search.searchByKeyword(Set.of(), "海外仓", 10))
                .extracting(ChunkSearchPort.ChunkMatch::chunkId)
                .contains(first.chunkId(), second.chunkId());
    }

    @Test
    void 切不出token的查询返回空而不是抛错() {
        // 纯标点在关键词通道上没有结果，这是正常情况；拼空 tsquery 给数据库才会抛错。
        assertThat(search.searchByKeyword(Set.of(), "，。！", 10)).isEmpty();
    }

    @Test
    void 向量检索支持多知识库定向与全局两种形态() {
        var first = fixture("向量范围一", "内容一");
        var second = fixture("向量范围二", "内容二");
        vectors.insertAll(first.kbId(), first.docId(), List.of(first.chunkId()), List.of(vector()));
        vectors.insertAll(second.kbId(), second.docId(), List.of(second.chunkId()), List.of(vector()));

        assertThat(search.searchByVector(Set.of(first.kbId(), second.kbId()), vector(), 10))
                .extracting(ChunkSearchPort.ChunkMatch::chunkId)
                .contains(first.chunkId(), second.chunkId());
        assertThat(search.searchByVector(Set.of(first.kbId()), vector(), 10))
                .extracting(ChunkSearchPort.ChunkMatch::chunkId)
                .containsExactly(first.chunkId());
        assertThat(search.searchByVector(Set.of(), vector(), 10)).isNotEmpty();
    }

    private List<Long> chunkIdsIn(String table, long docId) {
        return jdbc.queryForList(
                "select chunk_id from " + table + " where doc_id = ? order by chunk_id", Long.class, docId);
    }

    private Fixture fixture(String name, String content) {
        String alias = name + "-" + UUID.randomUUID();
        long kbId = jdbc.queryForObject(
                """
                insert into knowledge_base
                    (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'test', 1024)
                returning id
                """,
                Long.class,
                alias,
                alias);
        long docId = jdbc.queryForObject(
                """
                insert into source_document
                    (kb_id, name, source_type, storage_object_id)
                values (?, 'doc.txt', 'FILE', ?)
                returning id
                """,
                Long.class,
                kbId,
                UUID.randomUUID().toString());
        long chunkId = jdbc.queryForObject(
                """
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content,
                     char_count, token_count, content_hash)
                values (?, ?, 1, 0, ?, ?, 1,
                        '0000000000000000000000000000000000000000000000000000000000000000')
                returning id
                """,
                Long.class,
                kbId,
                docId,
                content,
                content.length());
        return new Fixture(kbId, docId, chunkId);
    }

    private float[] vector() {
        float[] vector = new float[1024];
        vector[0] = 1.0f;
        return vector;
    }

    private record Fixture(long kbId, long docId, long chunkId) {}
}
