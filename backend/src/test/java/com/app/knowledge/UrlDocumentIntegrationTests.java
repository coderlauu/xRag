package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * URL 来源文档（test-matrix DOC-06 ~ DOC-11）。
 *
 * <p>源站用 JDK 自带的 {@link HttpServer} 在本地起一个，**不打真实外网地址**：外网会让这组
 * 用例依赖网络连通性和对方站点的可用性，而这里要验的是抓取逻辑、大小限制、失败不落库，
 * 与对端是谁无关。本地源站还能精确构造 404、超大响应、带 ETag 这些情形。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeEmbeddingConfig.class)
class UrlDocumentIntegrationTests {

    private static HttpServer origin;
    private static String baseUrl;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private long kbId;

    @BeforeAll
    static void startOrigin() throws IOException {
        origin = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + origin.getAddress().getPort();

        origin.createContext("/handbook.md", exchange -> {
            byte[] body = ("# 员工手册\n\n第一章 总则。本章说明适用范围。\n\n"
                    + "第二章 考勤。上班时间为 9:30。\n\n第三章 报销。凭发票报销。")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/markdown");
            exchange.getResponseHeaders().add("ETag", "\"abc123\"");
            exchange.getResponseHeaders().add("Last-Modified", "Wed, 15 Jul 2026 08:00:00 GMT");
            respond(exchange, 200, body);
        });
        origin.createContext("/missing.md", exchange -> respond(exchange, 404, new byte[0]));
        origin.createContext("/empty.md", exchange -> respond(exchange, 200, new byte[0]));
        origin.createContext("/page.html", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/html");
            respond(exchange, 200, "<html><body>网页正文</body></html>".getBytes(StandardCharsets.UTF_8));
        });
        origin.start();
    }

    @AfterAll
    static void stopOrigin() {
        origin.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, byte[] body)
            throws IOException {
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    @BeforeEach
    void setUp() throws Exception {
        FakeEmbeddingConfig.SHOULD_FAIL.set(false);
        String kb = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "URL来源测试-知识库"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        kbId = json.readTree(kb).get("id").asLong();
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
        jdbc.update("delete from ingestion_run where kb_id = ?", kbId);
        jdbc.update("delete from document_chunk where kb_id = ?", kbId);
        jdbc.update("delete from source_document where kb_id = ?", kbId);
        jdbc.update("delete from knowledge_base where id = ?", kbId);
    }

    private String addUrl(String body) throws Exception {
        return mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void 抓取成功后记录来源类型文件键与内容哈希() throws Exception {
        String body = mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceUri": "%s/handbook.md"}
                                """.formatted(baseUrl)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("URL"))
                .andExpect(jsonPath("$.name").value("handbook.md"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        long docId = json.readTree(body).get("id").asLong();

        assertThat(jdbc.queryForObject("select file_key from source_document where id = ?",
                String.class, docId)).as("抓到的文件同样存进对象存储，重新分块不必再下一次").isNotBlank();
        assertThat(jdbc.queryForObject("select content_hash from source_document where id = ?",
                String.class, docId)).hasSize(64);
        assertThat(jdbc.queryForObject("select file_size from source_document where id = ?",
                Long.class, docId)).isPositive();
    }

    /** 工单 17 的两级变更检测要靠这两个头，创建时就得记下来。 */
    @Test
    void 记录ETag与LastModified() throws Exception {
        long docId = json.readTree(addUrl("""
                {"sourceUri": "%s/handbook.md"}
                """.formatted(baseUrl))).get("id").asLong();

        assertThat(jdbc.queryForObject("select http_etag from source_document where id = ?",
                String.class, docId)).isEqualTo("\"abc123\"");
        assertThat(jdbc.queryForObject("select http_last_modified from source_document where id = ?",
                String.class, docId)).isEqualTo("Wed, 15 Jul 2026 08:00:00 GMT");
    }

    /**
     * **抓取失败必须不落库**。留下一条永远处理不了的记录，用户只能困惑地反复点"重试"，
     * 而每次都会因为同样的原因失败。
     */
    @Test
    void 远程不可达时返回400且数据库无新记录() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceUri": "%s/missing.md"}
                                """.formatted(baseUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));

        assertThat(jdbc.queryForObject(
                "select count(*) from source_document where kb_id = ?", Long.class, kbId)).isZero();
    }

    /**
     * 网络类异常的 `getMessage()` 经常是 null（`ConnectException` 尤其如此），直接拼进提示里
     * 用户会看到"无法访问该地址：null"——既不是完整句子，也没告诉他下一步做什么。
     * 这条是浏览器实测撞出来的。
     */
    @Test
    void 连不上的地址提示里不出现null() throws Exception {
        // 端口 9（discard）在本机上通常直接拒绝连接
        String body = mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceUri": "http://127.0.0.1:9/a.md"}
                                """))
                .andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();

        String message = json.readTree(body).get("message").asText();
        assertThat(message).doesNotContain("null").startsWith("无法访问该地址：");
    }

    @Test
    void 空内容返回400且不落库() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceUri": "%s/empty.md"}
                                """.formatted(baseUrl)))
                .andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject(
                "select count(*) from source_document where kb_id = ?", Long.class, kbId)).isZero();
    }

    /** 只校验协议，不猜内容类型——Content-Type 是会撒谎的（api.md §3）。 */
    @Test
    void 非http协议返回400() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceUri": "file:///etc/passwd"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("来源地址必须以 http:// 或 https:// 开头。"));
    }

    /** 格式白名单与本地上传保持一致。HTML 不在白名单里——这个功能的用法是文件直链。 */
    @Test
    void 不支持的格式返回415() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceUri": "%s/page.html"}
                                """.formatted(baseUrl)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_FILE_TYPE"));
        assertThat(jdbc.queryForObject(
                "select count(*) from source_document where kb_id = ?", Long.class, kbId)).isZero();
    }

    /**
     * cron 最短间隔限制。**这是本模块唯一一处直接与钱挂钩的输入校验**：每次同步在内容变化时
     * 会重新分块并为每个分块调一次 Embedding，`* * * * * ?` 能在无人察觉时把费用打穿。
     */
    @Test
    void 过密的同步规则返回400() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases/{kbId}/documents/url", kbId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceUri": "%s/handbook.md", "syncEnabled": true, "syncCron": "* * * * * ?"}
                                """.formatted(baseUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("两次同步的间隔不能小于 10 分钟，请调整同步规则。"));
        assertThat(jdbc.queryForObject(
                "select count(*) from source_document where kb_id = ?", Long.class, kbId)).isZero();
    }

    @Test
    void 合法的同步规则被接受并算出下次执行时间() throws Exception {
        long docId = json.readTree(addUrl("""
                {"sourceUri": "%s/handbook.md", "syncEnabled": true, "syncCron": "0 0 3 * * ?"}
                """.formatted(baseUrl))).get("id").asLong();

        assertThat(jdbc.queryForObject("select sync_enabled from source_document where id = ?",
                Boolean.class, docId)).isTrue();
        assertThat(jdbc.queryForObject("select next_sync_time from source_document where id = ?",
                java.time.OffsetDateTime.class, docId)).isNotNull();
    }

    /**
     * 工单 16 清单里的手工验证项，这里自动化了：**URL 来源与 FILE 来源共用同一条执行链路**。
     * 抓取时就把文件存进了对象存储，所以分块阶段读的是同一个 `file_key`，不需要任何分支。
     */
    @Test
    void URL来源文档能正常触发分块并成功() throws Exception {
        long docId = json.readTree(addUrl("""
                {"sourceUri": "%s/handbook.md", "chunkSize": 20, "overlap": 0}
                """.formatted(baseUrl))).get("id").asLong();

        mvc.perform(post("/api/v1/documents/{docId}/ingestion-runs", docId))
                .andExpect(status().isAccepted());
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("select status from source_document where id = ?",
                        String.class, docId)).isEqualTo("SUCCESS"));

        Long chunks = jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = false", Long.class, docId);
        Long vectors = jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);
        assertThat(chunks).isPositive().isEqualTo(vectors);
    }
}
