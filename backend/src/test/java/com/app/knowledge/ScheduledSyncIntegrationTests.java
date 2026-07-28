package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.knowledge.ingestion.ScheduledSyncScanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * 定时同步的两级变更检测（test-matrix SYNC-01 ~ SYNC-04）。
 *
 * <p>**工单 17 把"内容没变则跳过 / 内容变了则重新分块"列成一对手工验证项**（改一次源文件、
 * 观察两次行为必须不同）。这里把它自动化了：本地源站的响应内容是一个可变引用，测试直接改它
 * 就等于"修改了源文件"，比手工改文件再等一分钟调度可靠得多，也快得多。
 *
 * <p>扫描器直接调 {@code scan()} 而不是等 60s 的 `@Scheduled`——要验的是判断逻辑，
 * 等调度只会让测试慢一分钟，还引入一个与逻辑无关的时间依赖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeEmbeddingConfig.class)
class ScheduledSyncIntegrationTests {

    private static HttpServer origin;
    private static String baseUrl;
    private static final AtomicReference<String> BODY = new AtomicReference<>();
    private static final AtomicReference<String> ETAG = new AtomicReference<>();
    private static final AtomicInteger GET_COUNT = new AtomicInteger();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ScheduledSyncScanner scanner;

    @Autowired
    private S3Client s3;

    @Value("${app.storage.bucket}")
    private String bucket;

    private long kbId;
    private long docId;

