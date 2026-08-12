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
@SpringBootTest(properties = {
    "DASHSCOPE_API_KEY=", "EMBEDDING_API_KEY=", "app.knowledge.ingestion.enabled=false"})
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

    @Autowired
    private com.app.knowledge.vector.ChunkVectorRepository vectors;

    @Autowired
    private com.app.qa.retrieval.RetrievalOrchestrator orchestrator;

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

    /**
     * 3B-E2E-04：无密钥时检索仍然可用，精排**降级**为未精排而不是让整轮失败。
     *
     * <p>这条查证的是工单 23 记录里一句存疑的话——"缺 DASHSCOPE_API_KEY 的环境里重排会直接失败
     * 而非降级"。如果属实，那与精排三态的降级设计（工单 07 / 架构 §19.6"精排失败 → 降级为未精排"）
     * 直接冲突，是个真 bug；如果不属实，那句记录该改。写成断言而不是留在文档里推断。
     *
     * <p><b>本方法不参与类级事务</b>：检索跑在 {@code qaRetrievalExecutor} 的其他线程上，
     * 那些线程看不见测试事务里未提交的种子数据（工单 08 踩过这个坑）。所以种子真提交，
     * 用 finally 显式清理。
     *
     * <p>无密钥时向量通道必然失败——查询向量算不出来。**关键词通道不需要任何模型**，
     * 这正是"检索仍然可用"的实际含义：混合检索在没有模型的环境里退化成纯关键词，而不是全灭。
     */
    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void e2e04_无密钥时检索仍可用且精排降级为未精排() throws Exception {
        String alias = "nokey-" + UUID.randomUUID();
        Long kbId = jdbc.queryForObject(
                """
                insert into knowledge_base (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'test', 1024) returning id
                """,
                Long.class,
                alias,
                alias);
        try {
            long docId = jdbc.queryForObject(
                    """
                    insert into source_document (kb_id, name, source_type, storage_object_id)
                    values (?, 'nokey.txt', 'FILE', ?) returning id
                    """,
                    Long.class,
                    kbId,
                    UUID.randomUUID().toString());
            String content = "退货政策说明：退货需要订单号与凭证照片";
            long chunkId = jdbc.queryForObject(
                    """
                    insert into document_chunk
                        (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                    values (?, ?, 1, 0, ?, ?, 1,
                            '0000000000000000000000000000000000000000000000000000000000000000')
                    returning id
                    """,
                    Long.class,
                    kbId,
                    docId,
                    content,
                    content.length());
            // 向量与关键词是同一处写入的两张派生表（架构 §20.1），所以这一行连带建好关键词索引。
            float[] vector = new float[1024];
            vector[0] = 1.0f;
            vectors.insertAll(kbId, docId, java.util.List.of(chunkId), java.util.List.of(vector));

            String response = mvc.perform(post("/api/qa/turns")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {
                                      "clientRequestId":"%s",
                                      "conversationId":null,
                                      "message":{"type":"QUESTION","text":"退货政策"}
                                    }
                                    """.formatted(UUID.randomUUID())))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var json = mapper.readTree(response);

            // 启动正常、整轮收敛：无密钥不该让请求失败。
            assertThat(json.at("/turn/status").asText()).isEqualTo("ACTIONS_READY");

            // **实测结论，与 Acceptance 原措辞不同**：无密钥时检索根本不会执行，因为 3A 的意图
            // 评分先拿不到候选（reasonCode=INTENT_SCORING_UNAVAILABLE），动作退化成 NO_MATCH，
            // 而 NO_MATCH 不是可执行的检索动作。所以"无 Key 时检索可用"在端到端这一层观察不到
            // ——不是检索坏了，是它没被要求做任何事。检索层自身的无 Key 行为由下一条验证。
            assertThat(json.at("/inspector/subQuestions/0/actions/0/type").asText()).isEqualTo("NO_MATCH");
            assertThat(json.at("/retrieval/status").asText()).isEqualTo("NO_RESULT");
            assertThat(json.at("/retrieval/sources").size()).isZero();
            // 这一态下 degradation 为空，形式上符合契约 §9 的不变量（rerankApplied=false 才要求
            // 成因非空）。语义是"没有候选，无从谈降级"，不是"精排成功了"。
            assertThat(json.at("/retrieval/degradation").isNull()).isTrue();
        } finally {
            jdbc.update("delete from document_chunk_keyword where kb_id = ?", kbId);
            jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
            jdbc.update("delete from document_chunk where kb_id = ?", kbId);
            jdbc.update("delete from source_document where kb_id = ?", kbId);
            jdbc.update("delete from knowledge_base where id = ?", kbId);
        }
    }

    /**
     * 3B-E2E-04 的另一半：**检索层自己**在无密钥时的行为。
     *
     * <p>上一条证明了端到端观察不到这件事（3A 先短路）。所以这里绕过 3A，直接 seed 一条
     * {@code GLOBAL_FALLBACK} 动作再调编排——这才是"如果 3A 真的产出了检索动作，无密钥环境
     * 会发生什么"的答案，也是工单 23 那句存疑记录（"缺 Key 时重排直接失败而非降级"）的正面查证。
     *
     * <p>预期：向量通道必然失败（查询向量算不出来），关键词通道不需要任何模型、照常召回，
     * 于是本轮是部分失败而不是全灭；精排拿不到模型则降级为未精排。
     */
    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void e2e04_检索层在无密钥时退化为关键词召回且精排降级() {
        String alias = "nokey-ret-" + UUID.randomUUID();
        Long kbId = jdbc.queryForObject(
                """
                insert into knowledge_base (name, storage_alias, embedding_model, embedding_dimensions)
                values (?, ?, 'test', 1024) returning id
                """,
                Long.class,
                alias,
                alias);
        UUID conversationId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        try {
            long docId = jdbc.queryForObject(
                    """
                    insert into source_document (kb_id, name, source_type, storage_object_id)
                    values (?, 'nokey.txt', 'FILE', ?) returning id
                    """,
                    Long.class,
                    kbId,
                    UUID.randomUUID().toString());
            String content = "退货政策说明：退货需要订单号与凭证照片";
            long chunkId = jdbc.queryForObject(
                    """
                    insert into document_chunk
                        (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                    values (?, ?, 1, 0, ?, ?, 1,
                            '0000000000000000000000000000000000000000000000000000000000000000')
                    returning id
                    """,
                    Long.class,
                    kbId,
                    docId,
                    content,
                    content.length());
            float[] vector = new float[1024];
            vector[0] = 1.0f;
            vectors.insertAll(kbId, docId, java.util.List.of(chunkId), java.util.List.of(vector));

            jdbc.update(
                    "insert into qa_conversation (id, title, last_active_at) values (?, '无密钥检索', now())",
                    conversationId);
            jdbc.update(
                    """
                    insert into qa_turn
                        (id, conversation_id, client_request_id, user_message_id, status, completed_at)
                    values (?, ?, ?, ?, 'ACTIONS_READY', now())
                    """,
                    turnId,
                    conversationId,
                    UUID.randomUUID(),
                    UUID.randomUUID());
            UUID subQuestionId = UUID.randomUUID();
            jdbc.update(
                    "insert into qa_subquestion (id, turn_id, ordinal, text, source) values (?, ?, 0, ?, 'MODEL')",
                    subQuestionId,
                    turnId,
                    "退货政策");
            jdbc.update(
                    """
                    insert into qa_retrieval_action
                        (id, turn_id, subquestion_id, ordinal, action_type,
                         intent_node_id, knowledge_base_id, reason_code)
                    values (?, ?, ?, 1, 'GLOBAL_FALLBACK', null, null, 'TEST')
                    """,
                    UUID.randomUUID(),
                    turnId,
                    subQuestionId);

            var outcome = orchestrator.retrieve(turnId);

            // 关键词通道不需要模型，所以仍然召得回来——"无密钥时检索可用"的实际含义。
            assertThat(outcome.sources())
                    .as("关键词通道不依赖任何模型，无密钥时它仍该工作")
                    .isNotEmpty();
            // 精排降级而不是让本轮失败（架构 §19.6：精排失败 → 降级为未精排）。
            assertThat(outcome.reranked())
                    .as("无密钥时精排应降级为未精排，而不是抛出让整轮失败")
                    .isFalse();
            assertThat(outcome.degradation())
                    .as("rerankApplied=false 时成因必须非空（契约 §9 的不变量）")
                    .isNotNull();
            // **实测暴露的规格缺口，如实断言而不是改成期望值**：本轮 status 是 RETRIEVAL_FAILED，
            // 同时 sources 非空。两者都符合现有规格——目标只由向量通道定义（架构 §19.6），
            // 向量通道全失败即"全部目标失败"；而关键词通道不产生目标、却真的召回了内容。
            //
            // 于是无密钥环境的**默认**状态是"检索失败但有来源"：界面会按 §13.6 显示"没查成、
            // 可重试"，而来源区里躺着关键词召回的结果。这个组合在工单 08 写 fail03 时就被注意到
            // 并刻意绕开了（当时用不存在的词让关键词也零命中），现在它成了无 Key 部署的常态。
            // 规格没有为它定义表达方式，需要产品裁决——见工单 14 的遗留，不在此擅自改实现。
            assertThat(outcome.status())
                    .as("当前实现与规格一致，但这个组合的用户表达尚未裁决")
                    .isEqualTo(com.app.qa.retrieval.RetrievalStatus.RETRIEVAL_FAILED);
            assertThat(outcome.partial())
                    .as("目标全失败时 partial 为 false——它表达的是'部分失败'，不是'全失败'")
                    .isFalse();
        } finally {
            jdbc.update("delete from qa_retrieval_source where turn_id = ?", turnId);
            jdbc.update("delete from qa_retrieval_target where turn_id = ?", turnId);
            jdbc.update("delete from qa_turn_retrieval where turn_id = ?", turnId);
            jdbc.update("delete from qa_retrieval_action where turn_id = ?", turnId);
            jdbc.update("delete from qa_subquestion where turn_id = ?", turnId);
            jdbc.update("delete from qa_turn where id = ?", turnId);
            jdbc.update("delete from qa_conversation where id = ?", conversationId);
            jdbc.update("delete from document_chunk_keyword where kb_id = ?", kbId);
            jdbc.update("delete from document_chunk_embedding where kb_id = ?", kbId);
            jdbc.update("delete from document_chunk where kb_id = ?", kbId);
            jdbc.update("delete from source_document where kb_id = ?", kbId);
            jdbc.update("delete from knowledge_base where id = ?", kbId);
        }
    }
}
