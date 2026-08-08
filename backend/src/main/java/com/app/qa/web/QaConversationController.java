package com.app.qa.web;

import com.app.qa.conversation.ConversationBusyException;
import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.conversation.TurnProcessor;
import com.app.qa.model.MessageRole;
import com.app.qa.model.MessageType;
import com.app.qa.model.RetrievalActionType;
import com.app.qa.model.RewriteMode;
import com.app.qa.model.SystemAction;
import com.app.qa.model.TurnStatus;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/qa")
public final class QaConversationController {

    private static final Logger LOGGER = LoggerFactory.getLogger(QaConversationController.class);

    private final ConversationPersistenceService conversations;
    private final ObjectProvider<TurnProcessor> processors;

    public QaConversationController(
            ConversationPersistenceService conversations, ObjectProvider<TurnProcessor> processors) {
        this.conversations = conversations;
        this.processors = processors;
    }

    @GetMapping("/conversations")
    public CursorPage<ConversationListItem> conversations(
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        validateLimit(limit);
        ConversationCursor decoded = decodeConversationCursor(cursor);
        var found = conversations.findConversations(decoded.activity(), decoded.conversationId(), limit + 1);
        boolean hasMore = found.size() > limit;
        var items = found.stream()
                .limit(limit)
                .map(conversation -> new ConversationListItem(
                        conversation.conversationId(),
                        conversation.title(),
                        conversation.version(),
                        conversation.lastActiveAt(),
                        conversation.messagePreview(),
                        conversation.activeTurn()))
                .toList();
        String nextCursor = hasMore
                ? encodeConversationCursor(found.get(limit - 1))
                : null;
        return new CursorPage<>(items, nextCursor);
    }

