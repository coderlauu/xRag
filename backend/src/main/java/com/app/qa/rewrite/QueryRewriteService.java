package com.app.qa.rewrite;

import com.app.ai.model.ChatClient;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.SystemMessage;
import com.app.ai.model.UserMessage;
import com.app.qa.model.QaChatCommandFactory;
import com.app.qa.model.RewriteMode;
import com.app.qa.prompt.PromptTemplateCatalog;
import com.app.qa.prompt.QaPrompt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public final class QueryRewriteService {

    private static final Pattern JSON_FENCE = Pattern.compile(
            "^\\s*```(?:json)?\\s*(.*?)\\s*```\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final ChatClient chatClient;
    private final PromptTemplateCatalog prompts;
    private final ObjectMapper objectMapper;

    public QueryRewriteService(
            ChatClient chatClient,
            PromptTemplateCatalog prompts,
            ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.prompts = prompts;
        this.objectMapper = objectMapper;
    }

    public RewriteResult rewrite(RewriteRequest request) {
        String normalized = QuestionNormalizer.normalize(request.question());
        String systemPrompt = prompts.render(QaPrompt.REWRITE_SYSTEM, Map.of());
        String userPrompt = prompts.render(QaPrompt.REWRITE_USER, Map.of(
                "summary", request.summary(),
                "history", request.recentHistory(),
                "question", normalized));

        try {
            var result = chatClient.complete(QaChatCommandFactory.balanced(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt))));
            UUID logicalCallId = result.execution().logicalCallId();
            return parse(result.message().text(), normalized, logicalCallId);
        } catch (ModelCallException exception) {
            return fallback(
                    normalized,
                    "REWRITE_MODEL_UNAVAILABLE",
                    exception.execution().logicalCallId());
        }
    }

    private RewriteResult parse(String raw, String normalized, UUID logicalCallId) {
        if (raw == null) {
            return fallback(normalized, "REWRITE_INVALID_RESPONSE", logicalCallId);
        }
        String json = stripSingleFence(raw);
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                return fallback(normalized, "REWRITE_INVALID_RESPONSE", logicalCallId);
            }
            JsonNode rewrittenNode = root.get("rewrittenQuestion");
            JsonNode subQuestionsNode = root.get("subQuestions");
            if (rewrittenNode == null
                    || !rewrittenNode.isTextual()
                    || subQuestionsNode == null
                    || !subQuestionsNode.isArray()) {
                return fallback(normalized, "REWRITE_INVALID_RESPONSE", logicalCallId);
            }
            String rewritten = rewrittenNode.textValue().trim();
            if (rewritten.isEmpty()) {
                return fallback(normalized, "REWRITE_INVALID_RESPONSE", logicalCallId);
            }

            var unique = new LinkedHashSet<String>();
            for (JsonNode item : subQuestionsNode) {
                if (!item.isTextual()) {
                    return fallback(normalized, "REWRITE_INVALID_RESPONSE", logicalCallId);
                }
                String question = item.textValue().trim();
                if (!question.isEmpty()) {
                    unique.add(question);
                }
            }
            var questions = new ArrayList<>(unique);
            if (questions.isEmpty()) {
                questions.add(rewritten);
            }
            RewriteDisposition disposition = questions.size() > 3
                    ? RewriteDisposition.TOO_MANY_TOPICS
                    : RewriteDisposition.READY;
            return new RewriteResult(
                    normalized,
                    rewritten,
                    questions,
                    RewriteMode.MODEL,
                    disposition,
                    null,
                    logicalCallId);
        } catch (JsonProcessingException exception) {
            return fallback(normalized, "REWRITE_INVALID_RESPONSE", logicalCallId);
        }
    }

    private static String stripSingleFence(String raw) {
        var matcher = JSON_FENCE.matcher(raw);
        return matcher.matches() ? matcher.group(1) : raw;
    }

    private static RewriteResult fallback(String normalized, String code, UUID logicalCallId) {
        return new RewriteResult(
                normalized,
                normalized,
                List.of(normalized),
                RewriteMode.NORMALIZED_FALLBACK,
                RewriteDisposition.READY,
                code,
                logicalCallId);
    }
}