    @BeforeAll
    static void startOrigin() throws IOException {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + origin.getAddress().getPort();
        origin.createContext("/doc.md", exchange -> {
            byte[] body = BODY.get().getBytes(StandardCharsets.UTF_8);
            if (ETAG.get() != null) {
                exchange.getResponseHeaders().add("ETag", ETAG.get());
            }
            exchange.getResponseHeaders().add("Content-Type", "text/markdown");
            boolean isHead = "HEAD".equals(exchange.getRequestMethod());
            if (!isHead) {
                GET_COUNT.incrementAndGet();
            }
            exchange.sendResponseHeaders(200, isHead ? -1 : body.length);
            if (!isHead) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        origin.start();
    }

    @AfterAll
    static void stopOrigin() {
        origin.stop(0);
    }

    @BeforeEach
    void setUp() throws Exception {
        FakeEmbeddingConfig.SHOULD_FAIL.set(false);
        BODY.set("第一版内容。这一段是初始文本。\n\n第二段初始文本。");
        ETAG.set(null);
        GET_COUNT.set(0);

        String kb = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "定时同步测试-知识库"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        kbId = json.readTree(kb).get("id").asLong();

        String doc = mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceUri": "%s/doc.md", "syncEnabled": true, "syncCron": "0 0 3 * * ?",
                                 "chunkSize": 20, "overlap": 0}
                                """.formatted(baseUrl)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        docId = json.readTree(doc).get("id").asLong();

        // 先处理一遍，让文档有分块与 revision，之后同步才有"要不要重建"的问题
        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isAccepted());
        awaitStatus("SUCCESS");
        // 把 next_sync_time 拨到过去，让扫描器立刻认为它到期
        makeDue();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
        jdbc.update("delete from ingestion_run where kb_id = ?", kbId);
        jdbc.update("delete from document_chunk where kb_id = ?", kbId);
        jdbc.update("delete from source_document where kb_id = ?", kbId);
        jdbc.update("delete from knowledge_base where id = ?", kbId);
    }

    private void makeDue() {
        jdbc.update("update source_document set next_sync_time = now() - interval '1 minute' where id = ?",
                docId);
    }

    private void awaitStatus(String expected) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select status from source_document where id = ?",
                        String.class, docId)).isEqualTo(expected));
    }

    private String latestRunStatus() {
        return jdbc.queryForObject(
                "select status from ingestion_run where doc_id = ? order by id desc limit 1",
                String.class, docId);
    }

    private int revision() {
        return jdbc.queryForObject("select revision from source_document where id = ?", Integer.class, docId);
    }

    /**
     * SYNC-01 前半：**内容没变 → 记一条 `SKIPPED`，分块一个都不重建**。
     *
     * <p>`SKIPPED` 必须落库：没有它，用户看到"同步开着但从来没有执行记录"，无法区分
     * "检查过没变化"和"调度根本没跑"——而"检查过没变化"恰恰是正常工作时最常见的结果。
     */
    @Test
    void 内容未变化时记录SKIPPED且不重建分块() {
        int revisionBefore = revision();
        long chunkIdsBefore = firstChunkId();

        scanner.scan();

        assertThat(latestRunStatus()).isEqualTo("SKIPPED");
        assertThat(revision()).as("没变就不该重新分块").isEqualTo(revisionBefore);
        assertThat(firstChunkId()).as("分块行本身也没被删旧插新").isEqualTo(chunkIdsBefore);
        assertThat(jdbc.queryForObject("select status from source_document where id = ?",
                String.class, docId)).isEqualTo("SUCCESS");
    }

    /** SYNC-01 后半：**源文件真的变了 → 重新分块、`revision` 递增**。与上一条成对，行为必须不同。 */
    @Test
    void 内容变化后触发重新分块且revision递增() {
        int revisionBefore = revision();
        BODY.set("第二版内容，已经被改过了。\n\n新增的第二段。\n\n再加一段第三段。");

        scanner.scan();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(revision()).isEqualTo(revisionBefore + 1));
        awaitStatus("SUCCESS");
        assertThat(jdbc.queryForObject("""
                select trigger_source from ingestion_run where doc_id = ? order by id desc limit 1
                """, String.class, docId)).isEqualTo("SCHEDULED");

        Long chunks = jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = false", Long.class, docId);
        Long vectors = jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);
        assertThat(chunks).isPositive().isEqualTo(vectors);
    }

    @Test
    void 新版本入库失败时当前原文和分块仍保持上一成功版本() throws Exception {
        int revisionBefore = revision();
        String activeFileKey = jdbc.queryForObject(
                "select file_key from source_document where id = ?", String.class, docId);
        String activeContentBefore = objectContent(activeFileKey);
        BODY.set("第二版内容，但这次向量化会失败，不能替换当前成功版本。");
        FakeEmbeddingConfig.SHOULD_FAIL.set(true);

        scanner.scan();

        awaitStatus("FAILED");
        assertThat(revision()).isEqualTo(revisionBefore);
        assertThat(jdbc.queryForObject(
                "select file_key from source_document where id = ?", String.class, docId))
                .isEqualTo(activeFileKey);
        assertThat(objectContent(activeFileKey)).isEqualTo(activeContentBefore);
        FakeEmbeddingConfig.SHOULD_FAIL.set(false);
    }

    /**
     * 第一级检测的价值所在：ETag 没变时**连下载都不发生**。
     *
     * <p>判据是源站的 GET 计数没有增加。只断言 `SKIPPED` 是不够的——第二级（下载后比哈希）
     * 同样会得出 `SKIPPED`，两者的区别恰恰在于**有没有付那次完整下载的代价**。
     */
    @Test
    void ETag未变时第一级就跳过不发生下载() {
        ETAG.set("\"v1\"");
        scanner.scan();          // 这一轮走第二级，顺手把 ETag 存进库
        makeDue();
        int getsAfterFirst = GET_COUNT.get();

        scanner.scan();          // 这一轮应该在第一级就命中

        assertThat(latestRunStatus()).isEqualTo("SKIPPED");
        assertThat(GET_COUNT.get()).as("第一级命中就不该再下载一次").isEqualTo(getsAfterFirst);
    }

    /** 每次检查（含跳过）都要推进 `next_sync_time`，否则这篇文档会在每一轮扫描里被反复命中。 */
    @Test
    void 跳过后也推进下次执行时间() {
        scanner.scan();

        assertThat(jdbc.queryForObject("""
                select next_sync_time > now() from source_document where id = ?
                """, Boolean.class, docId)).isTrue();
        assertThat(jdbc.queryForObject("select last_sync_time from source_document where id = ?",
                java.time.OffsetDateTime.class, docId)).isNotNull();

        // 已经不到期了，再扫一轮不该产生第二条记录
        long runsBefore = jdbc.queryForObject(
                "select count(*) from ingestion_run where doc_id = ?", Long.class, docId);
        scanner.scan();
        assertThat(jdbc.queryForObject("select count(*) from ingestion_run where doc_id = ?",
                Long.class, docId)).isEqualTo(runsBefore);
    }

    /**
     * 定时同步与手动触发的互斥**完全由工单 10 的同一个 CAS 保证，没有额外的锁**（ADR 0002）。
     * 文档处于 `RUNNING` 时扫描器根本不会把它选出来。
     */
    @Test
    void 处理中的文档不会被定时同步选中() {
        jdbc.update("update source_document set status = 'RUNNING' where id = ?", docId);
        long runsBefore = jdbc.queryForObject(
                "select count(*) from ingestion_run where doc_id = ?", Long.class, docId);

        scanner.scan();

        assertThat(jdbc.queryForObject("select count(*) from ingestion_run where doc_id = ?",
                Long.class, docId)).isEqualTo(runsBefore);
        jdbc.update("update source_document set status = 'SUCCESS' where id = ?", docId);
    }

    /** 关掉同步开关后就不该再被扫描到。 */
    @Test
    void 关闭同步后不再被扫描() {
        jdbc.update("update source_document set sync_enabled = false where id = ?", docId);
        long runsBefore = jdbc.queryForObject(
                "select count(*) from ingestion_run where doc_id = ?", Long.class, docId);

        scanner.scan();

        assertThat(jdbc.queryForObject("select count(*) from ingestion_run where doc_id = ?",
                Long.class, docId)).isEqualTo(runsBefore);
    }

    private long firstChunkId() {
        return jdbc.queryForObject("""
                select id from document_chunk where doc_id = ? and deleted = false
                 order by chunk_index asc, id asc limit 1
                """, Long.class, docId);
    }

    private String objectContent(String key) throws IOException {
        try (InputStream input = s3.getObject(
                GetObjectRequest.builder().bucket(bucket).key(key).build())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
