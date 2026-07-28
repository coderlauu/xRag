package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 文档更新 / 启禁用 / 删除的集成测试（test-matrix DOC-2x、DEL-05~07）。
 *
 * <p>本组用例的核心是**向量表的不变量**：表中每一行都必须对应一个未删除、已启用、且所属
 * 文档也未删除已启用的分块。文档级的禁用与删除都是这条不变量最容易被破坏的地方——只改
 * 数据库标记而忘了清向量，检索侧完全看不出来，症状是"明明禁用了却还能被检索到"。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeEmbeddingConfig.class)
class DocumentManagementIntegrationTests {

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
                                {"name": "文档管理测试-知识库"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        kbId = json.readTree(kb).get("id").asLong();

        String content = "第一段。\n\n第二段。\n\n第三段。\n\n第四段。";
        String doc = mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(new MockMultipartFile("file", "文档管理测试.txt", "text/plain",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .param("chunkSize", "10")
                        .param("overlap", "0"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        docId = json.readTree(doc).get("id").asLong();

        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select status from source_document where id = ?",
                        String.class, docId)).isEqualTo("SUCCESS"));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
        jdbc.update("delete from ingestion_run where kb_id = ?", kbId);
        jdbc.update("delete from document_chunk where kb_id = ?", kbId);
        jdbc.update("delete from source_document where kb_id = ?", kbId);
        jdbc.update("delete from knowledge_base where id = ?", kbId);
    }

