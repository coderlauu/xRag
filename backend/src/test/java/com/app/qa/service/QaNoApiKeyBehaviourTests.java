package com.app.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ProviderConfigurationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 无任何供应商密钥时 3A 的实际行为——写成测试而不是写进部署文档里的推断。
 *
 * <p>这一类**刻意不注入假 Chat**：它走真实的 {@code RoutingChatClient} 和真实的
 * {@code ai-models.yml}，在没有密钥的环境里观察整条链路。这正是 CI 和"还没配 Key 的新部署"
 * 所处的状态。
 *
 * <p>结论是反直觉的、也正因如此必须被断言住：**没有 Key 时应用照常启动，一轮提问也照常走到
 * 稳定终态**，只是改写退回归一化问题、意图评分拿不到候选，最终形成"没匹配到方向"的动作。
 * 运维如果按"没配 Key 就该报错"的直觉去看监控，会把一个完全没在工作的系统当成正常的。
 */
/*
 * 把两个密钥属性显式置空。
 *
 * <p>不这么做的话，这一类在开发机上会**连上真实供应商并产生计费调用**，还会给出一个完全
 * 相反的"无 Key 行为"结论：`application.properties` 里有
 * `spring.config.import=optional:file:.env[.properties]`，而本机的 `backend/.env`（已 gitignore）
 * 放着真实密钥——`System.getenv` 是空的，Spring 的 Environment 却不是。
 *
 * <p>覆盖 `spring.config.import` 没用：它写在 `application.properties` 文档内部，该文档被加载时
 * 就会处理自己的 import，外部再声明一次只是**追加**。内联测试属性的优先级高于全部 config data
 * 文件，所以直接把属性值覆盖成空串才是有效的那一手。
 */
@SpringBootTest(properties = {"DASHSCOPE_API_KEY=", "EMBEDDING_API_KEY="})
@AutoConfigureMockMvc
@Transactional
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class QaNoApiKeyBehaviourTests {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ModelCatalogSnapshot catalog;

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void requireUnconfiguredCatalogAndPublishTree() {
        // 守在 catalog 上而不是 System.getenv 上：密钥是否被解析出来，只有 catalog 说了算。
        // 这一条一旦失效，整个测试就会在"其实有 Key"的环境里给出假结论——工单 15 学到的同一课。
        Assumptions.assumeTrue(
                catalog.providers().values().stream().allMatch(
                        provider -> provider.configurationStatus() == ProviderConfigurationStatus.UNCONFIGURED),
                "catalog 里仍存在已配置密钥的供应商，跳过而不是给出假结论：" + catalog.providers().values()
                        .stream().map(p -> p.id().value() + "=" + p.configurationStatus()).toList());

        long knowledgeBaseId = jdbc.queryForObject("""
                insert into knowledge_base
                    (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'text-embedding-v3', 1024)
                returning id
                """, Long.class,
                "qa-nokey-" + UUID.randomUUID(),
                "qa-nokey-" + UUID.randomUUID());
        long revisionNo = jdbc.queryForObject(
                "select coalesce(max(revision_no), 0) + 1 from qa_intent_tree_revision", Long.class);
        UUID revisionId = UUID.randomUUID();
        UUID groupId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, change_type, node_count, enabled_leaf_count)
                values (?, ?, 'CREATE', 2, 1)
                """, revisionId, revisionNo);
        jdbc.update("""
                insert into qa_intent_node_snapshot
                    (revision_id, node_id, parent_id, depth, sort_order, node_type,
                     name, description, examples, enabled, deleted)
                values (?, ?, null, 1, 0, 'GROUP', '售后服务', '售后服务分组', '[]', true, false)
                """, revisionId, groupId);
        jdbc.update("""
                insert into qa_intent_node_snapshot
                    (revision_id, node_id, parent_id, depth, sort_order, node_type,
                     name, description, examples, knowledge_base_id, enabled, deleted)
                values (?, ?, ?, 2, 0, 'KB', '退款政策', '退款到账与材料',
                        '["退款多久到账"]', ?, true, false)
                """, revisionId, UUID.randomUUID(), groupId, knowledgeBaseId);
        jdbc.update("""
                update qa_intent_tree_head
                   set current_revision_id = ?, current_revision_no = ?, updated_at = now()
                 where singleton_id = 1
                """, revisionId, revisionNo);
    }

    @Test
    void 无密钥时应用照常启动且一轮提问仍收敛为稳定终态() throws Exception {
        String response = mvc.perform(post("/api/qa/turns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "clientRequestId":"%s",
                                  "conversationId":null,
                                  "message":{"type":"QUESTION","text":"退款多久到账？"}
                                }
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        var json = mapper.readTree(response);
        String status = json.at("/turn/status").asText();
        String rewriteMode = json.at("/inspector/rewrite/mode").asText();
        String actionType = json.at("/inspector/subQuestions/0/actions/0/type").asText();

        assertThat(status)
                .withFailMessage("无 Key 时本轮应收敛为稳定终态而不是失败，实际=%s，完整响应=%s",
                        status, response)
                .isEqualTo("ACTIONS_READY");
        // 改写调用被路由层挡掉（供应商 UNCONFIGURED），退回最小归一化问题并记录稳定降级码。
        assertThat(rewriteMode)
                .withFailMessage("完整响应=%s", response)
                .isEqualTo("NORMALIZED_FALLBACK");
        assertThat(json.at("/turn/degradations").toString()).contains("REWRITE_MODEL_UNAVAILABLE");
        // 评分同样拿不到候选，于是形成"没匹配到方向"一类的动作——不是伪造的定向检索。
        assertThat(actionType).isIn("NO_MATCH", "GLOBAL_FALLBACK");
        assertThat(json.at("/inspector/subQuestions/0/matches").size()).isZero();
    }
}
