package com.app.knowledge.embedding;

import com.app.ai.model.Capability;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 启动校验：向量列维度、catalog 身份维度与已有知识库绑定三者必须互相一致。
 *
 * <p>Embedding 客户端本身已由 {@code com.app.ai.config.EmbeddingClientConfiguration} 提供；
 * 本类只保留校验，维度事实源从 {@code app.embedding.dimensions} 换成 catalog 的
 * {@code EmbeddingProfile}——身份和维度不能再被默认模型端口旁路。
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingConfig.class);

    /** {@code vector(1024)} → 1024 */
    private static final Pattern VECTOR_DIMENSIONS = Pattern.compile("^vector\\((\\d+)\\)$");

    private static final String VECTOR_COLUMN_TYPE_QUERY = """
            select format_type(a.atttypid, a.atttypmod)
            from pg_attribute a
            join pg_class c on c.oid = a.attrelid
            join pg_namespace n on n.oid = c.relnamespace and n.nspname = current_schema()
            where c.relname = 'document_chunk_embedding'
              and a.attname = 'embedding'
              and a.attnum > 0
              and not a.attisdropped
            """;

    private static final String KNOWLEDGE_BASE_BINDING_QUERY = """
            select id, name, embedding_model, embedding_dimensions
            from knowledge_base
            where deleted = false
            """;

    /**
     * 维度不一致是**配置错误**：确定性、每次启动都错、症状要等到第一次写向量时才以一条
     * SQL 报错的形式出现，所以直接让应用起不来。数据库不可达是**环境状态**（本地容器还没
     * 起、迁移还没跑），沿用既有模式只记警告，由 readiness 反映。
     */
    @Bean
    public ApplicationRunner validateEmbeddingDimensions(
            JdbcTemplate jdbcTemplate, ModelCatalogSnapshot catalog) {
        return (ApplicationArguments args) -> {
            Map<ModelIdentity, Integer> identityDimensions = embeddingIdentities(catalog);
            if (identityDimensions.isEmpty()) {
                LOGGER.warn("catalog 未声明任何 EMBEDDING deployment，跳过向量维度校验。");
                return;
            }

            Integer columnDimensions = columnDimensions(jdbcTemplate);
            if (columnDimensions == null) {
                return;
            }
            identityDimensions.forEach((identity, dimensions) -> {
                if (dimensions != columnDimensions.intValue()) {
                    throw new IllegalStateException(
                            ("Embedding 维度不一致：catalog 身份 %s 是 %d 维，但 "
                                    + "document_chunk_embedding.embedding 是 vector(%d)。"
                                    + "改 ai-models.yml 使其与建表维度一致，"
                                    + "或新增 migration 修改列维度——两者必须相等。")
                                    .formatted(identity.value(), dimensions, columnDimensions));
                }
            });
            validateKnowledgeBaseBindings(jdbcTemplate, identityDimensions);
            LOGGER.info("Embedding 维度校验通过：{} 维，身份 {}",
                    columnDimensions, identityDimensions.keySet());
        };
    }

    /**
     * EMB-20：已有知识库的身份不能被静默改绑到另一个制品。知识库表记录的是**建库时**的
     * 身份与维度，catalog 改动若与之冲突，现存向量就已经不再属于同一个向量空间——那必须
     * 走一次显式的全量重建，不能只改映射。
     */
    private static void validateKnowledgeBaseBindings(
            JdbcTemplate jdbcTemplate, Map<ModelIdentity, Integer> identityDimensions) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(KNOWLEDGE_BASE_BINDING_QUERY);
        } catch (DataAccessException exception) {
            LOGGER.warn("无法读取 knowledge_base，跳过身份绑定校验：{}", exception.toString());
            return;
        }
        for (Map<String, Object> row : rows) {
            String model = (String) row.get("embedding_model");
            int dimensions = ((Number) row.get("embedding_dimensions")).intValue();
            Integer catalogDimensions = identityDimensions.get(new ModelIdentity(model));
            if (catalogDimensions == null) {
                throw new IllegalStateException(
                        ("知识库 %s（id=%s）绑定的 Embedding 身份 %s 在 catalog 中不存在。"
                                + "改绑身份必须走显式的全量向量重建，不能只改 ai-models.yml。")
                                .formatted(row.get("name"), row.get("id"), model));
            }
            if (catalogDimensions != dimensions) {
                throw new IllegalStateException(
                        ("知识库 %s（id=%s）建库时身份 %s 是 %d 维，catalog 现在声明 %d 维。"
                                + "维度变化意味着向量空间已不同，必须走显式的全量向量重建。")
                                .formatted(row.get("name"), row.get("id"), model,
                                        dimensions, catalogDimensions));
            }
        }
    }

    private static Map<ModelIdentity, Integer> embeddingIdentities(ModelCatalogSnapshot catalog) {
        var identities = new LinkedHashMap<ModelIdentity, Integer>();
        for (ModelDeployment deployment : catalog.deployments().values()) {
            if (deployment.capabilities().contains(Capability.EMBEDDING)
                    && deployment.embedding() != null) {
                identities.put(deployment.modelIdentity(), deployment.embedding().dimensions());
            }
        }
        return identities;
    }

    private static Integer columnDimensions(JdbcTemplate jdbcTemplate) {
        String columnType;
        try {
            List<String> found = jdbcTemplate.queryForList(VECTOR_COLUMN_TYPE_QUERY, String.class);
            if (found.isEmpty()) {
                LOGGER.warn("未找到 document_chunk_embedding.embedding 列，跳过 Embedding 维度校验"
                        + "（迁移可能尚未执行）。");
                return null;
            }
            columnType = found.get(0);
        } catch (DataAccessException exception) {
            LOGGER.warn("数据库不可达，跳过 Embedding 维度校验：{}", exception.toString());
            return null;
        }
        Matcher matcher = VECTOR_DIMENSIONS.matcher(columnType);
        if (!matcher.matches()) {
            throw new IllegalStateException(
                    "document_chunk_embedding.embedding 的类型是 %s，无法解析出向量维度"
                            .formatted(columnType));
        }
        return Integer.parseInt(matcher.group(1));
    }
}
