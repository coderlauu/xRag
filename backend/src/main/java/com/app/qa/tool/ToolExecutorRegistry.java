package com.app.qa.tool;

import com.app.qa.config.QaToolProperties;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * {@code name → ToolExecutor} 的启动期装配。
 *
 * <p><b>白名单里有、却没有对应执行器的工具，启动即失败。</b>理由与"重复 toolId 启动失败"同源：
 * 放行只会把问题推迟到用户点了"确认执行"之后——那时工具已经被承诺可以执行，却在最后一步
 * 报"没有执行器"，而这在界面上和真正的工具故障看起来一模一样。
 */
@Component
public class ToolExecutorRegistry implements InitializingBean {

    private final QaToolProperties properties;
    private final Map<String, ToolExecutor> byName;

    public ToolExecutorRegistry(QaToolProperties properties, List<ToolExecutor> executors) {
        this.properties = properties;
        var index = new LinkedHashMap<String, ToolExecutor>();
        for (ToolExecutor executor : executors) {
            ToolExecutor previous = index.put(executor.name(), executor);
            if (previous != null) {
                throw new IllegalStateException(
                        "two ToolExecutor beans claim the same tool name '" + executor.name() + "'");
            }
        }
        this.byName = Map.copyOf(index);
    }

    @Override
    public void afterPropertiesSet() {
        for (var tool : properties.getWhitelist()) {
            if (!byName.containsKey(tool.getName())) {
                throw new IllegalStateException(
                        "app.qa.tools.whitelist[" + tool.getName() + "] has no ToolExecutor bean; "
                                + "a whitelisted tool that cannot be executed would only fail after the user "
                                + "already confirmed it");
            }
        }
    }

    public ToolExecutor require(String name) {
        ToolExecutor executor = byName.get(name);
        if (executor == null) {
            throw new IllegalStateException("no ToolExecutor registered for '" + name + "'");
        }
        return executor;
    }
}
