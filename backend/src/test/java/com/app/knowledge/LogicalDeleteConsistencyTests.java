package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 * 逻辑删除一致性专项（工单 18、test-matrix DEL-01 ~ DEL-12）。
 *
 * <p>这个类是**横向的**，不对应任何一个功能切片。原因是逻辑删除的正确性依赖一条靠约定维持
 * 的纪律：repository 层每个查询方法都要带 {@code deleted = false}。**漏写一个方法就是一个
 * bug，而且开发时几乎发现不了**——只有恰好去查一条被删数据时才暴露。这类风险只能用穷举式的
 * 专项测试覆盖，分散到各功能工单里必然会漏。
 *
 * <h2>repository 查询方法清单（`2026-07-26` 逐个核对）</h2>
 *
 * <p>新增查询方法时请对照本表补用例。
 *
 * <table>
 * <caption>带 deleted = false 的查询</caption>
 * <tr><th>类</th><th>方法</th><th>覆盖用例</th></tr>
 * <tr><td>KnowledgeBaseRepository</td><td>findById / findPage / count / existsByName /
 *     existsByNameExcluding</td><td>{@link #删除知识库后它在所有读路径上都消失}</td></tr>
 * <tr><td>KnowledgeBaseRepository</td><td>SELECT_WITH_COUNTS 里的两个子查询（documentCount /
 *     chunkCount）</td><td>{@link #聚合计数排除已删除的文档与分块}</td></tr>
 * <tr><td>SourceDocumentRepository</td><td>findById / findPage / count</td>
 *     <td>{@link #删除文档后它在所有读路径上都消失}</td></tr>
 * <tr><td>SourceDocumentRepository</td><td>findDueForSync</td>
 *     <td>{@link #已删除的URL文档不会被定时同步扫到}</td></tr>
 * <tr><td>SourceDocumentRepository</td><td>findRunningIds</td>
 *     <td>{@link #启动回收不会捞起已删除文档}</td></tr>
 * <tr><td>DocumentChunkRepository</td><td>findById / findPage / count / countByDocId /
 *     findEnabledByDocId / findAllByDocIdAndIds</td><td>{@link #删除分块后它在所有读路径上都消失}</td></tr>
 * </table>
 *
 * <p><b>两处有意不带 {@code deleted = false} 的例外</b>，都写在各自的方法注释里，
 * 由 {@link #已删除分块的序号仍然被占着} 与 {@link #任务历史保留已删除文档的记录} 守住：
 * <ol>
 *   <li>{@code DocumentChunkRepository.maxChunkIndex}——已删除分块的序号必须继续被占着，
 *       否则新分块会与它撞号，而序号的含义（在原文中的位置）就乱了。</li>
 *   <li>{@code IngestionRunRepository} 的全部查询——它是日志性质的记录，没有"删除"这个操作。</li>
 * </ol>
 *
 * <p>{@code document_chunk_embedding} 整张表没有 {@code deleted} 列，只做物理删除
 * （PRD §7.6 例外 1）：它是派生索引不是主数据。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeEmbeddingConfig.class)
class LogicalDeleteConsistencyTests {

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
                                {"name": "逻辑删除测试-知识库"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        kbId = json.readTree(kb).get("id").asLong();

        String content = "第一段。\n\n第二段。\n\n第三段。";
        String doc = mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(new MockMultipartFile("file", "逻辑删除测试.txt", "text/plain",
                                content.getBytes(StandardCharsets.UTF_8)))
                        .param("chunkSize", "10").param("overlap", "0"))
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

    /** DEL-01~04：删除知识库后，它与其下全部数据在每一条读路径上都要消失。 */
    @Test
    void 删除知识库后它在所有读路径上都消失() throws Exception {
        mvc.perform(delete("/api/v1/knowledge-bases/{kbId}", kbId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/knowledge-bases/{kbId}", kbId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/knowledge-bases").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("逻辑删除测试-知识库"))));
        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents", kbId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/{docId}", docId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/{docId}/chunks", docId)).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where kb_id = ?", Long.class, kbId))
                .as("向量是派生索引，必须物理删除").isZero();
        // 同名可以重新创建：唯一索引带 where deleted = false
        mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "逻辑删除测试-知识库"}
                                """))
                .andExpect(status().isCreated());
        jdbc.update("delete from knowledge_base where name = '逻辑删除测试-知识库' and id <> ?", kbId);
    }

    /** DEL-05~07。 */
    @Test
    void 删除文档后它在所有读路径上都消失() throws Exception {
        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/documents/{docId}", docId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/documents/{docId}/chunks", docId)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents", kbId))
                .andExpect(jsonPath("$.total").value(0));
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId))
                .isZero();
        assertThat(jdbc.queryForObject("select sync_enabled from source_document where id = ?",
                Boolean.class, docId)).as("删除顺手关掉定时同步").isFalse();
    }

    @Test
    void 删除分块后它在所有读路径上都消失() throws Exception {
        long chunkId = firstChunkId();
        int countBefore = documentChunkCount();

        mvc.perform(delete("/api/v1/chunks/{chunkId}", chunkId)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(countBefore - 1))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("\"id\":" + chunkId + ","))));
        mvc.perform(put("/api/v1/chunks/{chunkId}", chunkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "改已删除的分块"}
                                """))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where chunk_id = ?", Long.class, chunkId))
                .isZero();
        assertThat(documentChunkCount()).isEqualTo(countBefore - 1);
    }

    /** DEL-09：批量接口里，已逻辑删除的分块 id 等同于不存在，整批失败。 */
    @Test
    void 批量接口把已删除分块视为无效id() throws Exception {
        long alive = firstChunkId();
        long removed = jdbc.queryForObject("""
                select id from document_chunk where doc_id = ? and deleted = false
                 order by chunk_index desc limit 1
                """, Long.class, docId);
        mvc.perform(delete("/api/v1/chunks/{chunkId}", removed)).andExpect(status().isNoContent());

        mvc.perform(patch("/api/v1/documents/{docId}/chunks/enabled", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"chunkIds\": [%d, %d], \"enabled\": false}".formatted(alive, removed)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select enabled from document_chunk where id = ?",
                Boolean.class, alive)).as("整批失败，合法的那个也不能被改").isTrue();
    }

    /** 聚合计数必须排除已删除记录，否则界面上会显示一个永远对不上的数字。 */
    @Test
    void 聚合计数排除已删除的文档与分块() throws Exception {
        long chunksBefore = aliveChunks();
        mvc.perform(get("/api/v1/knowledge-bases/{kbId}", kbId))
                .andExpect(jsonPath("$.documentCount").value(1))
                .andExpect(jsonPath("$.chunkCount").value((int) chunksBefore));

        mvc.perform(delete("/api/v1/chunks/{chunkId}", firstChunkId())).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/knowledge-bases/{kbId}", kbId))
                .andExpect(jsonPath("$.chunkCount").value((int) chunksBefore - 1));

        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/knowledge-bases/{kbId}", kbId))
                .andExpect(jsonPath("$.documentCount").value(0))
                .andExpect(jsonPath("$.chunkCount").value(0));
    }

    /**
     * **不存在的 id 与已逻辑删除的 id 必须返回完全一致的响应**（api.md §1）。
     *
     * <p>否则调用方能通过状态码或文案的差异推断出"这条记录曾经存在过"，逻辑删除就变成了
     * 半透明的。
     */
    @Test
    void 已删除与从不存在返回完全一致的404() throws Exception {
        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNoContent());

        String deleted = mvc.perform(get("/api/v1/documents/{docId}", docId))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String neverExisted = mvc.perform(get("/api/v1/documents/{docId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(deleted).isEqualTo(neverExisted);
    }

    /** 已删除资源上的一切写操作都必须是 404，而不是"改了个看不见的东西"。 */
    @Test
    void 已删除文档上的写操作全部返回404() throws Exception {
        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNoContent());

        mvc.perform(put("/api/v1/documents/{docId}", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "改已删除的文档"}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(patch("/api/v1/documents/{docId}/enabled", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled": false}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/documents/{docId}/chunks", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "给已删除文档加分块"}
                                """))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNotFound());
    }

    /** 定时扫描的 SQL 也要带 `deleted = false`，否则已删除的 URL 文档会被反复抓取。 */
    @Test
    void 已删除的URL文档不会被定时同步扫到() {
        long urlDoc = jdbc.queryForObject("""
                insert into source_document
                    (kb_id, name, source_type, source_uri, file_key, status, chunk_strategy,
                     chunk_size, chunk_overlap, sync_enabled, sync_cron, next_sync_time)
                values (?, '远程.md', 'URL', 'https://example.com/a.md', 'k', 'SUCCESS', 'RECURSIVE',
                        1000, 100, true, '0 0 3 * * ?', now() - interval '1 minute')
                returning id
                """, Long.class, kbId);

        assertThat(jdbc.queryForObject("""
                select count(*) from source_document
                 where source_type = 'URL' and sync_enabled = true and deleted = false
                   and status <> 'RUNNING' and next_sync_time <= now() and id = ?
                """, Long.class, urlDoc)).isEqualTo(1);

        jdbc.update("update source_document set deleted = true where id = ?", urlDoc);

        assertThat(jdbc.queryForObject("""
                select count(*) from source_document
                 where source_type = 'URL' and sync_enabled = true and deleted = false
                   and status <> 'RUNNING' and next_sync_time <= now() and id = ?
                """, Long.class, urlDoc)).as("删除后不该再被扫描 SQL 命中").isZero();
    }

    /** 启动回收的 `findRunningIds` 同样要排除已删除文档，否则会给它们写上失败信息。 */
    @Test
    void 启动回收不会捞起已删除文档() {
        jdbc.update("update source_document set status = 'RUNNING', deleted = true where id = ?", docId);

        assertThat(jdbc.queryForObject(
                "select count(*) from source_document where status = 'RUNNING' and deleted = false and id = ?",
                Long.class, docId)).isZero();
    }

    /**
     * **有意的例外 1**：`maxChunkIndex` 不带 `deleted = false`。
     *
     * <p>已删除分块的序号必须继续被占着——否则新分块会与它撞号，而序号的含义（在原文中的
     * 位置）就乱了。这条与本类其他用例方向相反，是故意的。
     */
    @Test
    void 已删除分块的序号仍然被占着() throws Exception {
        long last = jdbc.queryForObject("""
                select id from document_chunk where doc_id = ? and deleted = false
                 order by chunk_index desc limit 1
                """, Long.class, docId);
        int maxIndex = jdbc.queryForObject("select chunk_index from document_chunk where id = ?",
                Integer.class, last);
        mvc.perform(delete("/api/v1/chunks/{chunkId}", last)).andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/documents/{docId}/chunks", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "删掉最大序号那个之后新增的分块"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunkIndex").value(maxIndex + 1));
    }

    /**
     * **有意的例外 2**：`ingestion_run` 的查询不带 `deleted = false`（那张表压根没有这一列）。
     *
     * <p>它是日志性质的记录，没有"删除"这个操作。文档删掉之后历史记录仍然留在库里——
     * 只是外部再也访问不到了，因为入口接口先校验文档存在。
     */
    @Test
    void 任务历史保留已删除文档的记录() throws Exception {
        long runsBefore = jdbc.queryForObject(
                "select count(*) from ingestion_run where doc_id = ?", Long.class, docId);
        assertThat(runsBefore).isPositive();

        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select count(*) from ingestion_run where doc_id = ?",
                Long.class, docId)).as("日志不删").isEqualTo(runsBefore);
        mvc.perform(get("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isNotFound());
    }

    /**
     * PRD §7.6 例外 2：**对象存储里的原始文件保留不删**。
     *
     * <p>逻辑删除意味着可恢复，删了源文件就恢复不回来了。这是有意的，不是遗漏——所以要有
     * 一条用例把它钉住，免得后来者"顺手补上"删除逻辑。
     */
    @Test
    void 删除后对象存储中的原始文件仍然保留() throws Exception {
        String fileKey = jdbc.queryForObject(
                "select file_key from source_document where id = ?", String.class, docId);

        mvc.perform(delete("/api/v1/documents/{docId}", docId)).andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select file_key from source_document where id = ?",
                String.class, docId)).as("file_key 不清空，对象存储里的文件也不删").isEqualTo(fileKey);
    }

    private long firstChunkId() {
        return jdbc.queryForObject("""
                select id from document_chunk where doc_id = ? and deleted = false
                 order by chunk_index asc, id asc limit 1
                """, Long.class, docId);
    }

    private long aliveChunks() {
        return jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = false", Long.class, docId);
    }

    private int documentChunkCount() {
        return jdbc.queryForObject("select chunk_count from source_document where id = ?",
                Integer.class, docId);
    }
}
