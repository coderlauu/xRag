package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * 分块查看与编辑的集成测试（test-matrix CHK-13/14、CHK-20~24）。
 *
 * <p>分块与向量由真实的入库链路产生，而不是手工 insert 造数据：本类要验的恰恰是
 * "编辑之后库里两张表还一致"，用手工造的数据当起点，起点本身就没被验证过。
 *
 * <p>与其他集成测试类一样导入 {@link FakeEmbeddingConfig}——必须共享同一个 Spring 上下文，
 * 理由见那个类的注释。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeEmbeddingConfig.class)
class ChunkIntegrationTests {

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
                                {"name": "分块测试-知识库"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        kbId = json.readTree(kb).get("id").asLong();

        String content = "第一段内容。\n\n第二段内容。\n\n第三段内容。\n\n第四段内容。";
        String doc = mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(new MockMultipartFile("file", "分块测试.txt", "text/plain",
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

    /**
     * CHK-13。判别方式是插一条**序号最小但 id 最大**的分块：按 `chunk_index` 排它在最前，
     * 按 id 排它在最后，两种实现给出的结果完全不同，断言才有区分力。
     */
    @Test
    void 分块列表按序号升序而不是按id() throws Exception {
        insertChunk(0, "手动补的第 0 段");

        String body = mvc.perform(get("/api/v1/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        List<Integer> indexes = json.readTree(body).get("items").findValuesAsText("chunkIndex")
                .stream().map(Integer::valueOf).toList();
        assertThat(indexes).as("按 chunk_index 升序；按 id 排会得到 [0,1,2,3,0]")
                .isSorted()
                .startsWith(0, 0);
    }

    /** CHK-14。逻辑删除的分块在列表里必须彻底消失，`total` 也要跟着少 1。 */
    @Test
    void 已逻辑删除的分块不出现在列表里() throws Exception {
        long total = chunkCount();
        long victim = firstChunkId();
        jdbc.update("update document_chunk set deleted = true where id = ?", victim);

        String body = mvc.perform(get("/api/v1/documents/{docId}/chunks", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(total - 1))
                .andReturn().getResponse().getContentAsString();

        assertThat(json.readTree(body).get("items").findValuesAsText("id"))
                .doesNotContain(String.valueOf(victim));
    }

    /**
     * CHK-20。三处断言缺一不可：派生字段重算、向量表仍**只有一行**（删旧插新用的是同一个
     * 主键，写错成 insert-only 会留下两行）、向量值确实变了。
     */
    @Test
    void 修改内容后派生字段与向量都更新() throws Exception {
        long chunkId = firstChunkId();
        String hashBefore = contentHashOf(chunkId);
        String vectorBefore = vectorOf(chunkId);

        mvc.perform(put("/api/v1/chunks/{chunkId}", chunkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "修正后的内容，比原来长一些，用于验证字符数会跟着重算。"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("修正后的内容，比原来长一些，用于验证字符数会跟着重算。"))
                .andExpect(jsonPath("$.charCount").value(27))
                .andExpect(jsonPath("$.tokenCount").isNumber());

        assertThat(contentHashOf(chunkId)).as("content_hash 必须重算").isNotEqualTo(hashBefore);
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where chunk_id = ?", Long.class, chunkId))
                .as("删旧插新是同一个主键，不应出现两行").isEqualTo(1);
        assertThat(vectorOf(chunkId)).as("内容变了向量必须跟着变").isNotEqualTo(vectorBefore);
    }

    /**
     * CHK-21。**断言的是"一次 Embedding 调用都没发生"**，不是"结果看起来没变"——确定性
     * 假实现对同样内容会算出同样向量，只看向量值根本区分不出有没有重算。`create_time`
     * 未变化则证明那一行没被删过再插回来。
     */
    @Test
    void 提交相同内容时不触发任何向量操作() throws Exception {
        long chunkId = firstChunkId();
        String original = jdbc.queryForObject(
                "select content from document_chunk where id = ?", String.class, chunkId);
        OffsetDateTime createdBefore = vectorCreateTimeOf(chunkId);

        int callsBefore = FakeEmbeddingConfig.CALLS.get();
        mvc.perform(put("/api/v1/chunks/{chunkId}", chunkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new UpdateBody(original))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(original));

        assertThat(FakeEmbeddingConfig.CALLS.get()).as("内容没变就不该调用 Embedding")
                .isEqualTo(callsBefore);
        assertThat(vectorCreateTimeOf(chunkId)).as("向量行没被删旧插新")
                .isEqualTo(createdBefore);
    }

    /** CHK-22。禁用的分块没有向量，编辑内容也不该让它凭空出现一条。 */
    @Test
    void 编辑禁用的分块不会写入向量() throws Exception {
        long chunkId = firstChunkId();
        jdbc.update("update document_chunk set enabled = false where id = ?", chunkId);
        jdbc.update("delete from document_chunk_embedding where chunk_id = ?", chunkId);

        int callsBefore = FakeEmbeddingConfig.CALLS.get();
        mvc.perform(put("/api/v1/chunks/{chunkId}", chunkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "禁用状态下改的内容。"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("禁用状态下改的内容。"))
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where chunk_id = ?", Long.class, chunkId))
                .as("禁用状态不因编辑而恢复向量").isZero();
        assertThat(FakeEmbeddingConfig.CALLS.get()).as("禁用的分块连 Embedding 都不必调")
                .isEqualTo(callsBefore);
    }

    /** CHK-23。删一个分块只影响它自己——同文档其他分块的向量必须一行不少。 */
    @Test
    void 删除分块后向量物理删除且计数递减() throws Exception {
        long chunkId = firstChunkId();
        int countBefore = documentChunkCount();
        long vectorsBefore = jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);

        mvc.perform(delete("/api/v1/chunks/{chunkId}", chunkId))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForObject("select deleted from document_chunk where id = ?",
                Boolean.class, chunkId)).isTrue();
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where chunk_id = ?", Long.class, chunkId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId))
                .as("同文档其他分块的向量不受影响").isEqualTo(vectorsBefore - 1);
        assertThat(documentChunkCount()).isEqualTo(countBefore - 1);
    }

    /** CHK-24。冗余计数与实际行数不一致时，递减不能把它带成负数。 */
    @Test
    void 计数已为0时删除分块不会变成负数() throws Exception {
        jdbc.update("update source_document set chunk_count = 0 where id = ?", docId);

        mvc.perform(delete("/api/v1/chunks/{chunkId}", firstChunkId()))
                .andExpect(status().isNoContent());

        assertThat(documentChunkCount()).isZero();
    }

    /** ui-spec §3：`RUNNING` 期间旧分块随时会被整批替换，此刻的编辑改了也留不住。 */
    @Test
    void 处理中编辑或删除分块返回409() throws Exception {
        long chunkId = firstChunkId();
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);

        mvc.perform(put("/api/v1/chunks/{chunkId}", chunkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "处理中改的内容"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_PROCESSING"))
                .andExpect(jsonPath("$.message").value("文档正在处理中，请等待处理完成后再操作。"));

        mvc.perform(delete("/api/v1/chunks/{chunkId}", chunkId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_PROCESSING"));
    }

    @Test
    void 内容为空返回400() throws Exception {
        mvc.perform(put("/api/v1/chunks/{chunkId}", firstChunkId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    /**
     * CHK-37。**编辑分块是第一条同步调用 Embedding 的路径**，入库那条是异步的、失败写进任务
     * 记录，永远走不到这个错误码上。
     *
     * <p>第二条断言比状态码更重要：内容必须**原封不动**。Embedding 在事务外算完才开事务，
     * 所以它失败时事务根本没开始，不存在"内容改了但向量还是旧的"这种半截状态。
     */
    @Test
    void Embedding失败时返回502且内容未被修改() throws Exception {
        long chunkId = firstChunkId();
        String original = jdbc.queryForObject(
                "select content from document_chunk where id = ?", String.class, chunkId);
        FakeEmbeddingConfig.SHOULD_FAIL.set(true);

        mvc.perform(put("/api/v1/chunks/{chunkId}", chunkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "供应商故障时提交的新内容"}
                                """))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("EMBEDDING_FAILED"));

        assertThat(jdbc.queryForObject("select content from document_chunk where id = ?",
                String.class, chunkId)).as("事务在 Embedding 之后才开始，失败时内容不该被改").isEqualTo(original);
    }

    @Test
    void 不存在的分块返回404() throws Exception {
        mvc.perform(delete("/api/v1/chunks/{chunkId}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    /**
     * CHK-15/16 + DEL-08，**本工单最值钱的一条**。
     *
     * <p>造的局面是"最大序号那个分块恰好已被删除"：存活分块的最大序号比全表最大序号小。
     * 用 {@code count(*)} 或只数存活行都会算出一个已经被占用的序号，只有把已删除分块的序号
     * 继续算进去才对。删掉中间的 #1 是为了让 count 与 max 也对不上——两种错误实现都会被这
     * 一条抓住。
     */
    @Test
    void 新增分块的序号取全表max加1而不是count() throws Exception {
        // 起点：入库切出 4 个分块，序号 0~3
        assertThat(chunkCount()).isEqualTo(4);
        long middle = chunkIdOfIndex(1);
        long last = chunkIdOfIndex(3);
        mvc.perform(delete("/api/v1/chunks/{chunkId}", middle)).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/chunks/{chunkId}", last)).andExpect(status().isNoContent());
        // 此刻：存活 2 个（#0、#2），全表最大序号仍是 3

        mvc.perform(post("/api/v1/documents/{docId}/chunks", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "手动补的一段，序号应当是 4。"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunkIndex").value(4))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    /** CHK-19。指定已存在的序号 → 两条同号分块共存，**其他分块的序号一个都不动**。 */
    @Test
    void 指定已存在的序号不重排其他分块() throws Exception {
        List<Integer> before = allIndexes();

        mvc.perform(post("/api/v1/documents/{docId}/chunks", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "插在第 2 段旁边的补充说明。", "chunkIndex": 1}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunkIndex").value(1));

        assertThat(allIndexes()).as("原有 0,1,2,3 保持不变，新的 1 插进去后是 0,1,1,2,3")
                .containsExactly(0, 1, 1, 2, 3);
        assertThat(before).containsExactly(0, 1, 2, 3);
    }

    /** 新增后文档冗余计数要跟着涨，向量也要立刻写进去（新增即参与检索）。 */
    @Test
    void 新增分块后计数递增且向量已写入() throws Exception {
        int countBefore = documentChunkCount();

        String body = mvc.perform(post("/api/v1/documents/{docId}/chunks", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "新增分块会立刻算向量。"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long newId = json.readTree(body).get("id").asLong();

        assertThat(documentChunkCount()).isEqualTo(countBefore + 1);
        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where chunk_id = ?", Long.class, newId))
                .isEqualTo(1);
    }

    /** CHK-17。父文档禁用时新增分块，会造出"文档不参与检索、但新加这段能被检索到"的矛盾。 */
    @Test
    void 给禁用文档新增分块返回409() throws Exception {
        jdbc.update("update source_document set enabled = false where id = ?", docId);

        mvc.perform(post("/api/v1/documents/{docId}/chunks", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "不该被接受的新分块"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("文档已禁用，无法新增分块。请先启用文档。"));
    }

    /** CHK-18。 */
    @Test
    void 处理中新增分块返回409() throws Exception {
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);

        mvc.perform(post("/api/v1/documents/{docId}/chunks", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "处理中新增的分块"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DOCUMENT_PROCESSING"));
    }

    /** CHK-25。禁用再启用，向量要回来。 */
    @Test
    void 单条启用会重算向量写回() throws Exception {
        long chunkId = firstChunkId();
        mvc.perform(patchEnabled(chunkId, false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(vectorCountOf(chunkId)).as("禁用后向量被物理删除").isZero();

        mvc.perform(patchEnabled(chunkId, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
        assertThat(vectorCountOf(chunkId)).as("启用后向量重新写回").isEqualTo(1);
    }

    /**
     * CHK-26 与 CHK-27 放在一起，因为**这两条的价值就在于对比**：同一个父文档禁用状态下，
     * 启用分块被拒、禁用分块放行。不对称是有意的——把内容从检索里拿掉任何时候都安全，
     * 放进去则要求父文档也认。
     */
    @Test
    void 父文档禁用时启用分块被拒而禁用分块放行() throws Exception {
        long enabledChunk = firstChunkId();
        long disabledChunk = chunkIdOfIndex(1);
        jdbc.update("update document_chunk set enabled = false where id = ?", disabledChunk);
        jdbc.update("delete from document_chunk_embedding where chunk_id = ?", disabledChunk);
        jdbc.update("update source_document set enabled = false where id = ?", docId);

        mvc.perform(patchEnabled(disabledChunk, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("文档已禁用，无法单独启用其中的分块。请先启用文档。"));

        mvc.perform(patchEnabled(enabledChunk, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
        assertThat(vectorCountOf(enabledChunk)).isZero();
    }

    /** CHK-28。幂等的判据是"一次 Embedding 调用都没发生"，不是"结果看起来一样"。 */
    @Test
    void 重复设置为同一状态不产生任何向量操作() throws Exception {
        long chunkId = firstChunkId();
        mvc.perform(patchEnabled(chunkId, false)).andExpect(status().isOk());
        long vectorsAfterDisable = vectorCountOf(chunkId);

        int callsBefore = FakeEmbeddingConfig.CALLS.get();
        mvc.perform(patchEnabled(chunkId, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        assertThat(FakeEmbeddingConfig.CALLS.get()).isEqualTo(callsBefore);
        assertThat(vectorCountOf(chunkId)).isEqualTo(vectorsAfterDisable).isZero();
    }

    /** CHK-29。三个数字必须自洽：`requested == changed + alreadyInTargetState`。 */
    @Test
    void 批量设置返回请求数变更数与已达标数() throws Exception {
        List<Long> ids = List.of(firstChunkId(), chunkIdOfIndex(1), chunkIdOfIndex(2));
        // 先把其中一个单独禁用，让它在这次批量禁用里"已是目标状态"
        mvc.perform(patchEnabled(ids.get(2), false)).andExpect(status().isOk());

        mvc.perform(patchBatch(ids, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(3))
                .andExpect(jsonPath("$.changed").value(2))
                .andExpect(jsonPath("$.alreadyInTargetState").value(1));
    }

    /** CHK-35。全部已达目标不是错误——批量操作里"部分已达目标"是常态。 */
    @Test
    void 全部已是目标状态时返回changed为0而不报错() throws Exception {
        List<Long> ids = List.of(firstChunkId(), chunkIdOfIndex(1));

        mvc.perform(patchBatch(ids, false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2));

        int callsBefore = FakeEmbeddingConfig.CALLS.get();
        mvc.perform(patchBatch(ids, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(2))
                .andExpect(jsonPath("$.changed").value(0))
                .andExpect(jsonPath("$.alreadyInTargetState").value(2));
        assertThat(FakeEmbeddingConfig.CALLS.get()).isEqualTo(callsBefore);
    }

    /** CHK-30。 */
    @Test
    void 批量设置空列表返回400() throws Exception {
        mvc.perform(patchBatch(List.of(), false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("请至少选择一个分块。"));
    }

    /** CHK-31 / CHK-32：边界是**包含** 500 的，501 才拒。 */
    @Test
    void 批量上限500包含边界501拒绝() throws Exception {
        List<Long> bulk = insertBulkChunks(500);

        mvc.perform(patchBatch(bulk, false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(500))
                .andExpect(jsonPath("$.changed").value(500));

        List<Long> tooMany = new ArrayList<>(bulk);
        tooMany.add(firstChunkId());
        mvc.perform(patchBatch(tooMany, true))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("单次最多操作 500 个分块，请分批处理。"));
    }

    /**
     * CHK-33 / CHK-34 / DEL-09，**整批失败的三种触发方式合成一条**：不存在的 id、
     * 属于其他文档的 id、已逻辑删除的 id，三者都必须让整批失败且**一条都没改**。
     *
     * <p>最后那条断言才是重点。只断言 400 的话，"先改一部分再报错"也能过。
     */
    @Test
    void 含无效id时整批失败且数据库无任何变更() throws Exception {
        long valid = firstChunkId();
        long otherDocChunk = insertChunkInOtherDocument();
        long deletedChunk = chunkIdOfIndex(1);
        jdbc.update("update document_chunk set deleted = true where id = ?", deletedChunk);

        for (long invalid : List.of(999_999_999L, otherDocChunk, deletedChunk)) {
            mvc.perform(patchBatch(List.of(valid, invalid), false))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        }

        assertThat(jdbc.queryForObject("select enabled from document_chunk where id = ?",
                Boolean.class, valid)).as("合法的那个也一条都不能改").isTrue();
        assertThat(vectorCountOf(valid)).as("向量也不能动").isEqualTo(1);
    }

    /** 批量启用同样受父文档启用状态限制（与单条一致）。 */
    @Test
    void 父文档禁用时批量启用返回409() throws Exception {
        List<Long> ids = List.of(firstChunkId(), chunkIdOfIndex(1));
        mvc.perform(patchBatch(ids, false)).andExpect(status().isOk());
        jdbc.update("update source_document set enabled = false where id = ?", docId);

        mvc.perform(patchBatch(ids, true))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATE"))
                .andExpect(jsonPath("$.message").value("文档已禁用，无法单独启用其中的分块。请先启用文档。"));

        // 禁用方向不受限制（与单条接口的不对称保持一致）
        mvc.perform(patchBatch(ids, false)).andExpect(status().isOk());
    }

    /** 批量启用后向量要精准补回，且只补本次变更的那些。 */
    @Test
    void 批量启用后向量数与启用分块数一致() throws Exception {
        List<Long> ids = List.of(firstChunkId(), chunkIdOfIndex(1));
        mvc.perform(patchBatch(ids, false)).andExpect(status().isOk());
        assertThat(vectorCountOf(ids.get(0))).isZero();

        mvc.perform(patchBatch(ids, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2));

        assertThat(jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId))
                .isEqualTo(jdbc.queryForObject("""
                        select count(*) from document_chunk
                         where doc_id = ? and deleted = false and enabled = true
                        """, Long.class, docId));
    }

    /** 同一个 id 传两次不是错误，但它只对应一个分块——三个数字必须仍然自洽。 */
    @Test
    void 重复id按去重计数() throws Exception {
        long id = firstChunkId();

        mvc.perform(patchBatch(List.of(id, id), false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.changed").value(1))
                .andExpect(jsonPath("$.alreadyInTargetState").value(0));
    }

    /**
     * CHK-36 与 M-03 的自动化版本，覆盖三条会调用 Embedding 的同步路径。
     *
     * <p>矩阵原本把它列为手工项（跑 psql 盯 `pg_stat_activity` 有没有长事务）。那个办法能
     * 覆盖异步入库链路，但对同步接口来说既贵又容易漏，而**这条纪律最典型的破坏方式就是
     * 给方法加一个 `@Transactional`**——加了之后内部的编程式事务会静默地加入外层事务，
     * 事务边界一路扩张到整个方法，把网络调用圈进去，却没有任何报错。这里一行断言就守住了。
     */
    @Test
    void 三条同步路径的Embedding都发生在事务之外() throws Exception {
        long chunkId = firstChunkId();

        FakeEmbeddingConfig.IN_TRANSACTION_AT_CALL.set(true);
        mvc.perform(put("/api/v1/chunks/{chunkId}", chunkId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "改内容会触发一次向量重算。"}
                                """))
                .andExpect(status().isOk());
        assertThat(FakeEmbeddingConfig.IN_TRANSACTION_AT_CALL.get())
                .as("编辑分块：Embedding 不能在事务里").isFalse();

        FakeEmbeddingConfig.IN_TRANSACTION_AT_CALL.set(true);
        mvc.perform(post("/api/v1/documents/{docId}/chunks", docId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"content": "新增分块也会算一次向量。"}
                                """))
                .andExpect(status().isCreated());
        assertThat(FakeEmbeddingConfig.IN_TRANSACTION_AT_CALL.get())
                .as("新增分块：Embedding 不能在事务里").isFalse();

        List<Long> ids = List.of(chunkId, chunkIdOfIndex(2));
        mvc.perform(patchBatch(ids, false)).andExpect(status().isOk());
        FakeEmbeddingConfig.IN_TRANSACTION_AT_CALL.set(true);
        mvc.perform(patchBatch(ids, true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.changed").value(2));
        assertThat(FakeEmbeddingConfig.IN_TRANSACTION_AT_CALL.get())
                .as("批量启用：Embedding 不能在事务里——这条是本模块设计含量最高的地方").isFalse();
    }

    private MockHttpServletRequestBuilder patchBatch(List<Long> chunkIds, boolean enabled)
            throws Exception {
        return patch("/api/v1/documents/{docId}/chunks/enabled", docId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new BatchBody(chunkIds, enabled)));
    }

    private record BatchBody(List<Long> chunkIds, boolean enabled) {}

    /**
     * 直接建 500 行，不走接口——这条测试要验的是批量接口的上限边界，用 500 次 HTTP 新增
     * 把它铺出来只会让测试慢十几秒，验不到任何额外的东西。
     */
    private List<Long> insertBulkChunks(int count) {
        List<Long> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String content = "批量边界测试分块 " + i;
            ids.add(jdbc.queryForObject("""
                    insert into document_chunk
                        (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                    values (?, ?, 1, ?, ?, ?, ?, 'bulk')
                    returning id
                    """, Long.class, kbId, docId, 1000 + i, content,
                    content.length(), content.length()));
        }
        return ids;
    }

    /** 造一个属于别的文档的分块，用来验"不属于该文档的 id 整批失败"。 */
    private long insertChunkInOtherDocument() {
        Long otherDocId = jdbc.queryForObject("""
                insert into source_document
                    (kb_id, name, source_type, file_key, status, revision, chunk_strategy,
                     chunk_size, chunk_overlap)
                values (?, '另一份文档.txt', 'FILE', 'other.txt', 'SUCCESS', 1, 'RECURSIVE', 1000, 100)
                returning id
                """, Long.class, kbId);
        return jdbc.queryForObject("""
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                values (?, ?, 1, 0, '别的文档的分块', 7, 7, 'other')
                returning id
                """, Long.class, kbId, otherDocId);
    }

    private MockHttpServletRequestBuilder patchEnabled(long chunkId, boolean enabled) {
        return patch("/api/v1/chunks/{chunkId}/enabled", chunkId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\": %s}".formatted(enabled));
    }

    private record UpdateBody(String content) {}

    private void insertChunk(int chunkIndex, String content) {
        jdbc.update("""
                insert into document_chunk
                    (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                values (?, ?, (select revision from source_document where id = ?), ?, ?, ?, ?, 'manual')
                """, kbId, docId, docId, chunkIndex, content, content.length(), content.length());
    }

    private long firstChunkId() {
        return jdbc.queryForObject("""
                select id from document_chunk where doc_id = ? and deleted = false
                 order by chunk_index asc, id asc limit 1
                """, Long.class, docId);
    }

    private long chunkIdOfIndex(int chunkIndex) {
        return jdbc.queryForObject("""
                select id from document_chunk
                 where doc_id = ? and chunk_index = ? and deleted = false limit 1
                """, Long.class, docId, chunkIndex);
    }

    private List<Integer> allIndexes() {
        return jdbc.queryForList("""
                select chunk_index from document_chunk where doc_id = ? and deleted = false
                 order by chunk_index asc, id asc
                """, Integer.class, docId);
    }

    private long vectorCountOf(long chunkId) {
        return jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where chunk_id = ?", Long.class, chunkId);
    }

    private long chunkCount() {
        return jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = false", Long.class, docId);
    }

    private int documentChunkCount() {
        return jdbc.queryForObject("select chunk_count from source_document where id = ?", Integer.class, docId);
    }

    private String contentHashOf(long chunkId) {
        return jdbc.queryForObject("select content_hash from document_chunk where id = ?", String.class, chunkId);
    }

    private String vectorOf(long chunkId) {
        return jdbc.queryForObject(
                "select embedding::text from document_chunk_embedding where chunk_id = ?", String.class, chunkId);
    }

    private OffsetDateTime vectorCreateTimeOf(long chunkId) {
        return jdbc.queryForObject("select create_time from document_chunk_embedding where chunk_id = ?",
                OffsetDateTime.class, chunkId);
    }
}
