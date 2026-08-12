package com.app.qa.web;

import com.app.qa.conversation.ConversationBusyException;
import com.app.qa.conversation.IdempotencyKeyReusedException;
import com.app.qa.conversation.InvalidClarificationChoiceException;
import com.app.qa.intent.IntentTreeRevisionConflictException;
import com.app.qa.intent.IntentTreeValidationException;
import com.app.qa.generation.GenerationQueueFullException;
import com.app.qa.generation.ToolsNotSettledException;
import com.app.qa.prompt.PromptBudgetExceededException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = {QaConversationController.class, QaIntentTreeController.class})
final class QaApiExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadable(
            HttpMessageNotReadableException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "请求参数无效",
                "VALIDATION_FAILED",
                "JSON 正文包含未知字段、非法 UUID/枚举或格式错误。",
                request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "请求参数无效",
                "VALIDATION_FAILED",
                "路径或查询参数格式无效。",
                request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ProblemDetail> handleUnsupportedMediaType(
            HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                "媒体类型不受支持",
                "UNSUPPORTED_MEDIA_TYPE",
                "写接口必须使用 Content-Type: application/json。",
                request);
    }

    @ExceptionHandler(QaRequestException.class)
    ResponseEntity<ProblemDetail> handleQaRequest(
            QaRequestException exception, HttpServletRequest request) {
        var response = problem(
                exception.status(),
                exception.title(),
                exception.code(),
                exception.getMessage(),
                request);
        exception.properties().forEach(response.getBody()::setProperty);
        return response;
    }

    @ExceptionHandler(ConversationBusyException.class)
    ResponseEntity<ProblemDetail> handleConversationBusy(
            ConversationBusyException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "当前对话仍在处理中",
                "CONVERSATION_BUSY",
                "请等待当前本轮完成后再发送新问题。",
                request);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyReuse(
            IdempotencyKeyReusedException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "幂等键已用于其他消息",
                "IDEMPOTENCY_KEY_REUSED",
                "clientRequestId 已绑定另一条消息，请为新消息生成新的 ID。",
                request);
    }

    @ExceptionHandler(InvalidClarificationChoiceException.class)
    ResponseEntity<ProblemDetail> handleInvalidClarificationChoice(
            InvalidClarificationChoiceException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "澄清选择无效",
                "CLARIFICATION_CHOICE_INVALID",
                "澄清来源已过期、属于其他对话，或所选方向不在原选项中。",
                request);
    }

    @ExceptionHandler(IntentTreeRevisionConflictException.class)
    ResponseEntity<ProblemDetail> handleIntentTreeRevisionConflict(
            IntentTreeRevisionConflictException exception, HttpServletRequest request) {
        var response = problem(
                HttpStatus.CONFLICT,
                "意图树版本冲突",
                "INTENT_TREE_REVISION_CONFLICT",
                "配置已被更新，请刷新后比较再保存。",
                request);
        response.getBody().setProperty("currentRevisionId", exception.currentRevisionId());
        response.getBody().setProperty("currentRevisionNo", exception.currentRevisionNo());
        return response;
    }

    @ExceptionHandler(IntentTreeValidationException.class)
    ResponseEntity<ProblemDetail> handleIntentTreeValidation(
            IntentTreeValidationException exception, HttpServletRequest request) {
        if (exception.kind() == IntentTreeValidationException.Kind.NOT_FOUND) {
            return problem(
                    HttpStatus.NOT_FOUND,
                    "意图节点不存在",
                    "INTENT_NODE_NOT_FOUND",
                    "当前意图树版本中不存在该节点。",
                    request);
        }
        if (exception.kind() == IntentTreeValidationException.Kind.VIRTUAL_ROOT) {
            return problem(
                    HttpStatus.CONFLICT,
                    "虚拟根不可修改",
                    "VIRTUAL_ROOT_IMMUTABLE",
                    "虚拟根只用于展示，不能修改、启停或删除。",
                    request);
        }
        var response = problem(
                HttpStatus.CONFLICT,
                "意图树约束冲突",
                "INTENT_TREE_CONSTRAINT_VIOLATION",
                "命令不满足意图树结构或配置约束。",
                request);
        response.getBody().setProperty(
                "violations",
                java.util.List.of(java.util.Map.of(
                        "code", "TREE_CONSTRAINT_VIOLATION",
                        "detail", exception.getMessage())));
        return response;
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<ProblemDetail> handleDataAccess(
            DataAccessException exception, HttpServletRequest request) {
        boolean intentTree = request.getRequestURI().startsWith("/api/qa/intent-tree");
        return problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                intentTree ? "意图树保存失败" : "问答数据读取失败",
                intentTree ? "INTENT_TREE_PERSISTENCE_FAILED" : "QA_PERSISTENCE_FAILED",
                "服务暂时无法完成数据操作，请稍后重试。",
                request);
    }

    @ExceptionHandler(GenerationQueueFullException.class)
    ResponseEntity<ProblemDetail> handleGenerationQueueFull(
            GenerationQueueFullException exception, HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "1")
                .body(problem(HttpStatus.TOO_MANY_REQUESTS,
                        "生成队列已满", "GENERATION_QUEUE_FULL",
                        "当前生成任务较多，请稍后重试。", request).getBody());
    }

    @ExceptionHandler(PromptBudgetExceededException.class)
    ResponseEntity<ProblemDetail> handlePromptBudget(
            PromptBudgetExceededException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "生成上下文过长",
                "PROMPT_BUDGET_EXCEEDED",
                "当前问题与必要规则超过模型输入预算。",
                request);
    }

    @ExceptionHandler(ToolsNotSettledException.class)
    ResponseEntity<ProblemDetail> handleToolsNotSettled(
            ToolsNotSettledException exception, HttpServletRequest request) {
        return problem(
                HttpStatus.CONFLICT,
                "工具调用尚未结束",
                "TOOLS_NOT_SETTLED",
                "请先完成工具确认或等待工具执行结束。",
                request);
    }

    private static ResponseEntity<ProblemDetail> problem(
            HttpStatus status,
            String title,
            String code,
            String detail,
            HttpServletRequest request) {
        var problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        String requestId = UUID.randomUUID().toString();
        problem.setProperty("requestId", "req_****" + requestId.substring(requestId.length() - 8));
        return ResponseEntity.status(status).body(problem);
    }
}
