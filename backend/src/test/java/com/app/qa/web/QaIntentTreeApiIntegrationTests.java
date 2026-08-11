package com.app.qa.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@TestPropertySource(properties = "app.knowledge.ingestion.enabled=false")
class QaIntentTreeApiIntegrationTests {

    private static final String VIRTUAL_ROOT = "00000000-0000-0000-0000-000000000001";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void resetFixtures() {
        jdbc.execute("truncate qa_intent_node_snapshot, qa_intent_tree_revision cascade");
        jdbc.update("""
                update qa_intent_tree_head
                   set current_revision_id = null, current_revision_no = 0, updated_at = now()
                """);
        jdbc.update("""
                insert into knowledge_base
                    (id, name, storage_alias, description, embedding_model, embedding_dimensions)
                values (81001, '售后政策', 'qa-api-bindable', '可绑定', 'test-embedding', 1024),
                       (81002, '已删除知识库', 'qa-api-deleted', '不可绑定', 'test-embedding', 1024)
                on conflict (id) do update
                   set name = excluded.name, deleted = false, delete_time = null
                """);
        jdbc.update("update knowledge_base set deleted = true, delete_time = now() where id = 81002");
    }

    @Test
    void api07_读取返回虚拟根完整当前revision服务端有效态路径与计数() throws Exception {
        mvc.perform(get("/api/qa/intent-tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision.revisionId").isEmpty())
                .andExpect(jsonPath("$.revision.revisionNo").value(0))
                .andExpect(jsonPath("$.root.nodeId").value(VIRTUAL_ROOT))
                .andExpect(jsonPath("$.root.virtual").value(true))
                .andExpect(jsonPath("$.summary.nodeCount").value(0));

        JsonNode group = createGroup(null, "售后服务");
        String revisionId = group.at("/revision/revisionId").asText();
        String groupId = group.at("/changedNodeId").asText();

        mvc.perform(get("/api/qa/intent-tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision.revisionId").value(revisionId))
                .andExpect(jsonPath("$.nodes.length()").value(1))
                .andExpect(jsonPath("$.nodes[0].nodeId").value(groupId))
                .andExpect(jsonPath("$.nodes[0].parentId").value(VIRTUAL_ROOT))
                .andExpect(jsonPath("$.nodes[0].fullPath").value("售后服务"))
                .andExpect(jsonPath("$.nodes[0].effectiveEnabled").value(false))
                .andExpect(jsonPath("$.nodes[0].childCount").value(0))
                .andExpect(jsonPath("$.summary.nodeCount").value(1));
    }

    @Test
    void api08_命令缺expectedRevision拒绝且过期revision冲突不产生新版本() throws Exception {
        mvc.perform(post("/api/qa/intent-tree/nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentId":"%s","type":"GROUP","name":"缺版本","sortOrder":0}
                                """.formatted(VIRTUAL_ROOT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        JsonNode first = createGroup(null, "第一组");
        String nodeId = first.at("/changedNodeId").asText();
        String currentRevisionId = first.at("/revision/revisionId").asText();
        long revisionCount = revisionCount();
        mvc.perform(put("/api/qa/intent-tree/nodes/{nodeId}", nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"无版本编辑","examples":[],"sortOrder":0}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/qa/intent-tree/nodes/{nodeId}/impact", nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operation":"DISABLE_SUBTREE"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/qa/intent-tree/nodes/{nodeId}/disable", nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"SUBTREE"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/qa/intent-tree/nodes/{nodeId}/enable", nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"NODE_ONLY"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/qa/intent-tree/nodes/{nodeId}/delete", nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scope":"SUBTREE"}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/qa/intent-tree/nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createGroupRequest(null, "冲突组")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INTENT_TREE_REVISION_CONFLICT"))
                .andExpect(jsonPath("$.currentRevisionId")
                        .value(currentRevisionId));

        assertThat(revisionCount()).isEqualTo(revisionCount);
    }

    @Test
    void api09_创建强制停用普通编辑不可换父类型且预览与子树删除一致() throws Exception {
        JsonNode group = createGroup(null, "售后服务");
        String groupId = group.at("/changedNodeId").asText();
        String groupRevision = group.at("/revision/revisionId").asText();
        JsonNode leaf = json.readTree(mvc.perform(post("/api/qa/intent-tree/nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevisionId":"%s",
                                  "parentId":"%s",
                                  "type":"KB",
                                  "name":"退款政策",
                                  "description":"覆盖退款到账与材料问题",
                                  "examples":["退款多久到账？"],
                                  "knowledgeBaseId":81001,
                                  "sortOrder":0
                                }
                                """.formatted(groupRevision, groupId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nodes[?(@.name == '退款政策')].enabled").value(false))
                .andReturn().getResponse().getContentAsString());
        String leafId = leaf.at("/changedNodeId").asText();
        String leafRevision = leaf.at("/revision/revisionId").asText();

        mvc.perform(put("/api/qa/intent-tree/nodes/{nodeId}", leafId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "expectedRevisionId":"%s",
                                  "name":"退款规则",
                                  "description":"覆盖退款规则问题",
                                  "examples":["退款规则是什么？"],
                                  "knowledgeBaseId":81001,
                                  "sortOrder":1,
                                  "parentId":"%s"
                                }
                                """.formatted(leafRevision, VIRTUAL_ROOT)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        mvc.perform(post("/api/qa/intent-tree/nodes/{nodeId}/impact", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevisionId":"%s","operation":"DELETE_SUBTREE"}
                                """.formatted(leafRevision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodeCount").value(2))
                .andExpect(jsonPath("$.boundKnowledgeBaseCount").value(1));

        mvc.perform(post("/api/qa/intent-tree/nodes/{nodeId}/delete", groupId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"expectedRevisionId":"%s","scope":"SUBTREE"}
                                """.formatted(leafRevision)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.treeSummary.nodeCount").value(0));
        mvc.perform(get("/api/qa/intent-tree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes.length()").value(0));
    }

    @Test
    void api10_可绑定知识库过滤已删除项且不暴露内部存储字段() throws Exception {
        String body = mvc.perform(get("/api/qa/intent-tree/bindable-knowledge-bases")
                        .param("query", "售后")
                        .param("limit", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].knowledgeBaseId").value(81001))
                .andExpect(jsonPath("$.items[0].name").value("售后政策"))
                .andExpect(jsonPath("$.items[0].enabled").value(true))
                .andExpect(jsonPath("$.items[0].chunkCount").isNumber())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("storageAlias", "embeddingModel", "collection", "description");
    }

    private JsonNode createGroup(String expectedRevisionId, String name) throws Exception {
        return json.readTree(mvc.perform(post("/api/qa/intent-tree/nodes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createGroupRequest(expectedRevisionId, name)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private static String createGroupRequest(String expectedRevisionId, String name) {
        String revision = expectedRevisionId == null ? "null" : "\"" + expectedRevisionId + "\"";
        return """
                {
                  "expectedRevisionId":%s,
                  "parentId":"%s",
                  "type":"GROUP",
                  "name":"%s",
                  "examples":[],
                  "sortOrder":0
                }
                """.formatted(revision, VIRTUAL_ROOT, name);
    }

    private long revisionCount() {
        return jdbc.queryForObject("select count(*) from qa_intent_tree_revision", Long.class);
    }
}
