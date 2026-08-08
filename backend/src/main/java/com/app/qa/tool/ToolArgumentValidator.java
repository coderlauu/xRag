package com.app.qa.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * 代码侧 Schema 复核（架构 §21.3）。
 *
 * <p><b>这是安全边界，不是防御性编程。</b>{@code ToolCall.argumentsJson} 只经过"是合法 JSON"
 * 的校验，Provider 层不做任何 Schema 校验——模型完全可以返回 Schema 里不存在的字段，或者给
 * {@code integer} 参数塞一个字符串。传给外部系统的参数不能是模型随口编的。
 *
 * <p>五道复核逐条对应 PRD §12.7：
 * <ol>
 *   <li><b>字段名白名单</b>——遍历 Schema 声明的参数名，不遍历模型返回的 key。编造的字段直接丢弃。</li>
 *   <li><b>类型校验</b>——Schema 说 integer 而模型给 10.5 或字符串，拒绝。</li>
 *   <li><b>取值校验</b>——字段名对但值不在 {@code enum} 内同样拒绝。只校验字段名是"把约束告诉了
 *       模型就当模型一定会遵守"，而同一个仓库在意图评分那边是代码兜底的，两处不该前后不一致。</li>
 *   <li><b>必填校验</b>——缺任一必填参数不得调用工具，不存在"过滤掉后照常执行"的路径。</li>
 *   <li><b>禁止默认值兜底</b>——本类<b>不读 Schema 的 {@code default}</b>。抽取失败即中止；
 *       "降级到全默认值"对只读查询勉强说得通，对提交工单或发起退款是灾难。</li>
 * </ol>
 */
@Component
public class ToolArgumentValidator {

    /** 复核结果：要么给出可安全执行的参数，要么给出逐条违规。两者不会同时非空。 */
    public record Result(ObjectNode sanitized, List<ToolArgumentViolation> violations) {

        public Result {
            violations = List.copyOf(violations);
        }

        public boolean ok() {
            return violations.isEmpty();
        }
    }

    private final ObjectMapper mapper = new ObjectMapper();

    public Result validate(String inputSchemaJson, String argumentsJson) {
        JsonNode schema = readTree(Objects.requireNonNull(inputSchemaJson, "schema"));
        JsonNode arguments = argumentsJson == null ? mapper.createObjectNode() : readTree(argumentsJson);
        if (arguments == null || !arguments.isObject()) {
            // 模型没给出 JSON object——所有必填参数都视为缺失，而不是"当成空参数执行"。
            return new Result(mapper.createObjectNode(), requiredNames(schema).stream()
                    .map(ToolArgumentViolation::missing)
                    .toList());
        }

        JsonNode properties = schema == null ? null : schema.get("properties");
        List<String> required = requiredNames(schema);
        var violations = new ArrayList<ToolArgumentViolation>();
        ObjectNode sanitized = mapper.createObjectNode();

        // 第 1 道：遍历 Schema 的参数名，不遍历模型返回的 key。
        if (properties != null && properties.isObject()) {
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode declared = properties.get(name);
                JsonNode value = arguments.get(name);
                if (value == null || value.isNull()) {
                    if (required.contains(name)) {
                        violations.add(ToolArgumentViolation.missing(name));
                    }
                    continue;
                }
                var violation = checkValue(name, declared, value);
                if (violation != null) {
                    violations.add(violation);
                } else {
                    sanitized.set(name, value);
                }
            }
        }
        return new Result(violations.isEmpty() ? sanitized : mapper.createObjectNode(), violations);
    }

    private ToolArgumentViolation checkValue(String name, JsonNode declared, JsonNode value) {
        String type = declared.path("type").asText("");
        boolean typeOk = switch (type) {
            case "string" -> value.isTextual();
            // Jackson 保留 IntNode 与 DoubleNode 的区别，所以 10 不会变成 10.0。但那解决的是
            // 序列化库的问题，模型返回 10.5 给 integer 是另一个问题，仍然要拦。
            case "integer" -> value.isIntegralNumber();
            case "number" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            case "array" -> value.isArray();
            case "object" -> value.isObject();
            case "" -> true;
            default -> true;
        };
        if (!typeOk) {
            return ToolArgumentViolation.typeMismatch(name);
        }
        JsonNode allowed = declared.get("enum");
        if (allowed != null && allowed.isArray()) {
            var values = new ArrayList<String>();
            boolean matched = false;
            for (JsonNode option : allowed) {
                values.add(option.asText());
                if (option.equals(value)) {
                    matched = true;
                }
            }
            if (!matched) {
                return ToolArgumentViolation.notAllowed(name, values);
            }
        }
        return null;
    }

    private List<String> requiredNames(JsonNode schema) {
        JsonNode required = schema == null ? null : schema.get("required");
        if (required == null || !required.isArray()) {
            return List.of();
        }
        var names = new ArrayList<String>();
        required.forEach(node -> names.add(node.asText()));
        return names;
    }

    private JsonNode readTree(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception exception) {
            return null;
        }
    }
}
