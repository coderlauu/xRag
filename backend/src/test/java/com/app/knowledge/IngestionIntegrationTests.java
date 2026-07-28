package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.knowledge.ingestion.IngestionDispatcher;
import com.app.knowledge.model.IngestionInput;
import com.app.knowledge.service.IngestionService;
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

    @Autowired
    private IngestionDispatcher dispatcher;

    @Autowired
    private IngestionService ingestionService;

    private long kbId;
    private long docId;

    @BeforeEach
    void setUp() throws Exception {
        FakeEmbeddingConfig.SHOULD_FAIL.set(false);
        FakeEmbeddingConfig.SHOULD_THROW_ERROR.set(false);
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

    @Test
    void 定时入库任务创建失败时文档抢占也回滚() throws Exception {
        IngestionInput invalid = new IngestionInput(0, "x".repeat(513),
                null, null, null, null, null);

        assertThatThrownBy(() -> ingestionService.tryTriggerScheduled(kbId, docId, invalid))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

        mvc.perform(get("/api/v1/documents/{docId}", docId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
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

    /**
     * 心跳超时回收（工单 11）。**两张表都要改**：只改 `ingestion_run` 会让文档永久停在
     * `RUNNING`——CAS 抢不到、删除和更新也都被 409 挡住，用户什么都做不了。
     *
     * <p>直接调 {@code recoverStale()} 而不是等 60s 的调度：这里验的是回收逻辑本身，
     * 等调度只会让测试慢一分钟，还引入一个与逻辑无关的时间依赖。
     */
    @Test
    void 心跳超时的任务与其文档都被标记为失败() {
        long runId = jdbc.queryForObject("""
                insert into ingestion_run (kb_id, doc_id, trigger_source, status, phase, heartbeat_time)
                values ((select kb_id from source_document where id = ?), ?, 'MANUAL', 'RUNNING', 'EMBED',
                        now() - interval '6 minutes')
                returning id
                """, Long.class, docId, docId);
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);

        dispatcher.recoverStale();

        assertThat(jdbc.queryForObject("select status from ingestion_run where id = ?", String.class, runId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select status from source_document where id = ?", String.class, docId))
                .as("只改 run 不改文档，文档就永久卡在 RUNNING").isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "select error_message from source_document where id = ?", String.class, docId))
                .contains("卡死");
        // phase 清空：被回收的任务并不知道失败在哪一步，存着的只是心跳停前最后写下的步骤。
        // 留着它，界面会把「最后到过 EMBED」渲染成「向量计算失败：」，指向一个未必出错的环节。
        assertThat(jdbc.queryForObject("select phase from ingestion_run where id = ?", String.class, runId))
                .as("phase 的语义是「失败发生在这一步」，超时回收给不出这个信息，null 才是诚实的")
                .isNull();
        assertThat(jdbc.queryForObject(
                "select error_message from ingestion_run where id = ?", String.class, runId))
                .as("最后到过哪一步没有丢，只是改由消息用准确的措辞说明")
                .contains("最后记录到的步骤是 EMBED");
    }

    /** 回收的意义就在这里：文档能被重新触发，CAS 不再被挡住。 */
    @Test
    void 回收后文档可以被重新触发分块() throws Exception {
        jdbc.update("""
                insert into ingestion_run (kb_id, doc_id, trigger_source, status, heartbeat_time)
                values ((select kb_id from source_document where id = ?), ?, 'MANUAL', 'RUNNING',
                        now() - interval '6 minutes')
                """, docId, docId);
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);

        // 回收前：被 409 挡住
        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isConflict());

        dispatcher.recoverStale();

        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isAccepted());
        awaitStatus("SUCCESS");
    }

    /**
     * 执行过程中抛出的 {@link Error} 必须照样落进失败记录。
     *
     * <p>**这是本组测试里最有价值的一条**，因为它守的缺陷完全不可见：{@code execute} 若只
     * {@code catch (Exception)}，Error 会直接穿过去——任务不是"失败"，而是**永远停在
     * RUNNING**，直到五分钟后被心跳超时兜底改写成一句"卡死"。真实原因（这里是 OOM）
     * 在库里、日志里、界面上全部消失，排查时只剩一条毫无信息量的超时消息。
     *
     * <p>断言"错误消息里能看到真实原因"而不只是"状态变成 FAILED"：兜底回收也会把状态改成
     * FAILED，只断言状态的话，修复前后这条测试都是绿的。
     */
    @Test
    void 执行中抛出Error时真实原因仍然写进失败记录() throws Exception {
        FakeEmbeddingConfig.SHOULD_THROW_ERROR.set(true);
        try {
            trigger();
            awaitStatus("FAILED");
        } finally {
            FakeEmbeddingConfig.SHOULD_THROW_ERROR.set(false);
        }

        assertThat(jdbc.queryForObject(
                "select error_message from source_document where id = ?", String.class, docId))
                .as("Error 被吞掉的话，这里要么是 null，要么是五分钟后那句无用的超时兜底")
                .contains("堆内存耗尽");
        assertThat(jdbc.queryForObject(
                "select status from ingestion_run where doc_id = ? order by id desc limit 1",
                String.class, docId)).isEqualTo("FAILED");
    }

    /**
     * 心跳开始没多久就断掉 = 进程整个没了，消息必须这么说。
     *
     * <p>这正是真实故障的形态：任务开始 0.9 秒后心跳停止，五分钟后被回收，而当时给出的
     * 消息是"处理超过 PT5M 没有任何进展"，把人引向"分块卡住了"——实际那份文档切分只要
     * 3.5 毫秒。消息说错方向比不说更费时间。
     */
    @Test
    void 心跳几乎没跳过就断掉时消息指向进程被停止() {
        long runId = jdbc.queryForObject("""
                insert into ingestion_run (kb_id, doc_id, trigger_source, status, phase,
                                           started_time, heartbeat_time)
                values ((select kb_id from source_document where id = ?), ?, 'MANUAL', 'RUNNING', 'CHUNK',
                        now() - interval '6 minutes', now() - interval '6 minutes' + interval '1 second')
                returning id
                """, Long.class, docId, docId);
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);

        dispatcher.recoverStale();

        String message = jdbc.queryForObject(
                "select error_message from source_document where id = ?", String.class, docId);
        assertThat(message).contains("1 秒后就完全失去响应").contains("被停止或重启");
        assertThat(message)
                .as("phase 只是最后记录到的步骤，不能说成「切分失败」——那一步其实跑完了")
                .contains("最后记录到的步骤是 CHUNK");
        assertThat(jdbc.queryForObject("select status from ingestion_run where id = ?", String.class, runId))
                .isEqualTo("FAILED");
        // 前端用 phase 拼「内容切分失败：」前缀（RunHistory.tsx），留着它就等于在界面上
        // 断言切分出了问题——而这正是本次真实故障里把人带偏的那句话。
        assertThat(jdbc.queryForObject("select phase from ingestion_run where id = ?", String.class, runId))
                .isNull();
    }

    /** 反过来：心跳跳了很久才停，那才是真的卡在某一步，措辞应当不同。 */
    @Test
    void 心跳长时间正常后才停的仍然按卡死措辞() {
        jdbc.update("""
                insert into ingestion_run (kb_id, doc_id, trigger_source, status, phase,
                                           started_time, heartbeat_time)
                values ((select kb_id from source_document where id = ?), ?, 'MANUAL', 'RUNNING', 'EMBED',
                        now() - interval '2 hours', now() - interval '6 minutes')
                """, docId, docId);
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);

        dispatcher.recoverStale();

        assertThat(jdbc.queryForObject(
                "select error_message from source_document where id = ?", String.class, docId))
                .contains("卡死")
                .doesNotContain("被停止或重启");
    }

    /** 心跳还在跳的任务不能被误杀——否则长耗时的正常任务会被回收打断。 */
    @Test
    void 心跳正常的任务不会被回收() {
        long runId = jdbc.queryForObject("""
                insert into ingestion_run (kb_id, doc_id, trigger_source, status, heartbeat_time)
                values ((select kb_id from source_document where id = ?), ?, 'MANUAL', 'RUNNING', now())
                returning id
                """, Long.class, docId, docId);

        dispatcher.recoverStale();

        assertThat(jdbc.queryForObject("select status from ingestion_run where id = ?", String.class, runId))
                .isEqualTo("RUNNING");
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
