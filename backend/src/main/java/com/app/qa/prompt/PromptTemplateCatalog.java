package com.app.qa.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;

public final class PromptTemplateCatalog {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}") ;

    private final Map<QaPrompt, String> templates;
    private final int maxRenderedBytes;

    private PromptTemplateCatalog(Map<QaPrompt, String> source, int maxRenderedBytes) {
        if (maxRenderedBytes < 1) {
            throw new IllegalArgumentException("app.qa.understanding.max-intent-prompt-bytes must be positive");
        }
        var validated = new EnumMap<QaPrompt, String>(QaPrompt.class);
        for (QaPrompt prompt : QaPrompt.values()) {
            String template = source.get(prompt);
            validate(prompt, template);
            validated.put(prompt, template);
        }
        this.templates = Collections.unmodifiableMap(validated);
        this.maxRenderedBytes = maxRenderedBytes;
    }

    public static PromptTemplateCatalog fromClasspath(int maxRenderedBytes) {
        var templates = new EnumMap<QaPrompt, String>(QaPrompt.class);
        for (QaPrompt prompt : QaPrompt.values()) {
            var resource = new ClassPathResource(prompt.classpathPath());
            if (!resource.exists()) {
                throw new IllegalStateException(prompt.classpathLocation() + " does not exist");
            }
            try {
                templates.put(prompt, resource.getContentAsString(StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new IllegalStateException("cannot read " + prompt.classpathLocation(), exception);
            }
        }
        return new PromptTemplateCatalog(templates, maxRenderedBytes);
    }

    public static PromptTemplateCatalog fromTemplates(Map<QaPrompt, String> templates, int maxRenderedBytes) {
        return new PromptTemplateCatalog(templates, maxRenderedBytes);
    }

    public String render(QaPrompt prompt, Map<String, String> values) {
        String rendered = templates.get(prompt);
        Set<String> expected = prompt.requiredPlaceholders();
        if (!values.keySet().equals(expected)) {
            throw new IllegalArgumentException(
                    prompt.name() + " values must contain exactly " + expected + ", got " + values.keySet());
        }
        for (String placeholder : expected) {
            String value = values.get(placeholder);
            if (value == null) {
                throw new IllegalArgumentException(prompt.name() + " value " + placeholder + " must not be null");
            }
            rendered = rendered.replace("{{" + placeholder + "}}", value);
        }
        int bytes = rendered.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maxRenderedBytes) {
            throw new PromptBudgetExceededException(bytes, maxRenderedBytes);
        }
        return rendered;
    }

    private static void validate(QaPrompt prompt, String template) {
        if (template == null) {
            throw new IllegalStateException(prompt.classpathLocation() + " does not exist");
        }
        if (template.isBlank()) {
            throw new IllegalStateException(prompt.classpathLocation() + " must not be blank");
        }

        var counts = new HashMap<String, Integer>();
        var matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            counts.merge(matcher.group(1), 1, Integer::sum);
        }
        for (String placeholder : counts.keySet()) {
            if (!prompt.requiredPlaceholders().contains(placeholder)) {
                throw new IllegalStateException(
                        prompt.classpathLocation() + " contains unknown placeholder " + placeholder);
            }
        }
        for (String required : prompt.requiredPlaceholders()) {
            if (counts.getOrDefault(required, 0) != 1) {
                throw new IllegalStateException(
                        prompt.classpathLocation() + " required placeholder " + required
                                + " must appear exactly once");
            }
        }
    }
}
