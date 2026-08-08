package com.app.qa.tool;

import com.app.ai.model.ToolCall;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 把模型的一次 {@code ToolCall} 判成"下一步该处于哪个状态"（架构 §21.4）。
 *
 * <p>这一层刻意**不执行任何工具、不写库**：它是纯函数，因此"确认前一律不执行"这条语义
 * 可以在没有任何外部依赖的测试里被证明——不需要靠观察日志或数据库来推断。
 * 真正的执行与持久化在工单 10。
 */
@Component
public class ToolCallAdmission {

    /**
     * @param status      下一步状态
     * @param sanitized   复核通过后的参数；仅在 {@code PENDING_CONFIRM} / {@code RUNNING} 时非空
     * @param violations  复核违规；仅在 {@code ABORTED} 时非空
     * @param failureCode 归一化失败码；仅在 {@code UNAVAILABLE} / {@code ABORTED} 时非空
     */
    public record Decision(
            ToolCallStatus status,
            ObjectNode sanitized,
            List<ToolArgumentViolation> violations,
            ToolFailureCode failureCode,
            boolean sideEffect) {

        public Decision {
            violations = List.copyOf(violations);
        }
    }

    private final ToolRegistry registry;
    private final ToolArgumentValidator validator;

    public ToolCallAdmission(ToolRegistry registry, ToolArgumentValidator validator) {
        this.registry = registry;
        this.validator = validator;
    }

    public Decision admit(ToolCall call) {
        var tool = registry.find(call.name()).orElse(null);
        if (tool == null) {
            // 不静默跳过。用户看到的是"这个工具不在清单里"，而不是"系统好像没做这件事"。
            return new Decision(
                    ToolCallStatus.UNAVAILABLE, null, List.of(), ToolFailureCode.NOT_WHITELISTED, false);
        }
        var result = validator.validate(tool.getInputSchemaJson(), call.argumentsJson());
        if (!result.ok()) {
            return new Decision(
                    ToolCallStatus.ABORTED,
                    null,
                    result.violations(),
                    ToolFailureCode.ARGUMENTS_INVALID,
                    tool.isSideEffect());
        }
        // 有副作用 → 等用户裁决；只读 → 直接执行。参数在两种情况下都全程可见（PRD §12.6）。
        return new Decision(
                tool.isSideEffect() ? ToolCallStatus.PENDING_CONFIRM : ToolCallStatus.RUNNING,
                result.sanitized(),
                List.of(),
                null,
                tool.isSideEffect());
    }
}
