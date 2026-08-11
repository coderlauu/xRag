package com.app.ai.web;

import com.app.ai.model.Capability;
import com.app.ai.model.ServiceTier;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * api.md §5 调度总览的响应契约。
 *
 * <p>这里只放能公开的调度事实：不嵌入 {@link com.app.ai.model.ModelProvider}（它带着
 * {@code secretProperty} 与 {@code baseUrl}），也不放正文、完整 Provider request ID 或原始错误。
 *
 * <p>时间字段用 {@link ToStringSerializer} 输出 ISO-8601 文本，这样无论调用方拿到的是
 * Spring 配好的 ObjectMapper 还是一个裸 ObjectMapper，序列化结果都一致。
 */
public record AiDiagnosticsOverview(
        @JsonSerialize(using = ToStringSerializer.class) OffsetDateTime generatedAt,
        String policyVersion,
        String configFingerprint,
        Retention retention,
        Summary summary,
        List<Deployment> deployments,
        List<Breaker> breakers) {

    /** 诊断数据的保留窗口；{@code oldestAvailableAt} 是实际最早记录，不声称一定有完整 7 天。 */
    public record Retention(
            int maxAgeDays,
            int maxLogicalCalls,
            @JsonSerialize(using = ToStringSerializer.class) OffsetDateTime oldestAvailableAt) {
    }

    /**
     * 四个可空计数表示"读不到"，不是 0：进程内计数或诊断库读取失败时必须返回 null，
     * 否则页面会把故障显示成"一切正常"。
     */
    public record Summary(
            int eligibleCandidates,
            int openBreakers,
            Long failovers24h,
            Long failedOrInterrupted24h,
            Long diagnosticsDroppedSinceStart,
            Long diagnosticWriteFailuresSinceStart) {
    }

    /**
     * {@code verifiedCapabilities} 只来自真实验证记录，不拿 adapter 理论能力顶替：它的唯一来源是
     * {@code ai_attempt.outcome = 'SUCCEEDED'} 的诊断记录（能力名取自父 {@code ai_logical_call}），
     * 表示"我们真的把请求打到这个 deployment 的这个能力上并拿回了合法响应"，与 {@code capabilities}
     * 那份"这个部署理论上支持什么"是两件事，两者可以不一致，不一致本身就是要看的信息。
     *
     * <p>空数组是"在现存诊断记录里尚未验证"，不是"不支持"。诊断记录有保留窗口（ADR 0005：
     * 7 天或最近 10,000 次逻辑调用后物理删除），所以长期不调用的能力会自然掉出这个列表。
     */
    public record Deployment(
            String providerId,
            String providerDisplayName,
            String deploymentId,
            String modelIdentity,
            List<Capability> capabilities,
            List<ServiceTier> tiers,
            String dataRegion,
            boolean enabled,
            String configurationStatus,
            List<Capability> verifiedCapabilities) {
    }

    /** 当前 JVM registry 的 breaker 状态；进程重启后回到 CLOSED。 */
    public record Breaker(
            String breakerKey,
            String providerId,
            String deploymentId,
            String modelIdentity,
            Capability capability,
            String state,
            boolean allowsCall,
            boolean halfOpenPermissionInUse,
            @JsonSerialize(using = ToStringSerializer.class) OffsetDateTime lastChangedAt,
            String lastChangeReason,
            Recent recent) {
    }

    /** breaker 滑动窗口内的计数。 */
    public record Recent(int success, int failure, long rateLimited, int slow) {
    }
}
