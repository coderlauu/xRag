package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.knowledge.embedding.EmbeddingProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库 CRUD 的集成测试。**需要真实 Postgres**（H2 撑不住部分唯一索引、check 约束、
 * `vector(1024)` 和 HNSW——见 test-matrix.md §2），Flyway 保持默认开启。
 *
 * <p>类级 {@code @Transactional} 让每个用例结束后回滚，用例之间互不污染。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeEmbeddingConfig.class)
@Transactional
@Sql(statements = "delete from knowledge_base where name like '测试-%'")
class KnowledgeBaseIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 断言对照配置值而不是写死模型名：这两条用例要证明的是"值来自服务端配置"，
     * 写死字面量会让每次换供应商都无谓地弄红一次测试。
     */
    @Autowired
    private EmbeddingProperties embeddingProperties;

    private long createKnowledgeBase(String name) throws Exception {
        String body = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "%s", "description": "集成测试"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("id").asLong();
    }

    @Test
    void 创建后能查到并带上服务端写入的模型配置() throws Exception {
        long id = createKnowledgeBase("测试-创建");

        mvc.perform(get("/api/v1/knowledge-bases/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("测试-创建"))
                .andExpect(jsonPath("$.embeddingModel").value(embeddingProperties.getModel()))
                .andExpect(jsonPath("$.embeddingDimensions").value(embeddingProperties.getDimensions()))
                .andExpect(jsonPath("$.documentCount").value(0))
                .andExpect(jsonPath("$.chunkCount").value(0));
    }

    /** 客户端传 embeddingModel 必须被忽略，值只能来自全局配置（api.md §2）。 */
    @Test
    void 创建时忽略客户端传入的模型配置() throws Exception {
        String body = mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "测试-忽略模型", "embeddingModel": "伪造的模型", "embeddingDimensions": 7}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        JsonNode created = json.readTree(body);
        assertThat(created.get("embeddingModel").asText()).isEqualTo(embeddingProperties.getModel());
        assertThat(created.get("embeddingDimensions").asInt()).isEqualTo(embeddingProperties.getDimensions());
    }

    @Test
    void 同名知识库返回400且提示可直接展示() throws Exception {
        createKnowledgeBase("测试-重名");

        mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "测试-重名"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("已存在同名知识库「测试-重名」，请换一个名称。"));
    }

    @Test
    void 空名称返回400() throws Exception {
        mvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "   "}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
    }

    @Test
    void 改名不与自己冲突() throws Exception {
        long id = createKnowledgeBase("测试-改名");

        mvc.perform(put("/api/v1/knowledge-bases/{id}", id)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name": "测试-改名", "description": "只改描述"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.description").value("只改描述"));
    }

    /**
     * 逻辑删除的核心断言：删掉之后**详情和列表两条路径都查不到**。
     * 这一组专门针对"repository 查询漏写 deleted = false"这个高风险点。
     */
    @Test
    void 逻辑删除后详情与列表都查不到() throws Exception {
        long id = createKnowledgeBase("测试-删除");

        mvc.perform(delete("/api/v1/knowledge-bases/{id}", id)).andExpect(status().isNoContent());

        mvc.perform(get("/api/v1/knowledge-bases/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));

        String listBody = mvc.perform(get("/api/v1/knowledge-bases").param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(listBody).doesNotContain("测试-删除");
    }

    @Test
    void 存在文档时拒绝删除知识库() throws Exception {
        long id = createKnowledgeBase("测试-非空删除保护");
        jdbc.update("""
                insert into source_document (kb_id, name, source_type, storage_object_id)
                values (?, '仍在使用的文档.txt', 'FILE', 'kb-not-empty-doc')
                """, id);

        mvc.perform(delete("/api/v1/knowledge-bases/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("KB_NOT_EMPTY"))
                .andExpect(jsonPath("$.message")
                        .value("知识库下仍有文档，请先删除文档后再删除知识库。"));
    }

    @Test
    void 存在活动入库任务时优先拒绝删除知识库() throws Exception {
        long id = createKnowledgeBase("测试-任务删除保护");
        long docId = jdbc.queryForObject("""
                insert into source_document
                    (kb_id, name, source_type, status, storage_object_id)
                values (?, '正在处理的文档.txt', 'FILE', 'RUNNING', 'kb-active-run-doc')
                returning id
                """, Long.class, id);
        jdbc.update("""
                insert into ingestion_run (kb_id, doc_id, trigger_source, status)
                values (?, ?, 'MANUAL', 'RUNNING')
                """, id, docId);

        mvc.perform(delete("/api/v1/knowledge-bases/{id}", id))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("KB_HAS_ACTIVE_RUNS"))
                .andExpect(jsonPath("$.message")
                        .value("知识库仍有正在排队或处理中的任务，请等待任务结束后再删除。"));
    }

    /** 行还在库里，只是 deleted = true——否则就不是逻辑删除了。 */
    @Test
    void 逻辑删除保留数据行() throws Exception {
        long id = createKnowledgeBase("测试-仍在库中");

        mvc.perform(delete("/api/v1/knowledge-bases/{id}", id)).andExpect(status().isNoContent());

        Boolean deleted = jdbc.queryForObject(
                "select deleted from knowledge_base where id = ?", Boolean.class, id);
        assertThat(deleted).isTrue();
    }

    @Test
    void 重复删除返回404() throws Exception {
        long id = createKnowledgeBase("测试-重复删除");
        mvc.perform(delete("/api/v1/knowledge-bases/{id}", id)).andExpect(status().isNoContent());

        mvc.perform(delete("/api/v1/knowledge-bases/{id}", id))
                .andExpect(status().isNotFound());
    }

    /** 逻辑删除必须释放名字，否则用户删掉后再也建不出同名知识库（部分唯一索引的意义）。 */
    @Test
    void 删除后可以重建同名知识库() throws Exception {
        long first = createKnowledgeBase("测试-同名重建");
        mvc.perform(delete("/api/v1/knowledge-bases/{id}", first)).andExpect(status().isNoContent());

        long second = createKnowledgeBase("测试-同名重建");

        assertThat(second).isNotEqualTo(first);
    }

    @Test
    void 不存在的知识库返回404() throws Exception {
        mvc.perform(get("/api/v1/knowledge-bases/{id}", 999_999_999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("知识库不存在或已被删除。"));
    }

    @Test
    void 分页参数越界被规整而不是报错() throws Exception {
        mvc.perform(get("/api/v1/knowledge-bases").param("page", "0").param("size", "9999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(100));
    }
}
