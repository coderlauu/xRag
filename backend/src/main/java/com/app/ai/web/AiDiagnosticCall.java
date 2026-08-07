package com.app.ai.web;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * api.md §6 的列表项。
 *
 * <p>列表刻意不带候选与完整 attempt——每页 20 条各自重复一个大对象，前端还用不上。
 *
 * <p>{@code lastAttemptTarget} 只是最后一次真实 outbound attempt 的 target 摘要：它**不是**成功
 * 贡献者，不参与 actual/mixed/contributing 语义，没有 attempt 时为 null。把它当成"实际路由"显示，
 * 就会把一次全部失败的调用画成成功打到了某个模型。
 */
public record AiDiagnosticCall(
        UUID id,
        String requestId,
        String capability,
        String serviceTier,
        String outcome,
        String actualProviderId,
        String actualDeploymentId,
        String actualModelIdentity,
        Target lastAttemptTarget,
        int attemptCount,
        int retryCount,
        int failoverCount,
        boolean failoverOccurred,
        boolean mixedExecution,
        int contributingDeploymentCount,
        boolean committed,
        long totalDurationMs,
        /** 只有流式能力非空；ui-spec §6.2 的"首个有效块"列直接用它，前端不再二次请求详情。 */
        Long firstMeaningfulChunkMs,
        @JsonSerialize(using = ToStringSerializer.class) OffsetDateTime startedAt,
        String normalizedErrorType) {

    public record Target(String providerId, String deploymentId, String modelIdentity) {
    }
}