    @GetMapping("/conversations/{conversationId}")
    public ConversationDetailResponse conversation(@PathVariable UUID conversationId) {
        var conversation = requireConversation(conversationId);
        var latestTurn = conversations.findLatestStableTurn(conversationId)
                .map(turn -> new LatestTurnResponse(turn.turnId(), turn.status(), turn.completedAt()))
                .orElse(null);
        return new ConversationDetailResponse(
                conversation.conversationId(),
                conversation.title(),
                conversation.version(),
                conversation.lastActiveAt(),
                latestTurn);
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public MessagePage messages(
            @PathVariable UUID conversationId,
            @RequestParam(required = false) String before,
            @RequestParam(defaultValue = "50") int limit) {
        validateLimit(limit);
        requireConversation(conversationId);
        Long beforeSequence = decodeMessageCursor(before);
        var found = conversations.findMessages(conversationId, beforeSequence, limit + 1);
        boolean hasMore = found.size() > limit;
        var visible = hasMore ? found.subList(1, found.size()) : found;
        var items = visible.stream()
                .map(message -> new MessageResponse(
                        message.messageId(),
                        message.turnId(),
                        message.sequenceNo(),
                        message.role(),
                        message.type(),
                        message.content(),
                        message.createdAt()))
                .toList();
        String previousCursor = hasMore ? encodeMessageCursor(visible.get(0).sequenceNo()) : null;
        return new MessagePage(items, previousCursor);
    }

    @GetMapping("/conversations/{conversationId}/turns/{turnId}")
    public TurnLookupResponse turn(
            @PathVariable UUID conversationId, @PathVariable UUID turnId) {
        var turn = conversations.findTurn(turnId)
                .filter(found -> found.conversationId().equals(conversationId))
                .orElseThrow(QaConversationController::turnNotFound);
        var extras = turnExtras(turn.turnId());
        return new TurnLookupResponse(
                turnResponse(turn), extras.inspector(), extras.assistantMessage(), extras.clarification());
    }

    @PatchMapping("/conversations/{conversationId}")
    public ConversationMutationResponse rename(
            @PathVariable UUID conversationId, @RequestBody RenameConversationRequest request) {
        var current = requireConversation(conversationId);
        if (request == null
                || request.expectedVersion() == null
                || request.expectedVersion() < 0
                || request.title() == null
                || request.title().isBlank()
                || request.title().length() > 200) {
            throw validationFailed("标题必须包含 1 到 200 个字符，且 expectedVersion 不能为负数。");
        }
        if (!conversations.renameConversation(conversationId, request.expectedVersion(), request.title())) {
            throw versionConflict(conversationId, current.version());
        }
        var renamed = requireConversation(conversationId);
        return new ConversationMutationResponse(
                renamed.conversationId(), renamed.title(), renamed.version());
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID conversationId, @RequestBody DeleteConversationRequest request) {
        var current = requireConversation(conversationId);
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw validationFailed("expectedVersion 不能为负数。");
        }
        try {
            if (!conversations.softDeleteConversation(conversationId, request.expectedVersion())) {
                throw versionConflict(conversationId, current.version());
            }
        } catch (ConversationBusyException exception) {
            throw new QaRequestException(
                    HttpStatus.CONFLICT,
                    "活动本轮阻止删除",
                    "CONVERSATION_HAS_ACTIVE_TURN",
                    "请等待当前本轮完成后再删除对话。");
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/turns")
    public ResponseEntity<TurnResponse> submit(@RequestBody SubmitTurnRequest request) {
        validateSubmission(request);
        if (request.conversationId() != null) {
            requireConversation(request.conversationId());
        }
        TurnProcessor processor = processors.getIfAvailable();
        if (processor == null) {
            throw new QaRequestException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "问答处理器不可用",
                    "QA_PROCESSOR_UNAVAILABLE",
                    "问答处理器尚未就绪，请稍后重试。");
        }
        ConversationPersistenceService.TurnSubmission submission;
        try {
            submission = request.message().type() == TurnMessageType.QUESTION
                    ? conversations.submitQuestion(
                            request.conversationId(), request.clientRequestId(), request.message().text())
                    : conversations.submitClarificationChoice(
                            request.conversationId(),
                            request.clientRequestId(),
                            request.message().sourceTurnId(),
                            request.message().selectedNodeId(),
                            request.message().noneOfTheAbove());
        } catch (DataAccessException exception) {
            throw new QaRequestException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "消息保存失败",
                    "MESSAGE_PERSISTENCE_FAILED",
                    "消息尚未保存，请稍后重试。");
        }
        if (!submission.replayed()) {
            try {
                processor.process(submission);
            } catch (RuntimeException exception) {
                stabilizeProcessorFailure(submission, exception);
            }
        }
        var conversation = conversations.findConversation(submission.conversationId())
                .orElseThrow(() -> new IllegalStateException("saved conversation is not readable"));
        var turn = conversations.findTurn(submission.turnId())
                .orElseThrow(() -> new IllegalStateException("saved turn is not readable"));
        var extras = turnExtras(turn.turnId());
        var response = new TurnResponse(
                new ConversationResponse(
                        conversation.conversationId(),
                        conversation.title(),
                        conversation.version(),
                        submission.createdConversation()),
                turnResponse(turn),
                extras.inspector(),
                extras.assistantMessage(),
                extras.clarification());
        return ResponseEntity.status(submission.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(response);
    }

    private void stabilizeProcessorFailure(
            ConversationPersistenceService.TurnSubmission submission, RuntimeException exception) {
        LOGGER.warn(
                "QA turn processor failed; converging turn {} in conversation {}",
                submission.turnId(),
                submission.conversationId());
        try {
            conversations.markProcessing(submission.turnId());
            conversations.completeTurn(submission.turnId(), TurnStatus.FAILED, "PROCESSING_FAILED");
            var stable = conversations.findTurn(submission.turnId());
            if (stable.isPresent() && isStable(stable.get().status())) {
                return;
            }
        } catch (RuntimeException recoveryFailure) {
            exception.addSuppressed(recoveryFailure);
        }
        throw new QaRequestException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "本轮处理失败",
                "TURN_PERSISTENCE_FAILED",
                "本轮未能收敛为稳定状态，请稍后刷新重试。");
    }

    private static boolean isStable(TurnStatus status) {
        return status != TurnStatus.SAVED && status != TurnStatus.PROCESSING;
    }

