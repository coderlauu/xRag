package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.knowledge.vector.ChunkVectorRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** 共享向量表的知识库隔离接缝。 */
@SpringBootTest
@Import(FakeEmbeddingConfig.class)
@Transactional
class ChunkVectorSearchIntegrationTests {

    @Autowired
    private ChunkVectorRepository vectors;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 向量检索只返回指定知识库的分块() {
        Fixture first = fixture("检索隔离一");
        Fixture second = fixture("检索隔离二");
        float[] query = vector(1.0f, 0.0f);
        vectors.insertAll(first.kbId(), first.docId(), List.of(first.chunkId()),
                List.of(vector(0.8f, 0.2f)));
        vectors.insertAll(second.kbId(), second.docId(), List.of(second.chunkId()),
                List.of(vector(1.0f, 0.0f)));

        List<ChunkVectorRepository.SearchHit> hits = vectors.search(first.kbId(), query, 1);

        assertThat(hits).extracting(ChunkVectorRepository.SearchHit::chunkId)
                .containsExactly(first.chunkId());
    }

    private Fixture fixture(String name) {
        long kbId = jdbc.queryForObject("""
                insert into knowledge_base
                    (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'test', 1024)
                returning id
                """, Long.class, name, name);
        long docId = jdbc.queryForObject("""
                insert into source_document
                    (kb_id, name, source_type, storage_object_id)
                values (?, 'doc.txt', 'FILE', ?)
                returning id
                """, Long.class, kbId, UUID.randomUUID().toString());
        long chunkId = jdbc.queryForObject("""
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content,
                     char_count, token_count, content_hash)
                values (?, ?, 1, 0, 'chunk', 5, 1,
                        '0000000000000000000000000000000000000000000000000000000000000000')
                returning id
                """, Long.class, kbId, docId);
        return new Fixture(kbId, docId, chunkId);
    }

    private float[] vector(float first, float second) {
        float[] vector = new float[1024];
        vector[0] = first;
        vector[1] = second;
        return vector;
    }

    private record Fixture(long kbId, long docId, long chunkId) {}
}
