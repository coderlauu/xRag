package com.app.qa.tool;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@code qa_tool_call} 的唯一入口（数据模型 §15）。 */
@Repository
public class ToolCallRepository {

    public record ToolCallRow(
            UUID id,
            UUID turnId,
            int ordinal,
            String toolName,
            boolean sideEffect,
            ToolCallStatus status,
            String argumentsJson,
            String resultText,
            ToolFailureCode failureCode,
            Integer latencyMs,
            UUID confirmRequestId) {}

    private final JdbcTemplate jdbc;

    public ToolCallRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insert(
            UUID turnId,
            String toolName,
            boolean sideEffect,
            ToolCallStatus status,
            String argumentsJson,
            ToolFailureCode failureCode) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                insert into qa_tool_call
                    (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json, failure_code)
                values (?, ?, (select coalesce(max(ordinal), -1) + 1 from qa_tool_call where turn_id = ?),
                        ?, ?, ?, ?, ?)
                """,
                id,
                turnId,
                turnId,
                toolName,
                sideEffect,
                status.name(),
                argumentsJson,
                failureCode == null ? null : failureCode.name());
        return id;
    }

    public Optional<ToolCallRow> find(UUID turnId, UUID toolCallId) {
        var rows = jdbc.query(
                "select * from qa_tool_call where turn_id = ? and id = ?",
                ToolCallRepository::map,
                turnId,
                toolCallId);
        return rows.stream().findFirst();
    }

    public List<ToolCallRow> findByTurn(UUID turnId) {
        return jdbc.query(
                "select * from qa_tool_call where turn_id = ? order by ordinal", ToolCallRepository::map, turnId);
    }

    /**
     * 确认并占位：{@code PENDING_CONFIRM → RUNNING}，同时写入确认时间戳与幂等键。
     *
     * <p><b>CAS 是"确认前不执行"的第二道闸</b>（第一道是数据库 check）。只有一个请求能把状态从
     * PENDING_CONFIRM 推走，因此并发的两次 CONFIRM 只会有一次真的去调工具。
     *
     * @return 是否由本次调用赢得执行权
     */
    public boolean confirm(UUID toolCallId, UUID confirmRequestId) {
        return jdbc.update(
                        """
                        update qa_tool_call
                           set status = 'RUNNING', confirmed_at = now(),
                               confirm_request_id = ?, updated_at = now()
                         where id = ? and status = 'PENDING_CONFIRM'
                        """,
                        confirmRequestId,
                        toolCallId)
                == 1;
    }

    public boolean cancel(UUID toolCallId, UUID confirmRequestId) {
        return jdbc.update(
                        """
                        update qa_tool_call
                           set status = 'CANCELLED', confirm_request_id = ?, updated_at = now()
                         where id = ? and status = 'PENDING_CONFIRM'
                        """,
                        confirmRequestId,
                        toolCallId)
                == 1;
    }

    /** 终态写入。结果按纯文本存，失败只存归一化码。 */
    public void complete(UUID toolCallId, ToolExecutionResult result, Duration latency) {
        jdbc.update(
                """
                update qa_tool_call
                   set status = ?, result_text = ?, failure_code = ?, latency_ms = ?, updated_at = now()
                 where id = ? and status = 'RUNNING'
                """,
                result.ok() ? ToolCallStatus.SUCCEEDED.name() : ToolCallStatus.FAILED.name(),
                result.text(),
                result.failureCode() == null ? null : result.failureCode().name(),
                (int) latency.toMillis(),
                toolCallId);
    }

    private static ToolCallRow map(ResultSet rs, int rowNum) throws SQLException {
        String failure = rs.getString("failure_code");
        int latency = rs.getInt("latency_ms");
        UUID confirmRequestId = rs.getObject("confirm_request_id", UUID.class);
        return new ToolCallRow(
                rs.getObject("id", UUID.class),
                rs.getObject("turn_id", UUID.class),
                rs.getInt("ordinal"),
                rs.getString("tool_name"),
                rs.getBoolean("side_effect"),
                ToolCallStatus.valueOf(rs.getString("status")),
                rs.getString("arguments_json"),
                rs.getString("result_text"),
                failure == null ? null : ToolFailureCode.valueOf(failure),
                rs.wasNull() ? null : latency,
                confirmRequestId);
    }
}
