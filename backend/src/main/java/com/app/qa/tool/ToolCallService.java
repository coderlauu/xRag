package com.app.qa.tool;

import com.app.ai.model.ToolCall;
import com.app.qa.config.QaToolProperties;
import com.app.web.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 工具调用的落库与裁决（架构 §21.4）。
 *
 * <p>三条纪律：
 * <ul>
 *   <li><b>确认前零外部调用。</b>三层都拦：数据库 check 约束、状态 CAS、以及本类里
 *       "只有 CAS 赢家才调 executor"。</li>
 *   <li><b>工具执行在事务外。</b>它是外部 IO，放进事务会把连接占满整个调用时长。</li>
 *   <li><b>重试不复用旧行。</b>失败后重试追加一条新记录，有副作用的重新回到待确认——
 *       上一次的确认不能被复用（ui-spec §13.7.4）。</li>
 * </ul>
 */
@Service
public class ToolCallService {

    public enum Decision {
        CONFIRM,
        CANCEL
    }

    private final ToolCallRepository repository;
    private final ToolRegistry tools;
    private final ToolCallAdmission admission;
    private final ToolExecutorRegistry executors;

    public ToolCallService(
            ToolCallRepository repository,
            ToolRegistry tools,
            ToolCallAdmission admission,
            ToolExecutorRegistry executors) {
        this.repository = repository;
        this.tools = tools;
        this.admission = admission;
        this.executors = executors;
    }

    /**
     * 记录模型请求的一次工具调用，并按复核结果决定下一步。
     *
     * <p>只读工具在这里就执行完；有副作用的停在 {@code PENDING_CONFIRM}，一次外部调用都不发。
     */
    public UUID record(UUID turnId, ToolCall call) {
        var decision = admission.admit(call);
        String arguments = decision.sanitized() == null ? null : decision.sanitized().toString();
        UUID toolCallId = repository.insert(
                turnId,
                call.name(),
                decision.sideEffect(),
                decision.status(),
                arguments,
                // failure_code 只属于 FAILED——"执行了但失败了"才需要说明原因。
                // UNAVAILABLE 与 ABORTED 的状态本身就是原因，再存一份码值是冗余，
                // 数据库的 ck_qa_tool_failure 也正是这么约束的（数据模型 §15.1）。
                null);
        if (decision.status() == ToolCallStatus.RUNNING) {
            executeOutsideTransaction(toolCallId, call.name(), arguments);
        }
        return toolCallId;
    }

    /**
     * 用户裁决。{@code CONFIRM} 与 {@code CANCEL} 走同一入口——它们是同一个决定的两面，
     * 拆成两个方法会让"只允许决定一次"这条约束散落在两处。
     */
    public ToolCallRepository.ToolCallRow decide(
            UUID turnId, UUID toolCallId, Decision decision, UUID clientRequestId) {
        var row = repository
                .find(turnId, toolCallId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "TOOL_CALL_NOT_FOUND", "找不到这次工具调用。"));

        // 幂等重放：同一个 clientRequestId 再来一次，返回当时的结果而不是报冲突。
        // 对有副作用的工具这是硬要求——网络抖动的重试不该变成两张工单。
        if (clientRequestId.equals(row.confirmRequestId())) {
            return repository.find(turnId, toolCallId).orElseThrow();
        }
        if (row.status() != ToolCallStatus.PENDING_CONFIRM) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "TOOL_CALL_NOT_PENDING", "这次工具调用已经处理过了。");
        }

        if (decision == Decision.CANCEL) {
            repository.cancel(toolCallId, clientRequestId);
            return repository.find(turnId, toolCallId).orElseThrow();
        }
        if (!repository.confirm(toolCallId, clientRequestId)) {
            // 并发下输给了另一次裁决。没赢就绝不执行。
            throw new ApiException(
                    HttpStatus.CONFLICT, "TOOL_CALL_NOT_PENDING", "这次工具调用已经处理过了。");
        }
        executeOutsideTransaction(toolCallId, row.toolName(), row.argumentsJson());
        return repository.find(turnId, toolCallId).orElseThrow();
    }

    /** 失败后重试：追加一条新记录，有副作用的重新回到待确认。 */
    public UUID retry(UUID turnId, UUID toolCallId) {
        var row = repository
                .find(turnId, toolCallId)
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND, "TOOL_CALL_NOT_FOUND", "找不到这次工具调用。"));
        if (row.status() != ToolCallStatus.FAILED) {
            throw new ApiException(
                    HttpStatus.CONFLICT, "TOOL_CALL_NOT_RETRYABLE", "只有失败的工具调用可以重试。");
        }
        UUID retried = repository.insert(
                turnId,
                row.toolName(),
                row.sideEffect(),
                row.sideEffect() ? ToolCallStatus.PENDING_CONFIRM : ToolCallStatus.RUNNING,
                row.argumentsJson(),
                null);
        if (!row.sideEffect()) {
            executeOutsideTransaction(retried, row.toolName(), row.argumentsJson());
        }
        return retried;
    }

    public List<ToolCallRepository.ToolCallRow> findByTurn(UUID turnId) {
        return repository.findByTurn(turnId);
    }

    private void executeOutsideTransaction(UUID toolCallId, String toolName, String argumentsJson) {
        Duration timeout = tools.find(toolName)
                .map(QaToolProperties.Tool::getTimeout)
                .orElse(Duration.ofSeconds(10));
        long startedAt = System.nanoTime();
        ToolExecutionResult result;
        try {
            result = executors.require(toolName).execute(argumentsJson, timeout);
        } catch (RuntimeException exception) {
            // 实现方本不该抛（见 ToolExecutor 契约），但真抛了也不能让异常 message 逃出去。
            result = ToolExecutionResult.failure(ToolFailureCode.EXECUTION_FAILED);
        }
        repository.complete(toolCallId, result, Duration.ofNanos(System.nanoTime() - startedAt));
    }
}