    @Test
    void 只更新传了的字段其余保持不变() throws Exception {
        int sizeBefore = jdbc.queryForObject(
                "select chunk_size from source_document where id = ?", Integer.class, docId);

        mvc.perform(put("/api/v1/documents/{docId}", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "改过名的文档.txt"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("改过名的文档.txt"))
                // 分块参数在响应里是嵌套的 chunkConfig 对象，不是平铺字段（api.md §3）
                .andExpect(jsonPath("$.chunkConfig.chunkSize").value(sizeBefore))
                .andExpect(jsonPath("$.needsRechunk").value(false));
    }

    /** 改了分块参数要提示需要重新分块，但**不能自动重新分块**——那是在替用户花钱。 */
    @Test
    void 改分块参数返回needsRechunk且不自动重新分块() throws Exception {
        int revisionBefore = jdbc.queryForObject(
                "select revision from source_document where id = ?", Integer.class, docId);
        long chunksBefore = aliveChunks();

        mvc.perform(put("/api/v1/documents/{docId}", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"chunkStrategy": "FIXED_SIZE", "chunkSize": 500, "overlap": 50}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.chunkStrategy").value("FIXED_SIZE"))
                .andExpect(jsonPath("$.needsRechunk").value(true));

        // 给派发器足够的时间证明它确实没被触发
        Thread.sleep(2500);
        assertThat(jdbc.queryForObject("select revision from source_document where id = ?",
                Integer.class, docId)).as("不能自动重新分块").isEqualTo(revisionBefore);
        assertThat(aliveChunks()).isEqualTo(chunksBefore);
    }

    /**
     * 校验的是**合并之后**的取值。只传 overlap 时也要和库里现有的 chunkSize 比——否则能存进
     * 一个 `overlap >= chunkSize` 的组合，等到下次分块才在算法里炸开（那时 step <= 0，
     * 固定长度切分会原地打转停不下来）。
     */
    @Test
    void 只传overlap时也按库中chunkSize校验() throws Exception {
        mvc.perform(put("/api/v1/documents/{docId}", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"overlap": 999}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    /** FILE 来源没有来源地址与定时同步，传了要在接口层就说清楚，而不是让数据库约束报出来。 */
    @Test
    void FILE来源传同步字段返回400() throws Exception {
        mvc.perform(put("/api/v1/documents/{docId}", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"syncEnabled": true, "syncCron": "0 0 3 * * ?"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("本地上传的文档没有来源地址与定时同步，无法修改这几项。"));
    }

    /**
     * DOC-28 与本工单最不直观的一条：**禁用必须物理删除向量**。只改标记位的话，检索直接查
     * 向量表，文档内容照样被召回，"禁用"完全没有效果。
     */
    @Test
    void 禁用文档物理删除向量但分块记录与其enabled都不动() throws Exception {
        long chunksBefore = aliveChunks();
        assertThat(vectorCount()).isEqualTo(chunksBefore);

        mvc.perform(patchEnabled(false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(vectorCount()).as("向量必须被物理删除").isZero();
        assertThat(aliveChunks()).as("分块记录仍在，只是不参与检索").isEqualTo(chunksBefore);
        assertThat(jdbc.queryForObject("""
                select count(*) from document_chunk where doc_id = ? and deleted = false and enabled = true
                """, Long.class, docId)).as("分块自己的 enabled 不动").isEqualTo(chunksBefore);
    }

    /**
     * 上一条"分块 enabled 不动"的意义就在这条：文档禁用再启用之后，用户此前对**个别分块**
     * 的取舍必须原样恢复，而不是被一次文档级操作抹平。
     */
    @Test
    void 禁用再启用后单独禁用过的分块仍然是禁用且没有向量() throws Exception {
        long victim = firstChunkId();
        mvc.perform(patch("/api/v1/chunks/{chunkId}/enabled", victim)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled": false}
                                """))
                .andExpect(status().isOk());
        long enabledChunks = jdbc.queryForObject("""
                select count(*) from document_chunk where doc_id = ? and deleted = false and enabled = true
                """, Long.class, docId);

        mvc.perform(patchEnabled(false)).andExpect(status().isOk());
        mvc.perform(patchEnabled(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        assertThat(vectorCount()).as("只为已启用的分块重算向量").isEqualTo(enabledChunks);
        assertThat(jdbc.queryForObject("select enabled from document_chunk where id = ?",
                Boolean.class, victim)).as("那个分块仍然是禁用的").isFalse();
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where chunk_id = ?", Long.class, victim))
                .as("因此它也不该有向量").isZero();
    }

    /** 幂等：已是目标状态直接返回，一次 Embedding 都不该发生。 */
    @Test
    void 重复设置为同一启用状态不产生向量操作() throws Exception {
        int callsBefore = FakeEmbeddingConfig.CALLS.get();
        long vectorsBefore = vectorCount();

        mvc.perform(patchEnabled(true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        assertThat(FakeEmbeddingConfig.CALLS.get()).isEqualTo(callsBefore);
        assertThat(vectorCount()).isEqualTo(vectorsBefore);
    }

    /** `revision = 0`（从未成功分块）时启用：无向量可写，正常返回而不是报错。 */
    @Test
    void 从未分块过的文档启用不报错() throws Exception {
        long pendingDoc = jdbc.queryForObject("""
                insert into source_document
                    (kb_id, name, source_type, file_key, storage_object_id,
                     status, revision, chunk_strategy,
                     chunk_size, chunk_overlap, enabled)
                values (?, '没处理过.txt', 'FILE', 'never.txt', 'never-processed-document',
                        'PENDING', 0, 'RECURSIVE', 1000, 100, false)
                returning id
                """, Long.class, kbId);

        mvc.perform(patch("/api/v1/documents/{docId}/enabled", pendingDoc)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    /** DEL-05~07：删除后各接口都不再返回它，向量物理删除，**对象存储原始文件保留**。 */
    @Test
    void 删除文档后分块不可见向量已清理而源文件保留() throws Exception {
        String fileKey = jdbc.queryForObject(
                "select file_key from source_document where id = ?", String.class, docId);

        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/documents/{docId}", docId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/{docId}/chunks", docId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents", kbId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        assertThat(vectorCount()).isZero();
        assertThat(aliveChunks()).as("分块是逻辑删除，行还在但查不到").isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = true", Long.class, docId))
                .isPositive();
        // PRD §7.6 例外 2：逻辑删除意味着可恢复，删了源文件就恢复不回来了
        assertThat(fileKey).isNotBlank();
        assertThat(jdbc.queryForObject(
                "select file_key from source_document where id = ?", String.class, docId))
                .as("file_key 保留，对象存储里的原始文件不删").isEqualTo(fileKey);
        // 删除顺手关掉定时同步，让定时扫描的部分索引不再覆盖这行
        assertThat(jdbc.queryForObject("select sync_enabled from source_document where id = ?",
                Boolean.class, docId)).isFalse();
    }

    /** DOC-23/26：`RUNNING` 是排他状态，三种写操作都要被挡住。 */
    @Test
    void 处理中的文档更新启禁用删除三种操作都返回409() throws Exception {
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);

        mvc.perform(put("/api/v1/documents/{docId}", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "处理中改名"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_PROCESSING"))
                .andExpect(jsonPath("$.message").value("文档正在处理中，请等待处理完成后再操作。"));

        mvc.perform(patchEnabled(false)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_PROCESSING"));

        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_PROCESSING"));
    }

    /** 禁用文档后连"给它触发分块"也该被挡住，否则又会写出向量来。 */
    @Test
    void 禁用的文档不能触发分块() throws Exception {
        mvc.perform(patchEnabled(false)).andExpect(status().isOk());

        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("文档已禁用，无法处理。请先启用文档。"));
    }

    /** 查看源文档（PRD §4.2 增补）：返回的是文件本体，且内容与当初上传的逐字节一致。 */
    @Test
    void 下载源文件返回原始内容与文件名() throws Exception {
        byte[] body = mvc.perform(get("/api/v1/documents/{docId}/file", docId))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("inline")))
                // 文档名是中文，必须走 RFC 5987 的 filename* 编码，否则用户拿到一串乱码文件名
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();

        assertThat(new String(body, StandardCharsets.UTF_8))
                .as("内容要与上传的逐字节一致").isEqualTo("第一段。\n\n第二段。\n\n第三段。\n\n第四段。");
    }

    /**
     * **禁用与处理中都不拦这个接口**。禁用针对的是"参与检索"，与"能不能看原件"无关——
     * 禁用一份文档之后连原文都打不开，用户就没法判断该不该重新启用它。
     */
    @Test
    void 禁用或处理中的文档源文件仍可打开() throws Exception {
        mvc.perform(patchEnabled(false)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/documents/{docId}/file", docId)).andExpect(status().isOk());

        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);
        mvc.perform(get("/api/v1/documents/{docId}/file", docId)).andExpect(status().isOk());
    }

    /** 已删除的文档拿不到源文件——尽管对象存储里那个文件确实还在（PRD §7.6 例外 2）。 */
    @Test
    void 已删除文档的源文件不可访问() throws Exception {
        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/documents/{docId}/file", docId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("文档不存在或已被删除。"));
    }

    /**
     * "文档在、但存储里找不到源文件"与"文档不存在"是两种不同的 404，`message` 必须区分开。
     *
     * <p>前者是运维动作（存储空间回收）留下的可能状态，只有它需要找运维；后者是用户自己删的。
     */
    @Test
    void 源文件缺失时的404与文档不存在区分开() throws Exception {
        jdbc.update("update source_document set file_key = 'knowledge-base/nonexistent.txt' where id = ?",
                docId);

        mvc.perform(get("/api/v1/documents/{docId}/file", docId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(
                        "源文件已不可用。文档记录仍在，但存储中的原始文件缺失，可能已被清理。"));
    }

    @Test
    void 不存在的文档返回404() throws Exception {
        mvc.perform(delete("/api/v1/documents/{docId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    private MockHttpServletRequestBuilder patchEnabled(boolean enabled) {
        return patch("/api/v1/documents/{docId}/enabled", docId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": %s}".formatted(enabled));
    }

    private long aliveChunks() {
        return jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = false", Long.class, docId);
    }

    private long vectorCount() {
        return jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);
    }

    private long firstChunkId() {
        return jdbc.queryForObject("""
                select id from document_chunk where doc_id = ? and deleted = false
                 order by chunk_index asc, id asc limit 1
                """, Long.class, docId);
    }
}
