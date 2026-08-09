package com.app.qa.web;

import com.app.qa.tool.ToolCallRepository;
import com.app.qa.tool.ToolCallService;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 工具裁决端点（API §10）。
 *
 * <p>返回的是**本轮全部工具调用**而不是单条：客户端据此整体替换本地状态，不需要自己拼合
 * "这条变了、那条没变"。重试会产生新的 toolCallId，返回整份列表也就顺带覆盖了这种情况。
 */
@RestController
@RequestMapping("/api/qa")
public final class QaToolCallController {

    private final ToolCallService toolCalls;

    public QaToolCallController(ToolCallService toolCalls) {
        this.toolCalls = toolCalls;
    }

    @PostMapping("/turns/{turnId}/tool-calls/{toolCallId}/decision")
    public ResponseEntity<ToolCallsResponse> decide(
            @PathVariable UUID turnId,
            @PathVariable UUID toolCallId,
            @RequestBody DecisionRequest request) {
        request.validate();
        toolCalls.decide(turnId, toolCallId, request.decision(), request.clientRequestId());
        return ResponseEntity.ok(response(turnId));
    }

    @PostMapping("/turns/{turnId}/tool-calls/{toolCallId}/retry")
    public ResponseEntity<ToolCallsResponse> retry(
            @PathVariable UUID turnId, @PathVariable UUID toolCallId) {
        toolCalls.retry(turnId, toolCallId);
        return ResponseEntity.ok(response(turnId));
    }

    private ToolCallsResponse response(UUID turnId) {
        return new ToolCallsResponse(
                toolCalls.findByTurn(turnId).stream().map(ToolCallView::from).toList());
    }

    public record DecisionRequest(UUID clientRequestId, ToolCallService.Decision decision) {

        void validate() {
            if (clientRequestId == null) {
                throw new QaRequestException(
                        HttpStatus.BAD_REQUEST,
                        "请求不合法",
                        "INVALID_REQUEST",
                        "clientRequestId 不能为空。");
            }
            if (decision == null) {
                throw new QaRequestException(
                        HttpStatus.BAD_REQUEST,
                        "请求不合法",
                        "INVALID_REQUEST",
                        "decision 必须是 CONFIRM 或 CANCEL。");
            }
        }
    }

    public record ToolCallsResponse(List<ToolCallView> tools) {}

    /**
     * 对外视图。
     *
     * <p><b>不含 Schema 原文、不含模型原始参数、不含异常 message</b>——`argumentsJson` 存的已经是
     * 复核通过后的参数，`failureCode` 是闭集码值（API §9）。
     */
    public record ToolCallView(
            UUID toolCallId,
            int ordinal,
            String toolName,
            boolean sideEffect,
            String status,
            String argumentsJson,
            String resultText,
            String failureCode,
            Integer latencyMs) {

        static ToolCallView from(ToolCallRepository.ToolCallRow row) {
            return new ToolCallView(
                    row.id(),
                    row.ordinal(),
                    row.toolName(),
                    row.sideEffect(),
                    row.status().name(),
                    row.argumentsJson(),
                    row.resultText(),
                    row.failureCode() == null ? null : row.failureCode().name(),
                    row.latencyMs());
        }
    }
}
