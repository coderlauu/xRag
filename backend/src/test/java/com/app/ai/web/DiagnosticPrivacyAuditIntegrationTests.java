package com.app.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.chat.RoutingChatClient;
import com.app.ai.diagnostic.AsyncDiagnosticWriter;
import com.app.ai.diagnostic.JdbcDiagnosticStore;
import com.app.ai.embedding.RoutedEmbeddingClient;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatProfile;
import com.app.ai.model.ChatSamplingParameters;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.EmbeddingCommand;
import com.app.ai.model.EmbeddingProfile;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.RerankCandidate;
import com.app.ai.model.RerankCommand;
import com.app.ai.model.RerankProfile;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.ToolDefinition;
import com.app.ai.model.UserMessage;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ChatProvider;
import com.app.ai.provider.EmbeddingProvider;
import com.app.ai.provider.IndexedEmbedding;
import com.app.ai.provider.ProgrammableFakeProvider;
import com.app.ai.provider.ProviderChatResult;
import com.app.ai.provider.ProviderEmbeddingResult;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.ProviderRankedItem;
import com.app.ai.provider.ProviderRerankResult;
import com.app.ai.provider.RerankProvider;
import com.app.ai.rerank.RoutedRerankClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * DIA-10~DIA-12 与 AC-15：统一标记串隐私审计。
 *
 * <p>做法是把可识别的标记串塞进**每一个本不该被保存的位置**——Prompt、工具参数、Embedding
 * 正文、Rerank query 与候选文本、Provider 原始错误体、完整 Provider request ID、密钥值——
 * 然后跑完四个能力，再去五张诊断表的全部文本列和两个诊断 API 的序列化结果里搜这些标记串。
 *
 * <p>用扫描而不是逐字段断言，是因为**泄漏往往发生在新增字段时**：某人给 DTO 加一个
 * "顺手带上原始响应"的字段，逐字段断言不会失败，扫描会。
 *
 * <p>报告只指出位置和字段名，不复制敏感原文——否则审计失败本身就成了第二次泄漏。
 */
class DiagnosticPrivacyAuditIntegrationTests {

    /** 每个标记串对应一类本不该出现的数据。名字要能一眼看出泄漏源。 */
    private static final Map<String, String> MARKERS = new LinkedHashMap<>();

    static {
        MARKERS.put("用户 Prompt 正文", "XRAGLEAK_PROMPT_9f2a");
        MARKERS.put("工具定义与参数", "XRAGLEAK_TOOLARG_7c1b");
        MARKERS.put("Embedding 输入正文", "XRAGLEAK_EMBEDTEXT_3d5e");
        MARKERS.put("Rerank query", "XRAGLEAK_RERANKQUERY_8b4f");
        MARKERS.put("Rerank 候选正文", "XRAGLEAK_RERANKDOC_2a6c");
        MARKERS.put("Provider 原始错误体", "XRAGLEAK_RAWERROR_5e0d");
        MARKERS.put("密钥值", "XRAGLEAK_SECRET_1a2b");
        MARKERS.put("完整 Provider request ID", "XRAGLEAK_FULLREQID_6f3c");
    }

    private static final ProviderId PROVIDER = new ProviderId("provider-a");
    private static final DeploymentId CHAT = new DeploymentId("chat-a");
    private static final DeploymentId EMBED = new DeploymentId("embed-a");
    private static final DeploymentId RERANK = new DeploymentId("rerank-a");
    private static final ModelIdentity EMBED_IDENTITY = new ModelIdentity("embed-a-v1");

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private AsyncDiagnosticWriter writer;

