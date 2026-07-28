package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 文件上传的集成测试。需要真实 Postgres **和真实对象存储**（RustFS）——上传路径的
 * 核心就是"落盘 + 上传 + 落库"三段，把存储换成假的等于什么都没验。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeEmbeddingConfig.class)
@Transactional
class DocumentUploadIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private S3Client s3;

    @Value("${app.storage.bucket}")
    private String bucket;

    private long kbId;

    @BeforeEach
    void createKnowledgeBase() throws Exception {
        String body = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "测试-上传用知识库"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        kbId = json.readTree(body).get("id").asLong();
    }

    private MockMultipartFile textFile(String filename, String content) {
        return new MockMultipartFile("file", filename, "text/plain",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 集成测试使用独立的对象和数据库空间() {
        assertThat(bucket).isEqualTo("app-test");
        assertThat(jdbc.queryForObject("select current_schema()", String.class))
                .isEqualTo("xrag_test");
    }

    @Test
    void 上传成功后文档是待处理状态() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(textFile("手册.txt", "内容")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("手册.txt"))
                .andExpect(jsonPath("$.sourceType").value("FILE"))
                // 上传不触发分块——界面必须展示"待处理"，否则用户会以为已经能检索了
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.revision").value(0))
                .andExpect(jsonPath("$.chunkCount").value(0))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.fileKey").isNotEmpty());
    }

    @Test
    void 原始文件按知识库文档和版本分层存放() throws Exception {
        String body = mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(textFile("员工手册.txt", "内容")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String key = json.readTree(body).get("fileKey").asText();
        assertThat(key)
                .startsWith("knowledge-bases/%d-测试-上传用知识库/documents/".formatted(kbId))
                .contains("/versions/")
                .endsWith("/员工手册.txt");
    }

    @Test
    void 分块配置缺省时用api约定的默认值() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(textFile("默认参数.md", "# 标题")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunkStrategy").value("RECURSIVE"))
                .andExpect(jsonPath("$.chunkConfig.chunkSize").value(1000))
                .andExpect(jsonPath("$.chunkConfig.overlap").value(100));
    }

    @Test
    void 分块配置可以每文档指定() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(textFile("自定义参数.txt", "内容"))
                        .param("chunkStrategy", "FIXED_SIZE")
                        .param("chunkSize", "800")
                        .param("overlap", "80"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunkStrategy").value("FIXED_SIZE"))
                .andExpect(jsonPath("$.chunkConfig.chunkSize").value(800))
                .andExpect(jsonPath("$.chunkConfig.overlap").value(80));
    }

    /** 白名单在上传入口就拦，不等到分块阶段——那时文件已传完、任务已建好。 */
    @Test
    void 不支持的扩展名返回415() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(new MockMultipartFile("file", "演示.pptx",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_FILE_TYPE"))
                .andExpect(jsonPath("$.message").value("不支持这种文件格式。目前支持 .txt、.md、.pdf、.docx。"));
    }

    @Test
    void overlap不小于chunkSize返回400() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(textFile("参数非法.txt", "内容"))
                        .param("chunkSize", "100")
                        .param("overlap", "100"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void 上传到不存在的知识库返回404() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", 999_999_999L)
                        .file(textFile("孤儿.txt", "内容")))
                .andExpect(status().isNotFound());
    }

    @Test
    void 列表能按状态与启用状态过滤() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(textFile("列表用.txt", "内容")))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents", kbId).param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1));

        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents", kbId).param("status", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));

        mvc.perform(get("/api/v1/knowledge-bases/{kbId}/documents", kbId).param("enabled", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(0));
    }

    /**
     * 临时文件必须在成功和失败两条路径上都被清掉。漏删的症状是磁盘被慢慢吃满，
     * 而且要等到很久以后才发现——那时已经攒了成千上万个文件。
     */
    @Test
    void 临时文件在成功与失败路径下都被清理() throws Exception {
        List<Path> before = leftoverTempFiles();

        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", kbId)
                        .file(textFile("成功.txt", "内容")))
                .andExpect(status().isCreated());
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", 999_999_999L)
                        .file(textFile("失败.txt", "内容")))
                .andExpect(status().isNotFound());

        assertThat(leftoverTempFiles()).isEqualTo(before);
    }

    private List<Path> leftoverTempFiles() throws IOException {
        try (Stream<Path> files = Files.list(Paths.get(System.getProperty("java.io.tmpdir")))) {
            return files.filter(p -> p.getFileName().toString().startsWith("xrag-upload-")).sorted().toList();
        }
    }
}
