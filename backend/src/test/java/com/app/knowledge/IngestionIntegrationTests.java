package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 异步入库全链路的集成测试。
 *
 * <p>**本类不能加 {@code @Transactional}**：真正的执行发生在派发器的工作线程里，测试线程的
 * 事务对它不可见，加了反而看不到任何结果。代价是要自己清理数据——用 {@link #cleanUp()}
 * 按名字前缀删掉本类造的知识库（连带文档、分块、向量）。
 *
 * <p>Embedding 用 {@link FakeEmbeddingConfig} 的确定性假实现，不打真实 API：这里要验的是链路
 * 和事务边界，不是模型质量。**所有集成测试类都要导入同一个 config**，理由见那个类的注释——
 * 不共享上下文会让两个调度器同时活着，互相把对方的任务判成僵尸。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeEmbeddingConfig.class)
class IngestionIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private long kbId;
    private long docId;

    @BeforeEach
    void setUp() throws Exception {
        FakeEmbeddingConfig.SHOULD_FAIL.set(false);
        String kb = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "入库测试-知识库"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        kbId = json.readTree(kb).get("id").asLong();

        String content = "第一段内容，用于验证入库链路。\n\n第二段内容。\n\n第三段内容。";
        String doc = mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(new MockMultipartFile("file", "入库测试.txt", "text/plain",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .param("chunkSize", "20")
                        .param("overlap", "0"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        docId = json.readTree(doc).get("id").asLong();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
        jdbc.update("delete from ingestion_run where kb_id = ?", kbId);
        jdbc.update("delete from document_chunk where kb_id = ?", kbId);
        jdbc.update("delete from source_document where kb_id = ?", kbId);
        jdbc.update("delete from knowledge_base where id = ?", kbId);
    }

    private long trigger() throws Exception {
        String body = mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("runId").asLong();
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select status from source_document where id = ?",
                        String.class, docId)).isEqualTo(expected));
    }

    @Test
    void 成功后分块与向量行数一致且属于新revision() throws Exception {
        trigger();
        awaitStatus("SUCCESS");

        Integer revision = jdbc.queryForObject(
                "select revision from source_document where id = ?", Integer.class, docId);
        assertThat(revision).isEqualTo(1);

        Long chunkCount = jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = false", Long.class, docId);
        Long vectorCount = jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);
        assertThat(chunkCount).isPositive().isEqualTo(vectorCount);

        // 全部属于最新 revision
        Long staleCount = jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = false and revision <> ?",
                Long.class, docId, revision);
        assertThat(staleCount).isZero();

        // 文档上的冗余计数与实际行数一致
        assertThat(jdbc.queryForObject("select chunk_count from source_document where id = ?",
                Integer.class, docId)).isEqualTo(chunkCount.intValue());
    }

    /** 重新分块 = 删旧插新。旧 revision 的分块必须全部逻辑删除，其向量必须已被物理删除。 */
    @Test
    void 重新分块后旧版本分块被逻辑删除且向量已物理删除() throws Exception {
        trigger();
        awaitStatus("SUCCESS");
        long firstRevisionVectors = jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);

        trigger();
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select revision from source_document where id = ?",
                        Integer.class, docId)).isEqualTo(2));
        awaitStatus("SUCCESS");

        Long oldAlive = jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and revision = 1 and deleted = false",
                Long.class, docId);
        assertThat(oldAlive).as("旧 revision 的分块应全部逻辑删除").isZero();

        Long vectors = jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);
        Long newChunks = jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and revision = 2 and deleted = false",
                Long.class, docId);
        assertThat(vectors).as("向量表只应剩新版本的").isEqualTo(newChunks).isEqualTo(firstRevisionVectors);
    }

    /** 处理中重复触发必须 409，且 message 是能直接展示给用户的完整句子。 */
    @Test
    void 处理中重复触发返回409() throws Exception {
        // 直接把文档置为 RUNNING，避免依赖真实执行的时间窗口
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);

        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_PROCESSING"))
                .andExpect(jsonPath("$.message").value("文档正在处理中，请等待处理完成后再操作。"));
    }

    @Test
    void 禁用文档触发返回409且提示先启用() throws Exception {
        jdbc.update("update source_document set enabled = false where id = ?", docId);

        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("文档已禁用，无法处理。请先启用文档。"));
    }

    /**
     * 失败路径的核心断言：Embedding 抛异常后，文档变 FAILED、phase 停在 EMBED，
     * **且库里没有留下任何分块**——第 5 步的事务根本没开始，不存在半截数据。
     */
    @Test
    void Embedding失败时文档标记失败且没有留下分块() throws Exception {
        FakeEmbeddingConfig.SHOULD_FAIL.set(true);

        trigger();
        awaitStatus("FAILED");

        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ?", Long.class, docId))
                .as("失败时不应留下任何分块").isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select phase from ingestion_run where doc_id = ? order by id desc limit 1",
                String.class, docId)).isEqualTo("EMBED");
        assertThat(jdbc.queryForObject(
                "select status from ingestion_run where doc_id = ? order by id desc limit 1",
                String.class, docId)).isEqualTo("FAILED");
        // 失败后文档能被重新触发——不需要重新上传（PRD §4.2）
        assertThat(jdbc.queryForObject("select error_message from source_document where id = ?",
                String.class, docId)).isNotBlank();
    }

    @Test
    void 文档详情带最近一次任务摘要() throws Exception {
        trigger();
        awaitStatus("SUCCESS");

        mvc.perform(get("/api/v1/documents/{docId}", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.latestRun.status").value("SUCCESS"))
                .andExpect(jsonPath("$.latestRun.triggerSource").value("MANUAL"))
                .andExpect(jsonPath("$.latestRun.chunkCount").isNumber());
    }

    @Test
    void 任务历史按时间倒序返回() throws Exception {
        trigger();
        awaitStatus("SUCCESS");
        trigger();
        awaitStatus("SUCCESS");

        mvc.perform(get("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.items[0].revision").value(2))
                .andExpect(jsonPath("$.items[1].revision").value(1));
    }
}
