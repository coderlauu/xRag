package com.app.qa.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;

/**
 * 工具白名单（架构 §21.2）。空清单是合法状态——**没有配置工具**与**配置了但不可用**是两回事，
 * 前者不向模型发送 {@code tools} 字段，也不产生任何"工具不可用"提示。
 */
@ConfigurationProperties("app.qa.tools")
public class QaToolProperties implements InitializingBean {

    private final List<Tool> whitelist = new ArrayList<>();

    @Override
    public void afterPropertiesSet() {
        Set<String> seen = new HashSet<>();
        for (Tool tool : whitelist) {
            tool.validate();
            if (!seen.add(tool.getName())) {
                // 重复 toolId 视为配置错误而不是"后注册的胜出"：那种静默覆盖让调用哪个后端
                // 取决于配置文件里的排列顺序，改一次顺序就能悄悄换掉行为。同 Embedding 维度冲突。
                throw new IllegalArgumentException(
                        "app.qa.tools.whitelist contains duplicate name '" + tool.getName()
                                + "'; duplicate tool names are a configuration error, not an override");
            }
        }
    }

    public List<Tool> getWhitelist() {
        return whitelist;
    }

    public static final class Tool {

        private static final DefaultResourceLoader RESOURCE_LOADER = new DefaultResourceLoader();
        private static final ObjectMapper MAPPER = new ObjectMapper();

        private String name;
        private String displayName;
        private String description;
        private boolean sideEffect;
        private Duration timeout = Duration.ofSeconds(10);
        private String inputSchema;

        /** 启动时读出并缓存的 Schema 正文。工具定义启动后不变，没必要每次调用重拼。 */
        private String inputSchemaJson;

        /** 启动时从 Schema 解析出的参数展示元信息，同样只解析一次。 */
        private List<ParameterMeta> parameterMetas = List.of();

        private void validate() {
            requireText(name, "name");
            requireText(displayName, "display-name");
            // description 同时是给模型的说明与给用户看的那句话（ui-spec §13.7.2）。缺了它，
            // 确认卡就只能显示模型自己的说法——那等于让模型描述自己要做什么。
            requireText(description, "description");
            requireText(inputSchema, "input-schema");
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException(
                        "app.qa.tools.whitelist[" + name + "].timeout must be positive");
            }
            inputSchemaJson = readSchema();
            parameterMetas = readParameterMetas();
        }

        /**
         * 从 Schema 里解析出参数的展示元信息（契约 §9 的 {@code parameters} 块）。
         *
         * <p>启动时解析一次，与 {@link #inputSchemaJson} 同理——工具定义启动后不变。
         *
         * <p>展示名取 Schema 的 {@code title}，缺省回落到属性名：ui-spec §13.7.2 要的是
         * "Schema 声明的名称"，而 {@code title} 就是那个给人看的声明。<b>Schema 原文不进 API</b>
         * （契约 §12），这里只取名字与必填标记两项，够渲染参数表、不够重建 Schema。
         */
        private List<ParameterMeta> readParameterMetas() {
            JsonNode schema;
            try {
                schema = MAPPER.readTree(inputSchemaJson);
            } catch (IOException exception) {
                throw new IllegalStateException("schema already validated as JSON", exception);
            }
            var required = new LinkedHashSet<String>();
            JsonNode requiredNode = schema.path("required");
            if (requiredNode.isArray()) {
                requiredNode.forEach(node -> required.add(node.asText()));
            }
            var metas = new ArrayList<ParameterMeta>();
            JsonNode properties = schema.path("properties");
            properties.fieldNames().forEachRemaining(key -> {
                String title = properties.path(key).path("title").asText(null);
                metas.add(new ParameterMeta(
                        key, title == null || title.isBlank() ? key : title, required.contains(key)));
            });
            return List.copyOf(metas);
        }

        /**
         * 一个参数的展示元信息。
         *
         * @param key         Schema 里的属性名，用来从复核通过的参数 JSON 里取值
         * @param displayName 给用户看的名字（Schema 的 {@code title}）
         */
        public record ParameterMeta(String key, String displayName, boolean required) {}

        private String readSchema() {
            Resource resource = RESOURCE_LOADER.getResource(inputSchema);
            if (!resource.exists()) {
                throw new IllegalArgumentException(
                        "app.qa.tools.whitelist[" + name + "].input-schema not found: " + inputSchema);
            }
            String json;
            try (InputStream in = resource.getInputStream()) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "app.qa.tools.whitelist[" + name + "].input-schema is unreadable: " + inputSchema,
                        exception);
            }
            try {
                MAPPER.readTree(json);
            } catch (IOException exception) {
                throw new IllegalArgumentException(
                        "app.qa.tools.whitelist[" + name + "].input-schema is not valid JSON: " + inputSchema,
                        exception);
            }
            return json;
        }

        private void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(
                        "app.qa.tools.whitelist[" + (name == null ? "?" : name) + "]." + field
                                + " must not be blank");
            }
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public boolean isSideEffect() {
            return sideEffect;
        }

        public void setSideEffect(boolean sideEffect) {
            this.sideEffect = sideEffect;
        }

        public Duration getTimeout() {
            return timeout;
        }

        public void setTimeout(Duration timeout) {
            this.timeout = timeout;
        }

        public String getInputSchema() {
            return inputSchema;
        }

        public void setInputSchema(String inputSchema) {
            this.inputSchema = inputSchema;
        }

        public String getInputSchemaJson() {
            return inputSchemaJson;
        }

        public List<ParameterMeta> getParameterMetas() {
            return parameterMetas;
        }
    }
}