    private static void validateSubmission(SubmitTurnRequest request) {
        if (request == null || request.clientRequestId() == null || request.message() == null) {
            throw new QaRequestException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "请求参数无效",
                    "VALIDATION_FAILED",
                    "本轮请求必须包含 clientRequestId 和 message。");
        }
        var message = request.message();
        if (message.type() == TurnMessageType.QUESTION) {
            if (message.text() == null
                    || message.text().isBlank()
                    || message.text().codePointCount(0, message.text().length()) > 20_000
                    || message.sourceTurnId() != null
                    || message.selectedNodeId() != null
                    || message.noneOfTheAbove() != null) {
                throw validationFailed("QUESTION 必须且只能包含 1 到 20000 个字符的 text。");
            }
            return;
        }
        if (message.type() != TurnMessageType.CLARIFICATION_CHOICE
                || request.conversationId() == null
                || message.text() != null
                || message.sourceTurnId() == null
                || message.noneOfTheAbove() == null
                || message.noneOfTheAbove() == (message.selectedNodeId() != null)) {
            throw validationFailed(
                    "CLARIFICATION_CHOICE 必须包含 conversationId、sourceTurnId，且 selectedNodeId 与 noneOfTheAbove=true 互斥。");
        }
    }

    private ConversationPersistenceService.ConversationView requireConversation(UUID conversationId) {
        return conversations.findConversation(conversationId).orElseThrow(() -> new QaRequestException(
                HttpStatus.NOT_FOUND,
                "对话不存在",
                "CONVERSATION_NOT_FOUND",
                "对话不存在或已删除。"));
    }

    private static TurnResponseBody turnResponse(ConversationPersistenceService.TurnView turn) {
        return new TurnResponseBody(
                turn.turnId(),
                turn.userMessageId(),
                turn.status(),
                turn.terminalReason(),
                turn.startedAt(),
                turn.completedAt(),
                turn.degradations(),
                turn.intentRevisionId());
    }

    private TurnExtras turnExtras(UUID turnId) {
        var inspector = conversations.findInspector(turnId).orElse(null);
        if (inspector == null) {
            return new TurnExtras(null, null, null);
        }
        var inspectorResponse = new InspectorResponse(
                new HistoryInspectorResponse(
                        inspector.summaryUsed(), inspector.recentCompleteTurns()),
                new RewriteInspectorResponse(
                        inspector.rewriteMode(), inspector.rewrittenQuestion()),
                inspector.subQuestions().stream()
                        .map(question -> new SubQuestionInspectorResponse(
                                question.subQuestionId(),
                                question.ordinal(),
                                question.text(),
                                question.matches().stream()
                                        .map(match -> new MatchInspectorResponse(
                                                match.nodeId(),
                                                match.type(),
                                                match.fullPath(),
                                                match.score(),
                                                match.rank()))
                                        .toList(),
                                question.actions().stream()
                                        .map(action -> new ActionInspectorResponse(
                                                action.actionId(),
                                                action.type(),
                                                action.knowledgeBaseId(),
                                                action.intentNodeId(),
                                                action.systemAction(),
                                                action.reasonCode()))
                                        .toList()))
                        .toList());
        MessageResponse assistant = inspector.assistantMessage() == null
                ? null
                : new MessageResponse(
                        inspector.assistantMessage().messageId(),
                        inspector.assistantMessage().turnId(),
                        inspector.assistantMessage().sequenceNo(),
                        inspector.assistantMessage().role(),
                        inspector.assistantMessage().type(),
                        inspector.assistantMessage().content(),
                        inspector.assistantMessage().createdAt());
        ClarificationResponse clarification = inspector.options().isEmpty()
                ? null
                : new ClarificationResponse(
                        inspector.options().stream()
                                .map(option -> new ClarificationOptionResponse(
                                        option.nodeId(), option.label()))
                                .toList(),
                        true);
        return new TurnExtras(inspectorResponse, assistant, clarification);
    }

    private QaRequestException versionConflict(UUID conversationId, long previouslyReadVersion) {
        var latest = conversations.findConversation(conversationId);
        if (latest.isEmpty()) {
            return new QaRequestException(
                    HttpStatus.NOT_FOUND,
                    "对话不存在",
                    "CONVERSATION_NOT_FOUND",
                    "对话不存在或已删除。");
        }
        long currentVersion = latest.map(ConversationPersistenceService.ConversationView::version)
                .orElse(previouslyReadVersion);
        return new QaRequestException(
                HttpStatus.CONFLICT,
                "对话版本冲突",
                "CONVERSATION_VERSION_CONFLICT",
                "对话已被更新，请刷新后重试。",
                Map.of("currentVersion", currentVersion));
    }

    private static QaRequestException validationFailed(String detail) {
        return new QaRequestException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "请求参数无效",
                "VALIDATION_FAILED",
                detail);
    }

    private static QaRequestException turnNotFound() {
        return new QaRequestException(
                HttpStatus.NOT_FOUND,
                "本轮不存在",
                "TURN_NOT_FOUND",
                "本轮不存在、已删除或不属于当前对话。");
    }

    private static void validateLimit(int limit) {
        if (limit < 1 || limit > 100) {
            throw new QaRequestException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "请求参数无效",
                    "VALIDATION_FAILED",
                    "limit 必须在 1 到 100 之间。");
        }
    }

    private static ConversationCursor decodeConversationCursor(String cursor) {
        if (cursor == null) {
            return new ConversationCursor(null, null);
        }
        try {
            String decoded = decode(cursor);
            int separator = decoded.lastIndexOf('|');
            return new ConversationCursor(
                    OffsetDateTime.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private static Long decodeMessageCursor(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            long sequence = Long.parseLong(decode(cursor));
            if (sequence < 1) {
                throw invalidCursor();
            }
            return sequence;
        } catch (RuntimeException exception) {
            throw invalidCursor();
        }
    }

    private static String encodeConversationCursor(
            ConversationPersistenceService.ConversationView conversation) {
        return encode(conversation.lastActiveAt() + "|" + conversation.conversationId());
    }

    private static String encodeMessageCursor(long sequence) {
        return encode(Long.toString(sequence));
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String value) {
        return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
    }

    private static QaRequestException invalidCursor() {
        return new QaRequestException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "游标无效",
                "INVALID_CURSOR",
                "请移除游标并重新加载第一页。");
    }

    public enum TurnMessageType {
        QUESTION,
        CLARIFICATION_CHOICE
    }

    public record SubmitTurnRequest(
            UUID clientRequestId, UUID conversationId, TurnMessageRequest message) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record TurnMessageRequest(
            TurnMessageType type,
            String text,
            UUID sourceTurnId,
            UUID selectedNodeId,
            Boolean noneOfTheAbove) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record TurnResponse(
            ConversationResponse conversation,
            TurnResponseBody turn,
            InspectorResponse inspector,
            MessageResponse assistantMessage,
            ClarificationResponse clarification) {}

    public record TurnLookupResponse(
            TurnResponseBody turn,
            InspectorResponse inspector,
            MessageResponse assistantMessage,
            ClarificationResponse clarification) {}

    public record RenameConversationRequest(String title, Long expectedVersion) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record DeleteConversationRequest(Long expectedVersion) {

        @JsonAnySetter
        public void rejectUnknownField(String name, Object value) {
            throw new IllegalArgumentException("unknown field: " + name);
        }
    }

    public record ConversationMutationResponse(UUID conversationId, String title, long version) {}

    public record CursorPage<T>(List<T> items, String nextCursor) {}

    public record ConversationListItem(
            UUID conversationId,
            String title,
            long version,
            OffsetDateTime lastActiveAt,
            String messagePreview,
            boolean activeTurn) {}

    public record ConversationDetailResponse(
            UUID conversationId,
            String title,
            long version,
            OffsetDateTime lastActiveAt,
            LatestTurnResponse latestTurn) {}

    public record LatestTurnResponse(UUID turnId, TurnStatus status, OffsetDateTime completedAt) {}

    public record MessagePage(List<MessageResponse> items, String previousCursor) {}

    public record MessageResponse(
            UUID messageId,
            UUID turnId,
            long sequenceNo,
            MessageRole role,
            MessageType type,
            String content,
            OffsetDateTime createdAt) {}

    public record ConversationResponse(
            UUID conversationId, String title, long version, boolean created) {}

    public record TurnResponseBody(
            UUID turnId,
            UUID userMessageId,
            TurnStatus status,
            String terminalReason,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            List<String> degradations,
            UUID intentRevisionId) {}

    public record InspectorResponse(
            HistoryInspectorResponse history,
            RewriteInspectorResponse rewrite,
            List<SubQuestionInspectorResponse> subQuestions) {}

    public record HistoryInspectorResponse(boolean summaryUsed, int recentCompleteTurns) {}

    public record RewriteInspectorResponse(RewriteMode mode, String rewrittenQuestion) {}

    public record SubQuestionInspectorResponse(
            UUID subQuestionId,
            int ordinal,
            String text,
            List<MatchInspectorResponse> matches,
            List<ActionInspectorResponse> actions) {}

    public record MatchInspectorResponse(
            UUID nodeId, com.app.qa.model.IntentNodeType type, String fullPath, double score, int rank) {}

    public record ActionInspectorResponse(
            UUID actionId,
            RetrievalActionType type,
            Long knowledgeBaseId,
            UUID intentNodeId,
            SystemAction systemAction,
            String reasonCode) {}

    public record ClarificationResponse(
            List<ClarificationOptionResponse> options, boolean allowNoneOfTheAbove) {}

    public record ClarificationOptionResponse(UUID nodeId, String label) {}

    private record ConversationCursor(OffsetDateTime activity, UUID conversationId) {}

    private record TurnExtras(
            InspectorResponse inspector,
            MessageResponse assistantMessage,
            ClarificationResponse clarification) {}
}
