package com.app.qa.memory;

import com.app.ai.model.ChatClient;
import com.app.ai.model.SystemMessage;
import com.app.ai.model.UserMessage;
import com.app.qa.config.QaUnderstandingProperties;
import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.conversation.TurnStageSink;
import com.app.qa.model.MessageRole;
import com.app.qa.model.MessageType;
import com.app.qa.model.QaChatCommandFactory;
import com.app.qa.model.StageOutcome;
import com.app.qa.model.TurnStage;
import com.app.qa.model.TurnStatus;
import com.app.qa.prompt.PromptTemplateCatalog;
import com.app.qa.prompt.QaPrompt;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public final class ConversationMemoryService {

    private final JdbcTemplate jdbc;
    private final ConversationPersistenceService conversations;
    private final QaUnderstandingProperties properties;
    private final PromptTemplateCatalog prompts;
    private final ChatClient chat;
    private final TurnStageSink stages;
    private final HistoryLoader historyLoader;

    @Autowired
    public ConversationMemoryService(
            JdbcTemplate jdbc,
            ConversationPersistenceService conversations,
            QaUnderstandingProperties properties,
            PromptTemplateCatalog prompts,
            ChatClient chat,
            TurnStageSink stages) {
        this(jdbc, conversations, properties, prompts, chat, stages, null);
    }

    ConversationMemoryService(
            JdbcTemplate jdbc,
            ConversationPersistenceService conversations,
            QaUnderstandingProperties properties,
            PromptTemplateCatalog prompts,
            ChatClient chat,
            TurnStageSink stages,
            HistoryLoader historyLoader) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.properties = properties;
        this.prompts = prompts;
        this.chat = chat;
        this.stages = stages;
        this.historyLoader = historyLoader == null ? this::queryEligibleHistory : historyLoader;
    }

    public MemorySnapshot prepare(UUID currentTurnId) {
        CurrentTurn current = requireCurrentTurn(currentTurnId);
        var degradations = new ArrayList<String>();
        var summary = conversations.findCurrentSummary(current.conversationId()).orElse(null);
        long watermark = summary == null ? 0 : summary.watermarkSequenceNo();

        OffsetDateTime historyStarted = OffsetDateTime.now();
        List<HistoryTurn> eligible;
        try {
            eligible = historyLoader.load(
                    current.conversationId(), watermark, current.message().sequenceNo());
            recordStage(
                    currentTurnId,
                    TurnStage.HISTORY,
                    StageOutcome.SUCCEEDED,
                    null,
                    null,
                    historyStarted,
                    OffsetDateTime.now());
        } catch (DataAccessException exception) {
            degradations.add("HISTORY_UNAVAILABLE");
            eligible = List.of();
            recordStage(
                    currentTurnId,
                    TurnStage.HISTORY,
                    StageOutcome.DEGRADED,
                    "HISTORY_UNAVAILABLE",
                    null,
                    historyStarted,
                    OffsetDateTime.now());
        }

        if (eligible.size() >= properties.getSummaryStartTurns()) {
            int compressCount = eligible.size() - properties.getHistoryKeepTurns();
            List<HistoryTurn> compressible = List.copyOf(eligible.subList(0, compressCount));
            OffsetDateTime summaryStarted = OffsetDateTime.now();
            UUID logicalCallId = null;
            try {
                var result = chat.complete(QaChatCommandFactory.balanced(List.of(
                        new SystemMessage(prompts.render(QaPrompt.SUMMARY_SYSTEM, Map.of())),
                        new UserMessage(prompts.render(QaPrompt.SUMMARY_USER, Map.of(
                                "previousSummary", summary == null ? "（无）" : summary.content(),
                                "history", formatHistory(compressible)))))));
                logicalCallId = result.execution().logicalCallId();
                String content = result.message().text();
                if (content == null || content.isBlank() || content.length() > 20_000) {
                    throw new IllegalStateException("summary output must contain 1 to 20000 characters");
                }
                MemoryMessage watermarkMessage = lastMessage(compressible.get(compressible.size() - 1));
                long expectedRevision = summary == null ? 0 : summary.revision();
                int coveredTurns = (summary == null ? 0 : summary.coveredTurnCount()) + compressible.size();
                var installed = conversations.installSummary(
                        current.conversationId(),
                        expectedRevision,
                        watermarkMessage.messageId(),
                        watermarkMessage.sequenceNo(),
                        coveredTurns,
                        content.strip());
                if (installed.isPresent()) {
                    summary = installed.get();
                    eligible = List.copyOf(eligible.subList(compressCount, eligible.size()));
                    recordStage(
                            currentTurnId,
                            TurnStage.SUMMARY,
                            StageOutcome.SUCCEEDED,
                            null,
                            logicalCallId,
                            summaryStarted,
                            OffsetDateTime.now());
                } else {
                    degradations.add("SUMMARY_CONFLICT");
                    summary = conversations.findCurrentSummary(current.conversationId()).orElse(summary);
                    eligible = recentAfterCurrentWatermark(current, summary);
                    recordStage(
                            currentTurnId,
                            TurnStage.SUMMARY,
                            StageOutcome.DEGRADED,
                            "SUMMARY_CONFLICT",
                            logicalCallId,
                            summaryStarted,
                            OffsetDateTime.now());
                }
            } catch (RuntimeException exception) {
                degradations.add("SUMMARY_UNAVAILABLE");
                if (exception instanceof com.app.ai.model.ModelCallException modelFailure) {
                    logicalCallId = modelFailure.execution().logicalCallId();
                }
                recordStage(
                        currentTurnId,
                        TurnStage.SUMMARY,
                        StageOutcome.DEGRADED,
                        "SUMMARY_UNAVAILABLE",
                        logicalCallId,
                        summaryStarted,
                        OffsetDateTime.now());
                eligible = recent(eligible);
            }
        } else {
            recordStage(
                    currentTurnId,
                    TurnStage.SUMMARY,
                    StageOutcome.SKIPPED,
                    "BELOW_SUMMARY_THRESHOLD",
                    null,
                    OffsetDateTime.now(),
                    OffsetDateTime.now());
            eligible = recent(eligible);
        }

        return new MemorySnapshot(
                summary == null ? null : summary.content(),
                summary != null,
                eligible,
                current.message(),
                List.copyOf(degradations));
    }

    private List<HistoryTurn> recentAfterCurrentWatermark(
            CurrentTurn current, ConversationPersistenceService.SummaryView summary) {
        long watermark = summary == null ? 0 : summary.watermarkSequenceNo();
        return recent(historyLoader.load(
                current.conversationId(), watermark, current.message().sequenceNo()));
    }

    private List<HistoryTurn> recent(List<HistoryTurn> turns) {
        int from = Math.max(0, turns.size() - properties.getHistoryKeepTurns());
        return List.copyOf(turns.subList(from, turns.size()));
    }

    private CurrentTurn requireCurrentTurn(UUID turnId) {
        return jdbc.query("""
                select t.id as turn_id, t.conversation_id, m.id as message_id,
                       m.sequence_no, m.role, m.message_type, m.content, m.created_at
                  from qa_turn t
                  join qa_conversation c on c.id = t.conversation_id and c.deleted = false
                  join qa_message m on m.id = t.user_message_id and m.deleted = false
                 where t.id = ? and t.deleted = false
                """, (rs, rowNum) -> new CurrentTurn(
                        rs.getObject("turn_id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        new MemoryMessage(
                                rs.getObject("message_id", UUID.class),
                                rs.getLong("sequence_no"),
                                MessageRole.valueOf(rs.getString("role")),
                                MessageType.valueOf(rs.getString("message_type")),
                                rs.getString("content"),
                                rs.getObject("created_at", OffsetDateTime.class))), turnId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("current turn does not exist"));
    }

    private List<HistoryTurn> queryEligibleHistory(
            UUID conversationId, long afterSequence, long beforeSequence) {
        return jdbc.query("""
                select t.id as turn_id, t.status,
                       m.id as message_id, m.sequence_no, m.role, m.message_type,
                       m.content, m.created_at
                  from qa_turn t
                  join qa_message um on um.id = t.user_message_id and um.deleted = false
                  join qa_message m on m.turn_id = t.id and m.deleted = false
                 where t.conversation_id = ? and t.deleted = false
                   and um.sequence_no > ? and um.sequence_no < ?
                   and (
                       t.status in ('ACTIONS_READY', 'REJECTED_TOO_MANY_TOPICS')
                       or (t.status = 'WAITING_CLARIFICATION' and exists (
                           select 1 from qa_turn resolution
                            where resolution.source_turn_id = t.id and resolution.deleted = false))
                   )
                 order by um.sequence_no, t.id, m.sequence_no
                """, rs -> {
                    var turns = new LinkedHashMap<UUID, MutableHistoryTurn>();
                    while (rs.next()) {
                        UUID turnId = rs.getObject("turn_id", UUID.class);
                        var turn = turns.get(turnId);
                        if (turn == null) {
                            turn = new MutableHistoryTurn(
                                    turnId, TurnStatus.valueOf(rs.getString("status")));
                            turns.put(turnId, turn);
                        }
                        turn.messages.add(new MemoryMessage(
                                rs.getObject("message_id", UUID.class),
                                rs.getLong("sequence_no"),
                                MessageRole.valueOf(rs.getString("role")),
                                MessageType.valueOf(rs.getString("message_type")),
                                rs.getString("content"),
                                rs.getObject("created_at", OffsetDateTime.class)));
                    }
                    return turns.values().stream()
                            .map(turn -> new HistoryTurn(
                                    turn.turnId, turn.status, List.copyOf(turn.messages)))
                            .toList();
                }, conversationId, afterSequence, beforeSequence);
    }

    private void recordStage(
            UUID turnId,
            TurnStage stage,
            StageOutcome outcome,
            String reasonCode,
            UUID logicalCallId,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt) {
        stages.recordBestEffort(new TurnStageSink.StageEvent(
                turnId,
                stage,
                0,
                outcome,
                reasonCode,
                logicalCallId,
                null,
                startedAt,
                completedAt));
    }

    private static MemoryMessage lastMessage(HistoryTurn turn) {
        return turn.messages().get(turn.messages().size() - 1);
    }

    private static String formatHistory(List<HistoryTurn> turns) {
        var formatted = new StringBuilder();
        for (var turn : turns) {
            formatted.append("<turn status=\"").append(turn.status()).append("\">\n");
            for (var message : turn.messages()) {
                formatted.append(message.role())
                        .append('/')
                        .append(message.type())
                        .append(": ")
                        .append(message.content())
                        .append('\n');
            }
            formatted.append("</turn>\n");
        }
        return formatted.toString();
    }

    public record MemorySnapshot(
            String summary,
            boolean summaryUsed,
            List<HistoryTurn> recentTurns,
            MemoryMessage currentMessage,
            List<String> degradations) {

        public MemorySnapshot {
            recentTurns = List.copyOf(recentTurns);
            degradations = List.copyOf(degradations);
        }
    }

    public record HistoryTurn(UUID turnId, TurnStatus status, List<MemoryMessage> messages) {

        public HistoryTurn {
            messages = List.copyOf(messages);
        }
    }

    public record MemoryMessage(
            UUID messageId,
            long sequenceNo,
            MessageRole role,
            MessageType type,
            String content,
            OffsetDateTime createdAt) {}

    private record CurrentTurn(UUID turnId, UUID conversationId, MemoryMessage message) {}

    private static final class MutableHistoryTurn {

        private final UUID turnId;
        private final TurnStatus status;
        private final List<MemoryMessage> messages = new ArrayList<>();

        private MutableHistoryTurn(UUID turnId, TurnStatus status) {
            this.turnId = turnId;
            this.status = status;
        }
    }

    @FunctionalInterface
    interface HistoryLoader {

        List<HistoryTurn> load(UUID conversationId, long afterSequence, long beforeSequence);
    }
}