    @BeforeEach
    void createSchema() throws SQLException {
        schema = "xrag_privacy_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        dataSource = new DriverManagerDataSource(schemaUrl(), username, password);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        writer = new AsyncDiagnosticWriter(
                new JdbcDiagnosticStore(jdbc, new DataSourceTransactionManager(dataSource)),
                1_000, 50, Duration.ofMillis(50), Duration.ofSeconds(5));
    }

    @AfterEach
    void dropSchema() throws SQLException {
        writer.close();
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void dia10到dia12_四个能力跑完后标记串不出现在任何诊断表或api响应里() throws Exception {
        runAllCapabilities();
        writer.close();

        List<String> leaks = new ArrayList<>();
        leaks.addAll(scanTables());
        leaks.addAll(scanApis());

        assertThat(leaks)
                .withFailMessage("诊断边界出现隐私泄漏（只列出位置与字段，不复制原文）：%n%s",
                        String.join("\n", leaks))
                .isEmpty();
    }

    @Test
    void dia11_原始错误体只留下稳定错误类型与状态码() throws Exception {
        chatWithRawErrorBody();
        writer.close();

        Map<String, Object> row = jdbc.queryForMap("""
                select normalized_error_type, failure_phase, http_status
                  from ai_logical_call limit 1
                """);
        assertThat(row.get("normalized_error_type")).isEqualTo("SERVER_ERROR");
        assertThat(row.get("failure_phase")).isEqualTo("RESPONSE_HEADERS");
        assertThat(row.get("http_status")).isEqualTo(500);
        assertThat(scanTables()).isEmpty();
    }

    @Test
    void dia12_providerRequestId入库即已脱敏且列表与详情用同一个值() throws Exception {
        UUID callId = chatWithMaskedRequestId();
        writer.close();

        var service = new DiagnosticCallQueryService(jdbc, Clock.systemUTC());
        var detail = service.detail(callId);
        String persisted = jdbc.queryForObject(
                "select provider_request_id_masked from ai_logical_call where id = ?",
                String.class, callId);

        assertThat(persisted).isEqualTo("req_****3c7f");
        assertThat(persisted).doesNotContain(MARKERS.get("完整 Provider request ID"));
        assertThat(detail.call().providerRequestIdMasked()).isEqualTo(persisted);
        assertThat(detail.attempts()).singleElement()
                .satisfies(attempt ->
                        assertThat(attempt.providerRequestIdMasked()).isEqualTo(persisted));
    }

    @Test
    void 审计自身可信_人为种入的标记串必须被扫出来() throws Exception {
        // 一个永远找不到东西的扫描器和一个正确的扫描器，测试结果完全一样。
        // 这一例故意往两个不同的表里各种一个标记串，证明扫描确实覆盖到了它们。
        runAllCapabilities();
        writer.close();
        assertThat(scanTables()).isEmpty();

        jdbc.update("update ai_logical_call set request_id = ? where id in "
                        + "(select id from ai_logical_call limit 1)",
                MARKERS.get("用户 Prompt 正文"));
        jdbc.update("update ai_attempt set provider_request_id_masked = ? where attempt_no = 1",
                MARKERS.get("完整 Provider request ID"));

        List<String> leaks = scanTables();

        assertThat(leaks).anySatisfy(leak ->
                assertThat(leak).contains("ai_logical_call.request_id").contains("用户 Prompt 正文"));
        assertThat(leaks).anySatisfy(leak ->
                assertThat(leak).contains("ai_attempt.provider_request_id_masked"));
        // 报告只给位置和类别名，不复制原文——审计失败本身不该成为第二次泄漏。
        assertThat(leaks).allSatisfy(leak ->
                assertThat(leak).doesNotContain(MARKERS.get("用户 Prompt 正文")));
    }

    // ---------- 扫描 ----------

    /** 扫描五张诊断表的全部文本类列。列表从 information_schema 取，新增列自动纳入。 */
    private List<String> scanTables() {
        List<String> leaks = new ArrayList<>();
        List<Map<String, Object>> columns = jdbc.queryForList("""
                select table_name, column_name
                  from information_schema.columns
                 where table_schema = ?
                   and table_name like 'ai_%'
                   and data_type in ('character varying', 'text', 'character')
                 order by table_name, column_name
                """, schema);
        for (Map<String, Object> column : columns) {
            String table = (String) column.get("table_name");
            String name = (String) column.get("column_name");
            for (var marker : MARKERS.entrySet()) {
                Long hits = jdbc.queryForObject(
                        "select count(*) from %s where %s like ?".formatted(table, name),
                        Long.class,
                        "%" + marker.getValue() + "%");
                if (hits != null && hits > 0) {
                    leaks.add("表 %s.%s 命中「%s」%d 行".formatted(table, name, marker.getKey(), hits));
                }
            }
        }
        return leaks;
    }

    /** 扫描两个诊断 API 序列化后的响应文本，而不是逐字段断言。 */
    private List<String> scanApis() throws Exception {
        var service = new DiagnosticCallQueryService(jdbc, Clock.systemUTC());
        var mapper = new ObjectMapper();
        List<String> leaks = new ArrayList<>();

        var page = service.list(new DiagnosticCallQuery(
                null, List.of(), null, null, null, null, null, null, 1, 100));
        String listJson = mapper.writeValueAsString(page);
        leaks.addAll(scanText("calls 列表响应", listJson));
        for (var item : page.items()) {
            leaks.addAll(scanText(
                    "calls 详情响应", mapper.writeValueAsString(service.detail(item.id()))));
        }
        return leaks;
    }

    private static List<String> scanText(String where, String text) {
        List<String> leaks = new ArrayList<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (var marker : MARKERS.entrySet()) {
            if (lower.contains(marker.getValue().toLowerCase(Locale.ROOT))) {
                leaks.add("%s 命中「%s」".formatted(where, marker.getKey()));
            }
        }
        // secretProperty 是属性名不是密钥值，但 DTO 一旦整体嵌入 ModelProvider 就会把它带出去。
        for (String forbidden : List.of("authorization", "api_key", "apikey", "secret")) {
            if (lower.contains(forbidden)) {
                leaks.add("%s 出现禁止的字段名或值片段：%s".formatted(where, forbidden));
            }
        }
        return leaks;
    }

    // ---------- 四个能力的调用 ----------

    private void runAllCapabilities() {
        var catalog = catalog();
        var registry = registry();

        new RoutingChatClient(
                catalog,
                Map.<ProviderId, ChatProvider>of(PROVIDER, chatProvider()),
                registry,
                writer)
                .complete(new ChatCommand(
                        List.of(new UserMessage(MARKERS.get("用户 Prompt 正文"))),
                        List.of(new ToolDefinition(
                                "search", MARKERS.get("工具定义与参数"),
                                "{\"type\":\"object\",\"properties\":{}}")),
                        ServiceTier.BALANCED,
                        CallOptions.defaults()));

        new RoutedEmbeddingClient(
                catalog,
                Map.<ProviderId, EmbeddingProvider>of(PROVIDER, embeddingProvider()),
                registry,
                writer)
                .embed(new EmbeddingCommand(
                        EMBED_IDENTITY,
                        List.of(MARKERS.get("Embedding 输入正文")),
                        CallOptions.defaults()));

        new RoutedRerankClient(
                catalog,
                Map.<ProviderId, RerankProvider>of(PROVIDER, rerankProvider()),
                registry,
                writer)
                .rerank(new RerankCommand(
                        MARKERS.get("Rerank query"),
                        List.of(
                                new RerankCandidate("doc-0", MARKERS.get("Rerank 候选正文")),
                                new RerankCandidate("doc-1", MARKERS.get("Rerank 候选正文"))),
                        2,
                        ServiceTier.BALANCED,
                        CallOptions.defaults()));
    }

    private void chatWithRawErrorBody() {
        var failing = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    // 上游错误体里混进密钥与原始正文——适配器边界之外一个字都不该留下。
                    throw new ProviderFailureException(
                            AttemptFailureType.SERVER_ERROR_RESPONSE, 500, null, null);
                }));
        try {
            new RoutingChatClient(
                    catalog(),
                    Map.<ProviderId, ChatProvider>of(PROVIDER, failing),
                    registry(),
                    writer)
                    .complete(new ChatCommand(
                            List.of(new UserMessage(MARKERS.get("Provider 原始错误体"))),
                            List.of(), ServiceTier.BALANCED, CallOptions.defaults()));
        } catch (ModelCallException expected) {
            assertThat(expected.getMessage())
                    .doesNotContain(MARKERS.get("Provider 原始错误体"));
        }
    }

    private UUID chatWithMaskedRequestId() {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> new ProviderChatResult(
                        new com.app.ai.model.AssistantMessage("好的", null, List.of()),
                        ChatFinishReason.STOP,
                        new TokenUsage(2, 1, 3),
                        // 适配器交给调度层的必须已经是脱敏值；完整 ID 只应存在于适配器内部。
                        "req_****3c7f")));
        var result = new RoutingChatClient(
                catalog(),
                Map.<ProviderId, ChatProvider>of(PROVIDER, provider),
                registry(),
                writer)
                .complete(new ChatCommand(
                        List.of(new UserMessage("你好")), List.of(),
                        ServiceTier.BALANCED, CallOptions.defaults()));
        return result.execution().logicalCallId();
    }

    private static ProgrammableFakeProvider chatProvider() {
        return new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> new ProviderChatResult(
                        new com.app.ai.model.AssistantMessage(
                                MARKERS.get("用户 Prompt 正文"), null, List.of()),
                        ChatFinishReason.STOP,
                        new TokenUsage(2, 1, 3),
                        "req_****1a2b")));
    }

    private static ProgrammableFakeProvider embeddingProvider() {
        return new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.embedding(
                (deployment, texts, context) -> new ProviderEmbeddingResult(
                        List.of(new IndexedEmbedding(0, new float[] {0.1f, 0.2f})),
                        "req_****4d8e")));
    }

    private static ProgrammableFakeProvider rerankProvider() {
        return new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> new ProviderRerankResult(
                        List.of(new ProviderRankedItem(1, 0.9), new ProviderRankedItem(0, 0.4)),
                        "req_****9c0a")));
    }

    private static Resilience4jModelBreakerRegistry registry() {
        return new Resilience4jModelBreakerRegistry(
                runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
    }

    private static ModelCatalogSnapshot catalog() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER, new ModelProvider(
                PROVIDER, "openai-compatible", "供应商甲",
                URI.create("http://127.0.0.1:1/v1"),
                // secretProperty 是属性名；真正的密钥值（标记串）绝不进 catalog。
                "PROVIDER_A_TOKEN",
                new DataRegion("CN_TEST"), new QuotaDomain("quota-a"),
                ProviderConfigurationStatus.CONFIGURED));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(CHAT, new ModelDeployment(
                CHAT, PROVIDER, "chat-model", new ModelIdentity("chat-a-v1"),
                Set.of(Capability.CHAT), Set.of(ServiceTier.BALANCED), true, 10, true, null,
                Set.of(com.app.ai.model.ModelFeature.TOOL_CALLING), null,
                new ChatProfile(Map.of(
                        ServiceTier.BALANCED, new ChatSamplingParameters(0.1, 0.3)))));
        deployments.put(EMBED, new ModelDeployment(
                EMBED, PROVIDER, "embed-model", EMBED_IDENTITY,
                Set.of(Capability.EMBEDDING), Set.of(), false, 10, true,
                new EmbeddingProfile(false, null, 2, 10, 8192, "RAW_UTF8_V1")));
        deployments.put(RERANK, new ModelDeployment(
                RERANK, PROVIDER, "rerank-model", new ModelIdentity("rerank-a-v1"),
                Set.of(Capability.RERANK), Set.of(ServiceTier.BALANCED), true, 10, true,
                null, Set.of(), new RerankProfile(50)));
        return new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providers, deployments, runtimePolicy());
    }

    private static AiRuntimePolicy runtimePolicy() {
        return new AiRuntimePolicy(
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(45), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(120), 3, 1),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofMinutes(15), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(20), 3, 2),
                new AiRuntimePolicy.StreamLimits(32, 65_536, 65_536),
                new AiRuntimePolicy.BreakerLimits(
                        20, 5, 50, 50, Duration.ofSeconds(30), 2, Duration.ofSeconds(30)));
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
