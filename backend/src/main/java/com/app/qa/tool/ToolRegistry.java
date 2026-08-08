package com.app.qa.tool;

import com.app.ai.model.ToolDefinition;
import com.app.qa.config.QaToolProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 工具白名单注册表（架构 §21.2）。
 *
 * <p>只在构造期从配置装配一次，之后**只读**。接口上没有 {@code register()} / {@code unregister()}
 * ——课程实现暴露了运行期变更能力却按启动期一次性写入来做（普通 HashMap、无同步），
 * 那是"接口声明的能力与实现假设不一致"的典型。这里干脆不给那两个方法。
 *
 * <p>重复 {@code name} 在 {@link QaToolProperties} 就已经启动失败，不到这里。
 */
@Component
public class ToolRegistry {

    private final Map<String, QaToolProperties.Tool> byName;
    private final List<ToolDefinition> definitions;

    public ToolRegistry(QaToolProperties properties) {
        var index = new LinkedHashMap<String, QaToolProperties.Tool>();
        for (var tool : properties.getWhitelist()) {
            index.put(tool.getName(), tool);
        }
        this.byName = Map.copyOf(index);
        this.definitions = properties.getWhitelist().stream()
                .map(tool -> new ToolDefinition(
                        tool.getName(), tool.getDescription(), tool.getInputSchemaJson()))
                .toList();
    }

    /**
     * 交给模型的工具定义。
     *
     * <p><b>空清单返回空列表，调用方据此不发送 {@code tools} 字段。</b>"没有配置工具"与
     * "配置了但不可用"是两回事——前者不该产生任何"工具不可用"提示（PRD §12.6）。
     */
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    public boolean isWhitelisted(String name) {
        return byName.containsKey(name);
    }

    public Optional<QaToolProperties.Tool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }
}
