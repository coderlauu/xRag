package com.app.qa.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PromptTemplateCatalogTests {

    @Test
    void classpath目录包含八个可渲染模板() {
        var catalog = PromptTemplateCatalog.fromClasspath(65_536);

        assertThat(QaPrompt.values()).hasSize(8);
        assertThat(catalog.render(QaPrompt.INTENT_SCORE_USER, Map.of(
                        "question", "如何配置知识库？",
                        "intentCatalog", "[]")))
                .contains("如何配置知识库？", "[]");
    }

    @Test
    void 缺文件空文件未知占位符和缺必需占位符都会被拒绝() {
        var valid = validTemplates();

        var missing = new EnumMap<>(valid);
        missing.remove(QaPrompt.REWRITE_USER);
        assertThatThrownBy(() -> PromptTemplateCatalog.fromTemplates(missing, 65_536))
                .hasMessageContaining("classpath:prompts/qa/3a/rewrite-user.md");

        var blank = new EnumMap<>(valid);
        blank.put(QaPrompt.REWRITE_USER, "  \n");
        assertThatThrownBy(() -> PromptTemplateCatalog.fromTemplates(blank, 65_536))
                .hasMessageContaining("must not be blank");

        var unknown = new EnumMap<>(valid);
        unknown.put(QaPrompt.REWRITE_USER, "{{summary}} {{history}} {{question}} {{unknown}}");
        assertThatThrownBy(() -> PromptTemplateCatalog.fromTemplates(unknown, 65_536))
                .hasMessageContaining("unknown placeholder unknown");

        var required = new EnumMap<>(valid);
        required.put(QaPrompt.REWRITE_USER, "{{summary}} {{history}}");
        assertThatThrownBy(() -> PromptTemplateCatalog.fromTemplates(required, 65_536))
                .hasMessageContaining("required placeholder question must appear exactly once");
    }

    @Test
    void 渲染只替换命名占位符而不解释表达式或include() {
        var catalog = PromptTemplateCatalog.fromTemplates(validTemplates(), 65_536);
        var value = "${7 * 7} #{T(java.lang.Runtime)} {{include:secret}} <script>alert(1)</script>";

        var rendered = catalog.render(QaPrompt.INTENT_SCORE_USER, Map.of(
                "question", value,
                "intentCatalog", "[]"));

        assertThat(rendered).contains(value);
    }

    @Test
    void 意图评分预算按UTF8字节在65536边界拒绝() {
        var templates = validTemplates();
        templates.put(QaPrompt.INTENT_SCORE_USER, "{{question}}{{intentCatalog}}");
        var catalog = PromptTemplateCatalog.fromTemplates(templates, 65_536);
        var exact = "😀".repeat(16_384);

        var rendered = catalog.render(
                QaPrompt.INTENT_SCORE_USER,
                Map.of("question", exact, "intentCatalog", ""));
        assertThat(rendered.getBytes(StandardCharsets.UTF_8)).hasSize(65_536);
        assertThatThrownBy(() -> catalog.render(
                        QaPrompt.INTENT_SCORE_USER,
                        Map.of("question", exact + "x", "intentCatalog", "")))
                .isInstanceOf(PromptBudgetExceededException.class)
                .hasMessageContaining("65537")
                .hasMessageContaining("65536");
    }

    private static EnumMap<QaPrompt, String> validTemplates() {
        var templates = new EnumMap<QaPrompt, String>(QaPrompt.class);
        for (QaPrompt prompt : QaPrompt.values()) {
            var text = new StringBuilder(prompt.name());
            for (String placeholder : prompt.requiredPlaceholders()) {
                text.append(" {{").append(placeholder).append("}}");
            }
            templates.put(prompt, text.toString());
        }
        return templates;
    }
}
