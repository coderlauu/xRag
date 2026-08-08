package com.app.qa.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.qa.intent.IntentTreeStore.CreateNode;
import com.app.qa.intent.IntentTreeStore.EditableNode;
import com.app.qa.intent.IntentTreeStore.EnableScope;
import com.app.qa.intent.IntentTreeStore.TreeOperation;
import com.app.qa.model.IntentNodeType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntentTreeStoreTests {

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactions;
    private IntentTreeStore store;

    @BeforeAll
    void createSchema() throws SQLException {
        schema = "xrag_qa_intent_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        var dataSource = new DriverManagerDataSource(schemaUrl(), username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void resetTree() {
        jdbc.execute("truncate qa_intent_node_snapshot, qa_intent_tree_revision cascade");
        jdbc.update("""
                insert into knowledge_base
                    (id, name, storage_alias, embedding_model, embedding_dimensions)
                values (12, '意图树测试知识库', 'intent-tree-test', 'test-embedding', 1024)
                on conflict (id) do nothing
                """);
        jdbc.update("""
                insert into knowledge_base
                    (id, name, storage_alias, embedding_model, embedding_dimensions, deleted, delete_time)
                values (99, '不可用知识库', 'intent-tree-unavailable', 'test-embedding', 1024, true, now())
                on conflict (id) do nothing
                """);
        jdbc.update("""
                update qa_intent_tree_head
                   set current_revision_id = null, current_revision_no = 0, updated_at = now()
                """);
        store = new IntentTreeStore(jdbc, transactions, id -> id == 12L, new ObjectMapper());
    }

    @AfterAll
    void dropSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void tree01到02_空头首建默认停用且虚拟根不落表() {
        var empty = store.current();
        var created = store.create(null, group(IntentTreeStore.VIRTUAL_ROOT_ID, "售后服务"));

        assertThat(empty.revisionId()).isNull();
        assertThat(empty.revisionNo()).isZero();
        assertThat(created.snapshot().revisionNo()).isEqualTo(1);
        assertThat(created.snapshot().node(created.changedNodeId())).get()
                .satisfies(node -> {
                    assertThat(node.parentId()).isEqualTo(IntentTreeStore.VIRTUAL_ROOT_ID);
                    assertThat(node.enabled()).isFalse();
                });
        assertThat(jdbc.queryForObject("""
                select count(*) from qa_intent_node_snapshot where node_id = ?
                """, Integer.class, IntentTreeStore.VIRTUAL_ROOT_ID)).isZero();
        assertThatThrownBy(() -> store.disable(
                        created.snapshot().revisionId(), IntentTreeStore.VIRTUAL_ROOT_ID))
                .isInstanceOf(IntentTreeValidationException.class);
    }

    @Test
    void tree03到06_结构重名启用完整性和知识库可用性整体校验() {
        var root = store.create(null, group(IntentTreeStore.VIRTUAL_ROOT_ID, "售后服务"));
        UUID groupId = root.changedNodeId();

        assertThatThrownBy(() -> store.create(root.snapshot().revisionId(), group(UUID.randomUUID(), "孤儿")))
                .isInstanceOf(IntentTreeValidationException.class);
        assertThatThrownBy(() -> store.create(
                        root.snapshot().revisionId(), group(IntentTreeStore.VIRTUAL_ROOT_ID, " 售后服务 ")))
                .isInstanceOf(IntentTreeValidationException.class);

        var kb = store.create(root.snapshot().revisionId(), kb(groupId, "退款", 12L));
        UUID kbId = kb.changedNodeId();
        assertThatThrownBy(() -> store.create(kb.snapshot().revisionId(), group(kbId, "叶子之子")))
                .isInstanceOf(IntentTreeValidationException.class);
        assertThatThrownBy(() -> store.enable(kb.snapshot().revisionId(), kbId, EnableScope.NODE_ONLY))
                .isInstanceOf(IntentTreeValidationException.class);

        var groupEnabled = store.enable(kb.snapshot().revisionId(), groupId, EnableScope.NODE_ONLY);
        var enabled = store.enable(groupEnabled.snapshot().revisionId(), kbId, EnableScope.NODE_ONLY);
        assertThat(enabled.snapshot().node(kbId)).get()
                .extracting(IntentTreeStore.IntentNode::enabled)
                .isEqualTo(true);

        var unavailable = store.create(enabled.snapshot().revisionId(), kb(groupId, "换货", 99L));
        assertThatThrownBy(() -> store.enable(
                        unavailable.snapshot().revisionId(), unavailable.changedNodeId(), EnableScope.NODE_ONLY))
                .isInstanceOf(IntentTreeValidationException.class);

        var secondLevel = store.create(
                unavailable.snapshot().revisionId(), group(groupId, "二层组"));
        var thirdLevel = store.create(
                secondLevel.snapshot().revisionId(), group(secondLevel.changedNodeId(), "三层组"));
        assertThatThrownBy(() -> store.create(
                        thirdLevel.snapshot().revisionId(), group(thirdLevel.changedNodeId(), "非法四层")))
                .isInstanceOf(IntentTreeValidationException.class);
        assertThatThrownBy(() -> store.enable(
                        thirdLevel.snapshot().revisionId(), groupId, EnableScope.CONFIGURED_SUBTREE))
                .isInstanceOf(IntentTreeValidationException.class);
        assertThat(store.current().revisionId()).isEqualTo(thirdLevel.snapshot().revisionId());
    }

    @Test
    void tree07到09_子树启停删除原子生效且旧快照不变() {
        var fixture = enabledTwoLevelTree();
        var oldSnapshot = fixture.snapshot();

        var disabled = store.disable(oldSnapshot.revisionId(), fixture.groupId());
        assertThat(disabled.snapshot().activeSubtree(fixture.groupId()))
                .allSatisfy(node -> assertThat(node.enabled()).isFalse());
        assertThat(oldSnapshot.activeSubtree(fixture.groupId()))
                .allSatisfy(node -> assertThat(node.enabled()).isTrue());

        var nodeOnly = store.enable(disabled.snapshot().revisionId(), fixture.groupId(), EnableScope.NODE_ONLY);
        assertThat(nodeOnly.snapshot().node(fixture.groupId())).get()
                .extracting(IntentTreeStore.IntentNode::enabled)
                .isEqualTo(true);
        assertThat(nodeOnly.snapshot().node(fixture.leafId())).get()
                .extracting(IntentTreeStore.IntentNode::enabled)
                .isEqualTo(false);

        var subtree = store.enable(
                nodeOnly.snapshot().revisionId(), fixture.groupId(), EnableScope.CONFIGURED_SUBTREE);
        var deleted = store.delete(subtree.snapshot().revisionId(), fixture.groupId());
        assertThat(deleted.snapshot().activeSubtree(fixture.groupId())).isEmpty();
        assertThat(subtree.snapshot().activeSubtree(fixture.groupId())).hasSize(2);
    }

    @Test
    void tree10_相同期望revision并发命令只有一个提交() throws Exception {
        var root = store.create(null, group(IntentTreeStore.VIRTUAL_ROOT_ID, "根组"));
        UUID expected = root.snapshot().revisionId();
        var callers = Executors.newFixedThreadPool(2);
        try {
            var left = callers.submit(() -> store.create(expected, group(IntentTreeStore.VIRTUAL_ROOT_ID, "甲")));
            var right = callers.submit(() -> store.create(expected, group(IntentTreeStore.VIRTUAL_ROOT_ID, "乙")));
            int successes = 0;
            int conflicts = 0;
            for (var result : List.of(left, right)) {
                try {
                    result.get();
                    successes++;
                } catch (java.util.concurrent.ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(IntentTreeRevisionConflictException.class);
                    conflicts++;
                }
            }
            assertThat(successes).isEqualTo(1);
            assertThat(conflicts).isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from qa_intent_tree_revision", Integer.class))
                    .isEqualTo(2);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void tree11到13_普通编辑不改变结构且字段边界与闭集类型生效() {
        var root = store.create(null, group(IntentTreeStore.VIRTUAL_ROOT_ID, "根组"));
        var leaf = store.create(root.snapshot().revisionId(), kb(root.changedNodeId(), "知识叶", 12L));
        UUID leafId = leaf.changedNodeId();
        var before = leaf.snapshot().node(leafId).orElseThrow();

        var updated = store.update(leaf.snapshot().revisionId(), leafId, new EditableNode(
                "新名称", "新描述", List.of("例子 A", "例子 B"), 12L, null, "提示", 3));
        var after = updated.snapshot().node(leafId).orElseThrow();
        assertThat(after.nodeId()).isEqualTo(before.nodeId());
        assertThat(after.parentId()).isEqualTo(before.parentId());
        assertThat(after.type()).isEqualTo(before.type());
        assertThat(after.enabled()).isEqualTo(before.enabled());

        assertThatThrownBy(() -> store.update(updated.snapshot().revisionId(), leafId, new EditableNode(
                        "非法例子", "描述", List.of("重复", " 重复 "), 12L, null, null, 0)))
                .isInstanceOf(IntentTreeValidationException.class);
        assertThatThrownBy(() -> store.create(
                        updated.snapshot().revisionId(),
                        new CreateNode(root.changedNodeId(), IntentNodeType.GROUP, "伪 MCP", null,
                                List.of(), null, null, "MCP", 0)))
                .isInstanceOf(IntentTreeValidationException.class);
    }

    @Test
    void tree14到16_缓存故障可重载且影响预览与命令一致() {
        var fixture = enabledTwoLevelTree();
        var preview = store.preview(
                fixture.snapshot().revisionId(), fixture.groupId(), TreeOperation.DISABLE_SUBTREE);
        assertThat(preview.nodeCount()).isEqualTo(2);
        assertThat(preview.enabledLeafCount()).isEqualTo(1);
        assertThat(preview.boundKnowledgeBaseCount()).isEqualTo(1);

        var changed = store.disable(fixture.snapshot().revisionId(), fixture.groupId());
        assertThat(changed.snapshot().activeSubtree(fixture.groupId()))
                .allSatisfy(node -> assertThat(node.enabled()).isFalse());
        assertThatThrownBy(() -> store.disable(fixture.snapshot().revisionId(), fixture.groupId()))
                .isInstanceOf(IntentTreeRevisionConflictException.class);

        var failOnce = new AtomicBoolean(true);
        var flakyCache = new IntentTreeStore(
                jdbc,
                transactions,
                id -> id == 12L,
                new ObjectMapper(),
                snapshot -> {
                    if (failOnce.getAndSet(false)) {
                        throw new IllegalStateException("cache publish failed");
                    }
                });
        var committed = flakyCache.create(
                changed.snapshot().revisionId(), group(IntentTreeStore.VIRTUAL_ROOT_ID, "另一个组"));
        assertThat(flakyCache.current().revisionId()).isEqualTo(committed.snapshot().revisionId());
        assertThat(flakyCache.current().nodes()).containsExactlyInAnyOrderElementsOf(committed.snapshot().nodes());
    }

    private TreeFixture enabledTwoLevelTree() {
        var group = store.create(null, group(IntentTreeStore.VIRTUAL_ROOT_ID, "根组"));
        var leaf = store.create(group.snapshot().revisionId(), kb(group.changedNodeId(), "知识叶", 12L));
        var enabledGroup = store.enable(leaf.snapshot().revisionId(), group.changedNodeId(), EnableScope.NODE_ONLY);
        var enabledLeaf = store.enable(
                enabledGroup.snapshot().revisionId(), leaf.changedNodeId(), EnableScope.NODE_ONLY);
        return new TreeFixture(enabledLeaf.snapshot(), group.changedNodeId(), leaf.changedNodeId());
    }

    private static CreateNode group(UUID parentId, String name) {
        return new CreateNode(parentId, IntentNodeType.GROUP, name, null, List.of(), null, null, null, 0);
    }

    private static CreateNode kb(UUID parentId, String name, long knowledgeBaseId) {
        return new CreateNode(
                parentId,
                IntentNodeType.KB,
                name,
                "覆盖" + name + "相关问题",
                List.of(name + "怎么办？"),
                knowledgeBaseId,
                null,
                null,
                0);
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private record TreeFixture(IntentTreeStore.IntentTreeSnapshot snapshot, UUID groupId, UUID leafId) {}
}
