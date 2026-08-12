package com.app.qa.conversation;

import com.app.qa.model.IntentNodeType;
import com.app.qa.model.MessageRole;
import com.app.qa.model.MessageType;
import com.app.qa.model.RetrievalActionType;
import com.app.qa.model.RewriteMode;
import com.app.qa.model.SubQuestionSource;
import com.app.qa.model.SystemAction;
import com.app.qa.model.TurnStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public final class ConversationPersistenceService {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public ConversationPersistenceService(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public TurnSubmission submitQuestion(UUID conversationId, UUID clientRequestId, String content) {
        if (clientRequestId == null) {
            throw new IllegalArgumentException("clientRequestId must not be null");
        }
        if (content == null || content.isBlank() || content.codePointCount(0, content.length()) > 20_000) {
            throw new IllegalArgumentException("message content must contain 1 to 20000 code points");
        }
        return transactions.execute(status -> submitInTransaction(conversationId, clientRequestId, content));
    }

    public TurnSubmission submitClarificationChoice(
            UUID conversationId,
            UUID clientRequestId,
            UUID sourceTurnId,
            UUID selectedIntentNodeId,
            boolean noneOfTheAbove) {
        if (conversationId == null || clientRequestId == null || sourceTurnId == null) {
            throw new IllegalArgumentException("clarification choice identifiers must not be null");
        }
        if (noneOfTheAbove == (selectedIntentNodeId != null)) {
            throw new IllegalArgumentException(
                    "selectedIntentNodeId and noneOfTheAbove=true are mutually exclusive");
        }
        return transactions.execute(status -> submitClarificationChoiceInTransaction(
                conversationId,
                clientRequestId,
                sourceTurnId,
                selectedIntentNodeId,
                noneOfTheAbove));
    }

    public Optional<ConversationView> findConversation(UUID conversationId) {
        return jdbc.query("""
                select c.id, c.title, c.version, c.last_active_at,
                       (select left(m.content, 120)
                          from qa_message m
                         where m.conversation_id = c.id and m.deleted = false
                         order by m.sequence_no desc
                         limit 1) as message_preview,
                       exists(select 1
                                from qa_turn t
                               where t.conversation_id = c.id and t.deleted = false
                                 and t.status in ('SAVED', 'PROCESSING', 'QUEUED', 'GENERATING')) as active_turn
                  from qa_conversation c
                 where c.id = ? and c.deleted = false
                """, (rs, rowNum) -> conversationView(rs), conversationId)
                .stream()
                .findFirst();
    }

    public List<ConversationView> findConversations(
            OffsetDateTime beforeActivity, UUID beforeConversationId, int limit) {
        if (limit < 1 || limit > 101) {
            throw new IllegalArgumentException("limit must be between 1 and 101");
        }
        if ((beforeActivity == null) != (beforeConversationId == null)) {
            throw new IllegalArgumentException("conversation cursor fields must be supplied together");
        }
        if (beforeActivity == null) {
            return jdbc.query("""
                    select c.id, c.title, c.version, c.last_active_at,
                           (select left(m.content, 120)
                              from qa_message m
                             where m.conversation_id = c.id and m.deleted = false
                             order by m.sequence_no desc
                             limit 1) as message_preview,
                           exists(select 1
                                    from qa_turn t
                                   where t.conversation_id = c.id and t.deleted = false
                                     and t.status in ('SAVED', 'PROCESSING', 'QUEUED', 'GENERATING')) as active_turn
                      from qa_conversation c
                     where c.deleted = false
                     order by c.last_active_at desc, c.id desc
                     limit ?
                    """, (rs, rowNum) -> conversationView(rs), limit);
        }
        return jdbc.query("""
                select c.id, c.title, c.version, c.last_active_at,
                       (select left(m.content, 120)
                          from qa_message m
                         where m.conversation_id = c.id and m.deleted = false
                         order by m.sequence_no desc
                         limit 1) as message_preview,
                       exists(select 1
                                from qa_turn t
                               where t.conversation_id = c.id and t.deleted = false
                                 and t.status in ('SAVED', 'PROCESSING', 'QUEUED', 'GENERATING')) as active_turn
                  from qa_conversation c
                 where c.deleted = false and (c.last_active_at, c.id) < (?, ?)
                 order by c.last_active_at desc, c.id desc
                 limit ?
                """, (rs, rowNum) -> conversationView(rs), beforeActivity, beforeConversationId, limit);
    }

    public List<MessageView> findMessages(UUID conversationId, Long beforeSequence, int limit) {
        if (limit < 1 || limit > 101) {
            throw new IllegalArgumentException("limit must be between 1 and 101");
        }
        long before = beforeSequence == null ? Long.MAX_VALUE : beforeSequence;
        var messages = jdbc.query("""
                select m.id, m.turn_id, m.sequence_no, m.role, m.message_type,
                       m.content, m.created_at
                  from qa_message m
                  join qa_conversation c on c.id = m.conversation_id and c.deleted = false
                  join qa_turn t on t.id = m.turn_id and t.deleted = false
                 where m.conversation_id = ? and m.deleted = false and m.sequence_no < ?
                 order by m.sequence_no desc
                 limit ?
                """, (rs, rowNum) -> new MessageView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("turn_id", UUID.class),
                        rs.getLong("sequence_no"),
                        MessageRole.valueOf(rs.getString("role")),
                        MessageType.valueOf(rs.getString("message_type")),
                        rs.getString("content"),
                        rs.getObject("created_at", OffsetDateTime.class)), conversationId, before, limit);
        Collections.reverse(messages);
        return List.copyOf(messages);
    }

    public Optional<TurnView> findTurn(UUID turnId) {
        return jdbc.query("""
                select t.id, t.conversation_id, t.user_message_id, t.status, t.terminal_reason,
                       t.started_at, t.completed_at, t.degradation_codes::text,
                       t.intent_revision_id, t.rewrite_mode
                  from qa_turn t
                  join qa_conversation c on c.id = t.conversation_id and c.deleted = false
                 where t.id = ? and t.deleted = false
                """, (rs, rowNum) -> new TurnView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_message_id", UUID.class),
                        TurnStatus.valueOf(rs.getString("status")),
                        rs.getString("terminal_reason"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("completed_at", OffsetDateTime.class),
                        parseDegradationJson(rs.getString("degradation_codes")),
                        rs.getObject("intent_revision_id", UUID.class),
                        rs.getString("rewrite_mode") == null
                                ? null
                                : RewriteMode.valueOf(rs.getString("rewrite_mode"))), turnId)
                .stream()
                .findFirst();
    }

    public Optional<TurnView> findLatestStableTurn(UUID conversationId) {
        return jdbc.query("""
                select t.id, t.conversation_id, t.user_message_id, t.status, t.terminal_reason,
                       t.started_at, t.completed_at, t.degradation_codes::text,
                       t.intent_revision_id, t.rewrite_mode
                  from qa_turn t
                  join qa_conversation c on c.id = t.conversation_id and c.deleted = false
                 where t.conversation_id = ? and t.deleted = false
                   and t.status not in ('SAVED', 'PROCESSING', 'QUEUED', 'GENERATING')
                 order by t.created_at desc, t.id desc
                 limit 1
                """, (rs, rowNum) -> new TurnView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("user_message_id", UUID.class),
                        TurnStatus.valueOf(rs.getString("status")),
                        rs.getString("terminal_reason"),
                        rs.getObject("started_at", OffsetDateTime.class),
                        rs.getObject("completed_at", OffsetDateTime.class),
                        parseDegradationJson(rs.getString("degradation_codes")),
                        rs.getObject("intent_revision_id", UUID.class),
                        rs.getString("rewrite_mode") == null
                                ? null
                                : RewriteMode.valueOf(rs.getString("rewrite_mode"))), conversationId)
                .stream()
                .findFirst();
    }

    public boolean markProcessing(UUID turnId) {
        return jdbc.update("""
                update qa_turn
                   set status = 'PROCESSING', started_at = coalesce(started_at, now()), updated_at = now()
                 where id = ? and deleted = false and status = 'SAVED'
                """, turnId) == 1;
    }

    public boolean completeTurn(UUID turnId, TurnStatus terminalStatus, String terminalReason) {
        if (!isTerminal(terminalStatus)) {
            throw new IllegalArgumentException("terminalStatus must be terminal");
        }
        if (terminalReason == null || terminalReason.isBlank() || terminalReason.length() > 64) {
            throw new IllegalArgumentException("terminalReason must contain 1 to 64 characters");
        }
        return jdbc.update("""
                update qa_turn
                   set status = ?, terminal_reason = ?, completed_at = now(), updated_at = now()
                 where id = ? and deleted = false and status = 'PROCESSING'
                """, terminalStatus.name(), terminalReason, turnId) == 1;
    }

    public boolean renameConversation(UUID conversationId, long expectedVersion, String title) {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("title must contain 1 to 200 characters");
        }
        return jdbc.update("""
                update qa_conversation
                   set title = ?, version = version + 1, updated_at = now()
                 where id = ? and version = ? and deleted = false
                """, title.strip(), conversationId, expectedVersion) == 1;
    }

    public boolean softDeleteConversation(UUID conversationId, long expectedVersion) {
        Boolean deleted = transactions.execute(status -> softDeleteInTransaction(conversationId, expectedVersion));
        return Boolean.TRUE.equals(deleted);
    }

    public boolean completeUnderstanding(
            UUID turnId,
            UUID intentRevisionId,
            TurnStatus terminalStatus,
            String terminalReason,
            List<SubQuestionWrite> subQuestions,
            List<ClarificationOptionWrite> clarificationOptions,
            List<ActionWrite> actions) {
        return completeUnderstanding(
                turnId,
                intentRevisionId,
                terminalStatus,
                terminalReason,
                null,
                List.of(),
                subQuestions,
                List.of(),
                clarificationOptions,
                actions);
    }

    public boolean completeUnderstanding(
            UUID turnId,
            UUID intentRevisionId,
            TurnStatus terminalStatus,
            String terminalReason,
            RewriteMode rewriteMode,
            List<String> degradationCodes,
            List<SubQuestionWrite> subQuestions,
            List<IntentMatchWrite> matches,
            List<ClarificationOptionWrite> clarificationOptions,
            List<ActionWrite> actions) {
        String rewrittenQuestion = subQuestions == null || subQuestions.isEmpty()
                ? null
                : subQuestions.get(0).text();
        return completeUnderstanding(
                turnId,
                intentRevisionId,
                terminalStatus,
                terminalReason,
                rewriteMode,
                degradationCodes,
                false,
                0,
                rewrittenQuestion,
                subQuestions,
                matches,
                clarificationOptions,
                actions);
    }

    public boolean completeUnderstanding(
            UUID turnId,
            UUID intentRevisionId,
            TurnStatus terminalStatus,
            String terminalReason,
            RewriteMode rewriteMode,
            List<String> degradationCodes,
            boolean summaryUsed,
            int recentCompleteTurns,
            String rewrittenQuestion,
            List<SubQuestionWrite> subQuestions,
            List<IntentMatchWrite> matches,
            List<ClarificationOptionWrite> clarificationOptions,
            List<ActionWrite> actions) {
        validateUnderstandingResult(
                intentRevisionId, terminalStatus, degradationCodes, subQuestions, matches,
                clarificationOptions, actions);
        if (recentCompleteTurns < 0 || recentCompleteTurns > 8) {
            throw new IllegalArgumentException("recentCompleteTurns must be between 0 and 8");
        }
        if (rewrittenQuestion == null
                || rewrittenQuestion.isBlank()
                || rewrittenQuestion.length() > 20_000) {
            throw new IllegalArgumentException("rewrittenQuestion must contain 1 to 20000 characters");
        }
        Boolean completed = transactions.execute(status -> completeUnderstandingInTransaction(
                turnId,
                intentRevisionId,
                terminalStatus,
                terminalReason,
                rewriteMode,
                degradationCodes,
                summaryUsed,
                recentCompleteTurns,
                rewrittenQuestion,
                subQuestions,
                matches,
                clarificationOptions,
                actions));
        return Boolean.TRUE.equals(completed);
    }

    public boolean rejectUnderstanding(
            UUID turnId,
            TurnStatus terminalStatus,
            String terminalReason,
            RewriteMode rewriteMode,
            List<String> degradationCodes) {
        if (terminalStatus != TurnStatus.REJECTED_TOO_MANY_TOPICS
                && terminalStatus != TurnStatus.FAILED) {
            throw new IllegalArgumentException("rejected understanding must use a rejected or failed status");
        }
        validateDegradationCodes(degradationCodes);
        return jdbc.update("""
                update qa_turn
                   set status = ?, terminal_reason = ?, rewrite_mode = ?,
                       degradation_codes = ?::jsonb, completed_at = now(), updated_at = now()
                 where id = ? and deleted = false and status = 'PROCESSING'
                """, terminalStatus.name(), terminalReason,
                rewriteMode == null ? null : rewriteMode.name(),
                degradationJson(degradationCodes), turnId) == 1;
    }

    public Optional<TurnContext> findTurnContext(UUID turnId) {
        return jdbc.query("""
                select conversation_id, source_turn_id, selected_intent_node_id
                  from qa_turn
                 where id = ? and deleted = false
                """, (rs, rowNum) -> new TurnContext(
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("source_turn_id", UUID.class),
                        rs.getObject("selected_intent_node_id", UUID.class)), turnId)
                .stream()
                .findFirst();
    }

    public Optional<InspectorView> findInspector(UUID turnId) {
        var state = jdbc.query("""
                select rewrite_mode, rewritten_question, summary_used, recent_complete_turns
                  from qa_turn
                 where id = ? and deleted = false
                   and status in ('ACTIONS_READY', 'WAITING_CLARIFICATION')
                   and rewrite_mode is not null and rewritten_question is not null
                """, (rs, rowNum) -> new InspectorState(
                        RewriteMode.valueOf(rs.getString("rewrite_mode")),
                        rs.getString("rewritten_question"),
                        rs.getBoolean("summary_used"),
                        rs.getInt("recent_complete_turns")), turnId)
                .stream()
                .findFirst();
        if (state.isEmpty()) {
            return Optional.empty();
        }
        var questions = jdbc.query("""
                select id, ordinal, text
                  from qa_subquestion
                 where turn_id = ? and deleted = false
                 order by ordinal
                """, (rs, rowNum) -> new InspectorSubQuestion(
                        rs.getObject("id", UUID.class),
                        rs.getInt("ordinal"),
                        rs.getString("text"),
                        List.of(),
                        List.of()), turnId);
        var populated = new ArrayList<InspectorSubQuestion>();
        for (var question : questions) {
            var matches = jdbc.query("""
                    select node_id, node_type, full_path_snapshot, score, rank
                      from qa_intent_match
                     where subquestion_id = ? and deleted = false
                     order by rank
                    """, (rs, rowNum) -> new InspectorMatch(
                            rs.getObject("node_id", UUID.class),
                            IntentNodeType.valueOf(rs.getString("node_type")),
                            rs.getString("full_path_snapshot"),
                            rs.getDouble("score"),
                            rs.getInt("rank")), question.subQuestionId());
            var actions = jdbc.query("""
                    select id, action_type, knowledge_base_id, intent_node_id, system_action, reason_code
                      from qa_retrieval_action
                     where subquestion_id = ? and deleted = false
                     order by ordinal
                    """, (rs, rowNum) -> new InspectorAction(
                            rs.getObject("id", UUID.class),
                            RetrievalActionType.valueOf(rs.getString("action_type")),
                            rs.getObject("knowledge_base_id", Long.class),
                            rs.getObject("intent_node_id", UUID.class),
                            rs.getString("system_action") == null
                                    ? null
                                    : SystemAction.valueOf(rs.getString("system_action")),
                            rs.getString("reason_code")), question.subQuestionId());
            populated.add(new InspectorSubQuestion(
                    question.subQuestionId(), question.ordinal(), question.text(), matches, actions));
        }
        var options = jdbc.query("""
                select node_id, label
                  from qa_clarification_option
                 where turn_id = ? and deleted = false
                 order by ordinal
                """, (rs, rowNum) -> new InspectorOption(
                        rs.getObject("node_id", UUID.class), rs.getString("label")), turnId);
        var assistant = jdbc.query("""
                select id, turn_id, sequence_no, role, message_type, content, created_at
                  from qa_message
                 where turn_id = ? and deleted = false and message_type = 'CLARIFICATION_GUIDANCE'
                 order by sequence_no desc limit 1
                """, (rs, rowNum) -> new MessageView(
                        rs.getObject("id", UUID.class),
                        rs.getObject("turn_id", UUID.class),
                        rs.getLong("sequence_no"),
                        MessageRole.valueOf(rs.getString("role")),
                        MessageType.valueOf(rs.getString("message_type")),
                        rs.getString("content"),
                        rs.getObject("created_at", OffsetDateTime.class)), turnId)
                .stream()
                .findFirst()
                .orElse(null);
        var value = state.get();
        return Optional.of(new InspectorView(
                value.summaryUsed(),
                value.recentCompleteTurns(),
                value.rewriteMode(),
                value.rewrittenQuestion(),
                populated,
                options,
                assistant));
    }

    public int failStaleProcessing(Duration staleAfter) {
        if (staleAfter == null || staleAfter.isZero() || staleAfter.isNegative()) {
            throw new IllegalArgumentException("staleAfter must be positive");
        }
        return jdbc.update("""
                update qa_turn
                   set status = 'FAILED', terminal_reason = 'PROCESS_INTERRUPTED',
                       completed_at = now(), updated_at = now()
                 where deleted = false and status = 'PROCESSING'
                   and started_at < now() - (?::interval)
                """, staleAfter.toMillis() + " milliseconds");
    }

    public Optional<SummaryView> findCurrentSummary(UUID conversationId) {
        return jdbc.query("""
                select s.id, s.conversation_id, s.revision, s.content,
                       s.watermark_message_id, s.watermark_sequence_no,
                       s.covered_turn_count, s.created_at
                  from qa_conversation_summary s
                  join qa_conversation c on c.id = s.conversation_id and c.deleted = false
                 where s.conversation_id = ? and s.deleted = false
                """, (rs, rowNum) -> summaryView(rs), conversationId).stream().findFirst();
    }

    public List<SummaryView> findSummaryHistory(UUID conversationId) {
        return jdbc.query("""
                select id, conversation_id, revision, content,
                       watermark_message_id, watermark_sequence_no,
                       covered_turn_count, created_at
                  from qa_conversation_summary
                 where conversation_id = ?
                 order by revision
                """, (rs, rowNum) -> summaryView(rs), conversationId);
    }

    public Optional<SummaryView> installSummary(
            UUID conversationId,
            long expectedRevision,
            UUID watermarkMessageId,
            long watermarkSequenceNo,
            int coveredTurnCount,
            String content) {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must not be negative");
        }
        if (content == null || content.isBlank() || content.length() > 20_000) {
            throw new IllegalArgumentException("summary content must contain 1 to 20000 characters");
        }
        if (coveredTurnCount < 1) {
            throw new IllegalArgumentException("coveredTurnCount must be positive");
        }
        return transactions.execute(status -> installSummaryInTransaction(
                conversationId,
                expectedRevision,
                watermarkMessageId,
                watermarkSequenceNo,
                coveredTurnCount,
                content.strip()));
    }

    private TurnSubmission submitInTransaction(UUID requestedConversationId, UUID requestId, String content) {
        boolean createdConversation = requestedConversationId == null;
        UUID conversationId = createdConversation ? UUID.randomUUID() : requestedConversationId;
        if (createdConversation) {
            jdbc.update("""
                    insert into qa_conversation (id, title, last_active_at)
                    values (?, ?, now())
                    """, conversationId, firstCodePoints(content.strip(), 40));
        } else if (jdbc.queryForList("""
                    select id from qa_conversation where id = ? and deleted = false for update
                    """, UUID.class, conversationId).isEmpty()) {
            throw new IllegalArgumentException("conversation does not exist");
        }

        var existing = findSubmission(conversationId, requestId);
        if (existing.isPresent()) {
            var replay = existing.get();
            if (!sha256(replay.content()).equals(sha256(content))) {
                throw new IdempotencyKeyReusedException();
            }
            return new TurnSubmission(
                    replay.conversationId(), replay.turnId(), replay.messageId(), replay.status(), false, true);
        }

        Integer active = jdbc.queryForObject("""
                select count(*) from qa_turn
                 where conversation_id = ? and deleted = false
                   and status in ('SAVED','PROCESSING','QUEUED','GENERATING')
                """, Integer.class, conversationId);
        if (active != null && active > 0) {
            throw new ConversationBusyException();
        }

        Long nextSequence = jdbc.queryForObject("""
                select coalesce(max(sequence_no), 0) + 1 from qa_message where conversation_id = ?
                """, Long.class, conversationId);
        UUID turnId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status)
                values (?, ?, ?, ?, 'SAVED')
                """, turnId, conversationId, requestId, messageId);
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                values (?, ?, ?, ?, 'USER', 'USER_QUERY', ?)
                """, messageId, conversationId, turnId, nextSequence, content);
        jdbc.update("update qa_conversation set last_active_at = now(), updated_at = now() where id = ?",
                conversationId);
        return new TurnSubmission(conversationId, turnId, messageId, TurnStatus.SAVED, createdConversation, false);
    }

    private TurnSubmission submitClarificationChoiceInTransaction(
            UUID conversationId,
            UUID requestId,
            UUID sourceTurnId,
            UUID selectedIntentNodeId,
            boolean noneOfTheAbove) {
        if (jdbc.queryForList("""
                    select id from qa_conversation where id = ? and deleted = false for update
                    """, UUID.class, conversationId).isEmpty()) {
            throw new InvalidClarificationChoiceException();
        }

        var existing = findClarificationSubmission(conversationId, requestId);
        if (existing.isPresent()) {
            var replay = existing.get();
            if (!sourceTurnId.equals(replay.sourceTurnId())
                    || !Objects.equals(selectedIntentNodeId, replay.selectedIntentNodeId())
                    || replay.messageType() != MessageType.CLARIFICATION_CHOICE) {
                throw new IdempotencyKeyReusedException();
            }
            return new TurnSubmission(
                    conversationId, replay.turnId(), replay.messageId(), replay.status(), false, true);
        }

        var latestStableTurn = jdbc.query("""
                select id, status
                  from qa_turn
                 where conversation_id = ? and deleted = false
                   and status not in ('SAVED', 'PROCESSING', 'QUEUED', 'GENERATING')
                 order by created_at desc, id desc
                 limit 1
                 for update
                """, (rs, rowNum) -> new StableTurn(
                        rs.getObject("id", UUID.class),
                        TurnStatus.valueOf(rs.getString("status"))), conversationId)
                .stream()
                .findFirst();
        if (latestStableTurn.isEmpty()
                || !latestStableTurn.get().turnId().equals(sourceTurnId)
                || latestStableTurn.get().status() != TurnStatus.WAITING_CLARIFICATION) {
            throw new InvalidClarificationChoiceException();
        }

        String content;
        if (noneOfTheAbove) {
            content = "都不是这些，请补充其他方向。";
        } else {
            var label = jdbc.query("""
                    select label
                      from qa_clarification_option
                     where turn_id = ? and node_id = ? and deleted = false
                    """, (rs, rowNum) -> rs.getString("label"), sourceTurnId, selectedIntentNodeId)
                    .stream()
                    .findFirst();
            if (label.isEmpty()) {
                throw new InvalidClarificationChoiceException();
            }
            content = "我想了解：" + label.get();
        }

        Integer active = jdbc.queryForObject("""
                select count(*) from qa_turn
                 where conversation_id = ? and deleted = false
                   and status in ('SAVED','PROCESSING','QUEUED','GENERATING')
                """, Integer.class, conversationId);
        if (active != null && active > 0) {
            throw new ConversationBusyException();
        }
        Long nextSequence = jdbc.queryForObject("""
                select coalesce(max(sequence_no), 0) + 1 from qa_message where conversation_id = ?
                """, Long.class, conversationId);
        UUID turnId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status,
                     source_turn_id, selected_intent_node_id)
                values (?, ?, ?, ?, 'SAVED', ?, ?)
                """, turnId, conversationId, requestId, messageId, sourceTurnId, selectedIntentNodeId);
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                values (?, ?, ?, ?, 'USER', 'CLARIFICATION_CHOICE', ?)
                """, messageId, conversationId, turnId, nextSequence, content);
        jdbc.update("update qa_conversation set last_active_at = now(), updated_at = now() where id = ?",
                conversationId);
        return new TurnSubmission(conversationId, turnId, messageId, TurnStatus.SAVED, false, false);
    }

    private Optional<StoredSubmission> findSubmission(UUID conversationId, UUID requestId) {
        return jdbc.query("""
                select t.conversation_id, t.id as turn_id, t.user_message_id, t.status, m.content
                  from qa_turn t
                  join qa_message m on m.id = t.user_message_id
                 where t.conversation_id = ? and t.client_request_id = ? and t.deleted = false
                """, (rs, rowNum) -> new StoredSubmission(
                        rs.getObject("conversation_id", UUID.class),
                        rs.getObject("turn_id", UUID.class),
                        rs.getObject("user_message_id", UUID.class),
                        TurnStatus.valueOf(rs.getString("status")),
                        rs.getString("content")), conversationId, requestId).stream().findFirst();
    }

    private Optional<StoredClarificationSubmission> findClarificationSubmission(
            UUID conversationId, UUID requestId) {
        return jdbc.query("""
                select t.id as turn_id, t.user_message_id, t.status, t.source_turn_id,
                       t.selected_intent_node_id, m.message_type
                  from qa_turn t
                  join qa_message m on m.id = t.user_message_id
                 where t.conversation_id = ? and t.client_request_id = ? and t.deleted = false
                """, (rs, rowNum) -> new StoredClarificationSubmission(
                        rs.getObject("turn_id", UUID.class),
                        rs.getObject("user_message_id", UUID.class),
                        TurnStatus.valueOf(rs.getString("status")),
                        rs.getObject("source_turn_id", UUID.class),
                        rs.getObject("selected_intent_node_id", UUID.class),
                        MessageType.valueOf(rs.getString("message_type"))), conversationId, requestId)
                .stream()
                .findFirst();
    }

    private boolean softDeleteInTransaction(UUID conversationId, long expectedVersion) {
        if (jdbc.queryForList("""
                select id from qa_conversation
                 where id = ? and version = ? and deleted = false
                 for update
                """, UUID.class, conversationId, expectedVersion).isEmpty()) {
            return false;
        }
        Integer active = jdbc.queryForObject("""
                select count(*) from qa_turn
                 where conversation_id = ? and deleted = false
                   and status in ('SAVED','PROCESSING','QUEUED','GENERATING')
                """, Integer.class, conversationId);
        if (active != null && active > 0) {
            throw new ConversationBusyException();
        }

        jdbc.update("""
                update qa_intent_match set deleted = true, deleted_at = now()
                 where deleted = false and turn_id in (
                       select id from qa_turn where conversation_id = ?)
                """, conversationId);
        jdbc.update("""
                update qa_clarification_option set deleted = true, deleted_at = now()
                 where deleted = false and turn_id in (
                       select id from qa_turn where conversation_id = ?)
                """, conversationId);
        jdbc.update("""
                update qa_retrieval_action set deleted = true, deleted_at = now(), updated_at = now()
                 where deleted = false and turn_id in (
                       select id from qa_turn where conversation_id = ?)
                """, conversationId);
        jdbc.update("""
                update qa_subquestion set deleted = true, deleted_at = now(), updated_at = now()
                 where deleted = false and turn_id in (
                       select id from qa_turn where conversation_id = ?)
                """, conversationId);
        jdbc.update("""
                update qa_conversation_summary
                   set deleted = true, deleted_at = now(), updated_at = now()
                 where conversation_id = ? and deleted = false
                """, conversationId);
        jdbc.update("""
                update qa_message set deleted = true, deleted_at = now(), updated_at = now()
                 where conversation_id = ? and deleted = false
                """, conversationId);
        jdbc.update("""
                update qa_turn set deleted = true, deleted_at = now(), updated_at = now()
                 where conversation_id = ? and deleted = false
                """, conversationId);
        return jdbc.update("""
                update qa_conversation
                   set deleted = true, deleted_at = now(), updated_at = now(), version = version + 1
                 where id = ? and version = ? and deleted = false
                """, conversationId, expectedVersion) == 1;
    }

    private Optional<SummaryView> installSummaryInTransaction(
            UUID conversationId,
            long expectedRevision,
            UUID watermarkMessageId,
            long watermarkSequenceNo,
            int coveredTurnCount,
            String content) {
        if (jdbc.queryForList("""
                select id from qa_conversation where id = ? and deleted = false for update
                """, UUID.class, conversationId).isEmpty()) {
            return Optional.empty();
        }
        var current = jdbc.query("""
                select id, conversation_id, revision, content,
                       watermark_message_id, watermark_sequence_no,
                       covered_turn_count, created_at
                  from qa_conversation_summary
                 where conversation_id = ? and deleted = false
                """, (rs, rowNum) -> summaryView(rs), conversationId).stream().findFirst();
        long currentRevision = current.map(SummaryView::revision).orElse(0L);
        long currentWatermark = current.map(SummaryView::watermarkSequenceNo).orElse(0L);
        if (currentRevision != expectedRevision || watermarkSequenceNo <= currentWatermark) {
            return Optional.empty();
        }
        Integer matchingMessage = jdbc.queryForObject("""
                select count(*) from qa_message
                 where id = ? and conversation_id = ? and sequence_no = ? and deleted = false
                """, Integer.class, watermarkMessageId, conversationId, watermarkSequenceNo);
        if (matchingMessage == null || matchingMessage != 1) {
            return Optional.empty();
        }

        current.ifPresent(summary -> jdbc.update("""
                update qa_conversation_summary
                   set deleted = true, deleted_at = now(), updated_at = now()
                 where id = ? and deleted = false
                """, summary.summaryId()));
        UUID summaryId = UUID.randomUUID();
        long revision = currentRevision + 1;
        jdbc.update("""
                insert into qa_conversation_summary
                    (id, conversation_id, revision, content, watermark_message_id,
                     watermark_sequence_no, covered_turn_count)
                values (?, ?, ?, ?, ?, ?, ?)
                """, summaryId, conversationId, revision, content, watermarkMessageId,
                watermarkSequenceNo, coveredTurnCount);
        return findCurrentSummary(conversationId);
    }

    private boolean completeUnderstandingInTransaction(
            UUID turnId,
            UUID intentRevisionId,
            TurnStatus terminalStatus,
            String terminalReason,
            RewriteMode rewriteMode,
            List<String> degradationCodes,
            boolean summaryUsed,
            int recentCompleteTurns,
            String rewrittenQuestion,
            List<SubQuestionWrite> subQuestions,
            List<IntentMatchWrite> matches,
            List<ClarificationOptionWrite> clarificationOptions,
            List<ActionWrite> actions) {
        var owningConversations = jdbc.queryForList("""
                select conversation_id from qa_turn
                 where id = ? and deleted = false and status = 'PROCESSING'
                 for update
                """, UUID.class, turnId);
        if (owningConversations.isEmpty()) {
            return false;
        }
        UUID conversationId = owningConversations.get(0);
        for (var subQuestion : subQuestions) {
            jdbc.update("""
                    insert into qa_subquestion (id, turn_id, ordinal, text, source)
                    values (?, ?, ?, ?, ?)
                    """, subQuestion.subQuestionId(), turnId, subQuestion.ordinal(),
                    subQuestion.text(), subQuestion.source().name());
        }
        for (var match : matches) {
            jdbc.update("""
                    insert into qa_intent_match
                        (id, turn_id, subquestion_id, intent_revision_id, node_id, node_type,
                         full_path_snapshot, knowledge_base_id, system_action, score, rank,
                         category_node_id)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, match.matchId(), turnId, match.subQuestionId(), intentRevisionId,
                    match.nodeId(), match.nodeType().name(), match.fullPath(),
                    match.knowledgeBaseId(),
                    match.systemAction() == null ? null : match.systemAction().name(),
                    match.score(), match.rank(), match.categoryNodeId());
        }
        for (var option : clarificationOptions) {
            jdbc.update("""
                    insert into qa_clarification_option
                        (id, turn_id, ordinal, intent_revision_id, node_id, category_node_id, label)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """, option.optionId(), turnId, option.ordinal(), intentRevisionId,
                    option.nodeId(), option.categoryNodeId(), option.label());
        }
        if (terminalStatus == TurnStatus.WAITING_CLARIFICATION) {
            Long nextSequence = jdbc.queryForObject("""
                    select coalesce(max(sequence_no), 0) + 1
                      from qa_message
                     where conversation_id = ?
                    """, Long.class, conversationId);
            String guidance = "请确认你想了解的方向：" + clarificationOptions.stream()
                    .map(ClarificationOptionWrite::label)
                    .reduce((left, right) -> left + "；" + right)
                    .orElseThrow();
            jdbc.update("""
                    insert into qa_message
                        (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                    values (?, ?, ?, ?, 'ASSISTANT', 'CLARIFICATION_GUIDANCE', ?)
                    """, UUID.randomUUID(), conversationId, turnId, nextSequence, guidance);
        }
        for (var action : actions) {
            jdbc.update("""
                    insert into qa_retrieval_action
                        (id, turn_id, subquestion_id, ordinal, action_type, intent_node_id,
                         knowledge_base_id, system_action, reason_code)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, action.actionId(), turnId, action.subQuestionId(), action.ordinal(),
                    action.actionType().name(), action.intentNodeId(), action.knowledgeBaseId(),
                    action.systemAction() == null ? null : action.systemAction().name(),
                    action.reasonCode());
        }
        return jdbc.update("""
                update qa_turn
                   set intent_revision_id = ?, status = ?, terminal_reason = ?, rewrite_mode = ?,
                       degradation_codes = ?::jsonb, summary_used = ?,
                       recent_complete_turns = ?, rewritten_question = ?,
                       completed_at = now(), updated_at = now()
                 where id = ? and deleted = false and status = 'PROCESSING'
                """, intentRevisionId, terminalStatus.name(), terminalReason,
                rewriteMode == null ? null : rewriteMode.name(),
                degradationJson(degradationCodes), summaryUsed, recentCompleteTurns,
                rewrittenQuestion.strip(), turnId) == 1;
    }

    private static void validateUnderstandingResult(
            UUID intentRevisionId,
            TurnStatus terminalStatus,
            List<String> degradationCodes,
            List<SubQuestionWrite> subQuestions,
            List<IntentMatchWrite> matches,
            List<ClarificationOptionWrite> clarificationOptions,
            List<ActionWrite> actions) {
        if (intentRevisionId == null) {
            throw new IllegalArgumentException("intentRevisionId must not be null");
        }
        if (subQuestions == null || subQuestions.isEmpty() || subQuestions.size() > 3) {
            throw new IllegalArgumentException("understanding result requires 1 to 3 subquestions");
        }
        if (matches == null || clarificationOptions == null || actions == null) {
            throw new IllegalArgumentException("understanding result collections must not be null");
        }
        validateDegradationCodes(degradationCodes);
        var subQuestionIds = new HashSet<UUID>();
        for (int ordinal = 0; ordinal < subQuestions.size(); ordinal++) {
            var subQuestion = subQuestions.get(ordinal);
            if (subQuestion == null
                    || subQuestion.subQuestionId() == null
                    || subQuestion.ordinal() != ordinal
                    || subQuestion.text() == null
                    || subQuestion.text().isBlank()
                    || subQuestion.source() == null
                    || !subQuestionIds.add(subQuestion.subQuestionId())) {
                throw new IllegalArgumentException("subquestions must have stable IDs and contiguous ordinals");
            }
        }
        for (var match : matches) {
            if (match == null
                    || match.matchId() == null
                    || !subQuestionIds.contains(match.subQuestionId())
                    || match.nodeId() == null
                    || match.nodeType() == null
                    || match.fullPath() == null
                    || match.fullPath().isBlank()
                    || match.categoryNodeId() == null
                    || !Double.isFinite(match.score())
                    || match.score() < 0
                    || match.score() > 1
                    || match.rank() < 1
                    || match.rank() > 3) {
                throw new IllegalArgumentException("intent matches must be valid and belong to a subquestion");
            }
        }
        if (terminalStatus == TurnStatus.WAITING_CLARIFICATION) {
            if (clarificationOptions.size() < 2 || clarificationOptions.size() > 3 || !actions.isEmpty()) {
                throw new IllegalArgumentException("clarification terminal requires 2 to 3 options and no actions");
            }
            return;
        }
        if (terminalStatus != TurnStatus.ACTIONS_READY || !clarificationOptions.isEmpty() || actions.isEmpty()) {
            throw new IllegalArgumentException("actions terminal requires actions and no clarification options");
        }
        var actionSubQuestions = new HashSet<UUID>();
        for (var action : actions) {
            if (action == null || !subQuestionIds.contains(action.subQuestionId())) {
                throw new IllegalArgumentException("every action must belong to a persisted subquestion");
            }
            actionSubQuestions.add(action.subQuestionId());
        }
        if (!actionSubQuestions.equals(subQuestionIds)) {
            throw new IllegalArgumentException("every subquestion requires at least one action");
        }
    }

    private static void validateDegradationCodes(List<String> degradationCodes) {
        if (degradationCodes == null
                || degradationCodes.stream().anyMatch(code ->
                        code == null || !code.matches("[A-Z0-9_]{1,64}"))) {
            throw new IllegalArgumentException("degradation codes must use stable uppercase identifiers");
        }
    }

    private static String degradationJson(List<String> degradationCodes) {
        return degradationCodes.stream()
                .distinct()
                .map(code -> "\"" + code + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    private static List<String> parseDegradationJson(String raw) {
        if (raw == null || raw.equals("[]")) {
            return List.of();
        }
        String body = raw.substring(1, raw.length() - 1);
        if (body.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split(","))
                .map(String::strip)
                .map(value -> value.substring(1, value.length() - 1))
                .toList();
    }

    private static ConversationView conversationView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ConversationView(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getLong("version"),
                rs.getObject("last_active_at", OffsetDateTime.class),
                rs.getString("message_preview"),
                rs.getBoolean("active_turn"));
    }

    private static SummaryView summaryView(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SummaryView(
                rs.getObject("id", UUID.class),
                rs.getObject("conversation_id", UUID.class),
                rs.getLong("revision"),
                rs.getString("content"),
                rs.getObject("watermark_message_id", UUID.class),
                rs.getLong("watermark_sequence_no"),
                rs.getInt("covered_turn_count"),
                rs.getObject("created_at", OffsetDateTime.class));
    }

    private static boolean isTerminal(TurnStatus status) {
        return status == TurnStatus.ACTIONS_READY
                || status == TurnStatus.WAITING_CLARIFICATION
                || status == TurnStatus.REJECTED_TOO_MANY_TOPICS
                || status == TurnStatus.FAILED;
    }

    private static String firstCodePoints(String value, int max) {
        int end = value.offsetByCodePoints(0, Math.min(value.codePointCount(0, value.length()), max));
        return value.substring(0, end);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record TurnSubmission(
            UUID conversationId,
            UUID turnId,
            UUID messageId,
            TurnStatus status,
            boolean createdConversation,
            boolean replayed) {}

    public record ConversationView(
            UUID conversationId,
            String title,
            long version,
            OffsetDateTime lastActiveAt,
            String messagePreview,
            boolean activeTurn) {}

    public record MessageView(
            UUID messageId,
            UUID turnId,
            long sequenceNo,
            MessageRole role,
            MessageType type,
            String content,
            OffsetDateTime createdAt) {}

    public record TurnView(
            UUID turnId,
            UUID conversationId,
            UUID userMessageId,
            TurnStatus status,
            String terminalReason,
            OffsetDateTime startedAt,
            OffsetDateTime completedAt,
            List<String> degradations,
            UUID intentRevisionId,
            RewriteMode rewriteMode) {

        public TurnView {
            degradations = List.copyOf(degradations);
        }
    }

    public record SummaryView(
            UUID summaryId,
            UUID conversationId,
            long revision,
            String content,
            UUID watermarkMessageId,
            long watermarkSequenceNo,
            int coveredTurnCount,
            OffsetDateTime createdAt) {}

    public record SubQuestionWrite(
            UUID subQuestionId, int ordinal, String text, SubQuestionSource source) {}

    public record ClarificationOptionWrite(
            UUID optionId, int ordinal, UUID nodeId, UUID categoryNodeId, String label) {}

    public record IntentMatchWrite(
            UUID matchId,
            UUID subQuestionId,
            UUID nodeId,
            IntentNodeType nodeType,
            String fullPath,
            Long knowledgeBaseId,
            SystemAction systemAction,
            double score,
            int rank,
            UUID categoryNodeId) {}

    public record ActionWrite(
            UUID actionId,
            UUID subQuestionId,
            int ordinal,
            RetrievalActionType actionType,
            UUID intentNodeId,
            Long knowledgeBaseId,
            SystemAction systemAction,
            String reasonCode) {}

    public record TurnContext(
            UUID conversationId, UUID sourceTurnId, UUID selectedIntentNodeId) {}

    public record InspectorView(
            boolean summaryUsed,
            int recentCompleteTurns,
            RewriteMode rewriteMode,
            String rewrittenQuestion,
            List<InspectorSubQuestion> subQuestions,
            List<InspectorOption> options,
            MessageView assistantMessage) {

        public InspectorView {
            subQuestions = List.copyOf(subQuestions);
            options = List.copyOf(options);
        }
    }

    public record InspectorSubQuestion(
            UUID subQuestionId,
            int ordinal,
            String text,
            List<InspectorMatch> matches,
            List<InspectorAction> actions) {

        public InspectorSubQuestion {
            matches = List.copyOf(matches);
            actions = List.copyOf(actions);
        }
    }

    public record InspectorMatch(
            UUID nodeId, IntentNodeType type, String fullPath, double score, int rank) {}

    public record InspectorAction(
            UUID actionId,
            RetrievalActionType type,
            Long knowledgeBaseId,
            UUID intentNodeId,
            SystemAction systemAction,
            String reasonCode) {}

    public record InspectorOption(UUID nodeId, String label) {}

    private record StoredSubmission(
            UUID conversationId,
            UUID turnId,
            UUID messageId,
            TurnStatus status,
            String content) {}

    private record StoredClarificationSubmission(
            UUID turnId,
            UUID messageId,
            TurnStatus status,
            UUID sourceTurnId,
            UUID selectedIntentNodeId,
            MessageType messageType) {}

    private record StableTurn(UUID turnId, TurnStatus status) {}

    private record InspectorState(
            RewriteMode rewriteMode,
            String rewrittenQuestion,
            boolean summaryUsed,
            int recentCompleteTurns) {}
}
