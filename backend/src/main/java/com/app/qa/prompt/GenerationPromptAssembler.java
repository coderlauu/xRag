package com.app.qa.prompt;

import com.app.ai.model.ChatCommand;
import com.app.ai.model.SystemMessage;
import com.app.ai.model.UserMessage;
import com.app.qa.model.QaChatCommandFactory;
import com.app.qa.prompt.UntrustedPromptCodec.Kind;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public final class GenerationPromptAssembler {

    private static final String USER_PREFIX = "{\"protocol\":\"qa-generation-v1\",\"items\":[";
    private static final String USER_SUFFIX = "]}";

    private final PromptWindowBudget budget;
    private final UntrustedPromptCodec codec;

    public GenerationPromptAssembler(PromptWindowBudget budget) {
        this(budget, new UntrustedPromptCodec());
    }

    GenerationPromptAssembler(PromptWindowBudget budget, UntrustedPromptCodec codec) {
        if (budget == null || codec == null) {
            throw new IllegalArgumentException("budget and codec must not be null");
        }
        this.budget = budget;
        this.codec = codec;
    }

    public Result assemble(Material material) {
        if (material == null) {
            throw new IllegalArgumentException("material must not be null");
        }

        var selected = new ArrayList<EncodedItem>();
        selected.add(encode(Kind.USER_QUESTION, material.question()));
        addAll(selected, Kind.RECENT_HISTORY, material.recentHistory());
        addAll(selected, Kind.INTENT_HINT, material.intentHints());
        addAll(selected, Kind.RETRIEVAL_EVIDENCE, material.evidence());
        addAll(selected, Kind.TOOL_RESULT, material.toolResults());
        addAll(selected, Kind.OLD_SUMMARY, material.oldSummaries());

        var evictionOrder = new ArrayList<EncodedItem>();
        addReverse(evictionOrder, selected, Kind.OLD_SUMMARY);
        addReverse(evictionOrder, selected, Kind.TOOL_RESULT);
        addReverse(evictionOrder, selected, Kind.RETRIEVAL_EVIDENCE);
        addReverse(evictionOrder, selected, Kind.INTENT_HINT);
        addReverse(evictionOrder, selected, Kind.RECENT_HISTORY);

        var omittedIds = new ArrayList<String>();
        String userText = renderUser(selected);
        int inputBytes = inputBytes(material.systemRules(), userText);
        for (EncodedItem candidate : evictionOrder) {
            if (inputBytes <= budget.inputBudgetBytes()) {
                break;
            }
            selected.remove(candidate);
            omittedIds.add(candidate.id());
            userText = renderUser(selected);
            inputBytes = inputBytes(material.systemRules(), userText);
        }
        if (inputBytes > budget.inputBudgetBytes()) {
            throw new PromptBudgetExceededException(inputBytes, budget.inputBudgetBytes());
        }

        ChatCommand command = QaChatCommandFactory.balanced(List.of(
                new SystemMessage(material.systemRules()),
                new UserMessage(userText)));
        return new Result(command, inputBytes, omittedIds);
    }

    private EncodedItem encode(Kind kind, Item item) {
        return new EncodedItem(kind, item.id(), codec.encode(kind, item.id(), item.text()));
    }

    private void addAll(List<EncodedItem> target, Kind kind, List<Item> items) {
        for (Item item : items) {
            target.add(encode(kind, item));
        }
    }

    private static void addReverse(List<EncodedItem> target, List<EncodedItem> source, Kind kind) {
        for (int index = source.size() - 1; index >= 0; index--) {
            var item = source.get(index);
            if (item.kind() == kind) {
                target.add(item);
            }
        }
    }

    private static String renderUser(List<EncodedItem> items) {
        return USER_PREFIX + String.join(",", items.stream().map(EncodedItem::json).toList()) + USER_SUFFIX;
    }

    private static int inputBytes(String systemRules, String userText) {
        return Math.addExact(
                systemRules.getBytes(StandardCharsets.UTF_8).length,
                userText.getBytes(StandardCharsets.UTF_8).length);
    }

    private record EncodedItem(Kind kind, String id, String json) {}

    public record Item(String id, String text) {
        public Item {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("item id must not be blank");
            }
            if (text == null) {
                throw new IllegalArgumentException("item text must not be null");
            }
        }
    }

    public record Material(
            String systemRules,
            Item question,
            List<Item> recentHistory,
            List<Item> intentHints,
            List<Item> evidence,
            List<Item> toolResults,
            List<Item> oldSummaries) {

        public Material {
            if (systemRules == null || systemRules.isBlank()) {
                throw new IllegalArgumentException("system rules must not be blank");
            }
            if (question == null) {
                throw new IllegalArgumentException("question must not be null");
            }
            recentHistory = copy(recentHistory);
            intentHints = copy(intentHints);
            evidence = copy(evidence);
            toolResults = copy(toolResults);
            oldSummaries = copy(oldSummaries);

            var ids = new HashSet<String>();
            for (Item item : all(question, recentHistory, intentHints, evidence, toolResults, oldSummaries)) {
                if (!ids.add(item.id())) {
                    throw new IllegalArgumentException("prompt item id must be unique: " + item.id());
                }
            }
        }

        private static List<Item> copy(List<Item> items) {
            return items == null ? List.of() : List.copyOf(items);
        }

        @SafeVarargs
        private static List<Item> all(Item question, List<Item>... groups) {
            var result = new ArrayList<Item>();
            result.add(question);
            for (List<Item> group : groups) {
                result.addAll(group);
            }
            return result;
        }
    }

    public record Result(ChatCommand command, int inputBytes, List<String> omittedIds) {
        public Result {
            omittedIds = List.copyOf(omittedIds);
        }
    }
}
