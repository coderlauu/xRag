package com.app.knowledge.embedding;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(EmbeddingProperties.class)
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

    @Bean
    public EmbeddingClient embeddingClient(
            RestClient.Builder restClientBuilder, EmbeddingProperties properties) {
        if (!properties.isConfigured()) {
            LOGGER.warn("app.embedding.api-key 未配置，向量化能力不可用（应用其余部分正常）。"
                    + "需要时设置环境变量 EMBEDDING_API_KEY 后重启。");
            return new UnconfiguredEmbeddingClient(properties.getDimensions());
        }
        LOGGER.info("Embedding: {} @ {}，{} 维，每批 {} 条", properties.getModel(),
                properties.getBaseUrl(), properties.getDimensions(), properties.getBatchSize());
        return new OpenAiCompatibleEmbeddingClient(restClientBuilder, properties);
    }

    /**
     * 校验配置维度与向量列维度一致。
     *
     * <p>两种失败区别对待，因为性质不同：**维度不一致是配置错误**，它是确定性的、每次
     * 启动都错、且症状要等到第一次写向量时才以一条 SQL 报错的形式出现——所以直接让应用
     * 起不来。而**数据库不可达是环境状态**（本地容器还没起、迁移还没跑），沿用既有模式
     * 只记警告，由 readiness 反映。
     */
    @Bean
    public ApplicationRunner validateEmbeddingDimensions(
            JdbcTemplate jdbcTemplate, EmbeddingProperties properties) {
        return (ApplicationArguments args) -> {
            String columnType;
            try {
                List<String> found = jdbcTemplate.queryForList(VECTOR_COLUMN_TYPE_QUERY, String.class);
                if (found.isEmpty()) {
                    LOGGER.warn("未找到 document_chunk_embedding.embedding 列，跳过 Embedding 维度校验"
                            + "（迁移可能尚未执行）。");
                    return;
                }
                columnType = found.get(0);
            } catch (DataAccessException exception) {
                LOGGER.warn("数据库不可达，跳过 Embedding 维度校验：{}", exception.toString());
                return;
            }

            Matcher matcher = VECTOR_DIMENSIONS.matcher(columnType);
            if (!matcher.matches()) {
                throw new IllegalStateException(
                        "document_chunk_embedding.embedding 的类型是 %s，无法解析出向量维度".formatted(columnType));
            }

            int columnDimensions = Integer.parseInt(matcher.group(1));
            if (columnDimensions != properties.getDimensions()) {
                throw new IllegalStateException(
                        ("Embedding 维度不一致：app.embedding.dimensions=%d，但 "
                                + "document_chunk_embedding.embedding 是 %s。"
                                + "改配置（EMBEDDING_DIMENSIONS）使其与建表维度一致，"
                                + "或新增一个 migration 修改列维度——两者必须相等，"
                                + "否则每次写向量都会在运行时报错。")
                                .formatted(properties.getDimensions(), columnType));
            }
            LOGGER.info("Embedding 维度校验通过：{} 维", columnDimensions);
        };
    }
}
