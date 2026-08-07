package com.app.ai.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * api.md §7 的调用详情。
 *
 * <p>{@code routeEvents} 是**真实持久化**的非 HTTP 调度事件，不是详情 API 按 attempt 间隙合成的
 * 说明文字——合成出来的时间线会把"没记录到"和"没发生"混为一谈。
 *
 * <p>{@code capabilityDetails} 是按能力区分的联合结构，四个字段同时只有一个非空。
 * 永远不包含正文、原始错误或完整 Provider request ID。
 */
public record AiDiagnosticCallDetail(
        Call call,
        List<Candidate> candidates,
        List<RouteEvent> routeEvents,
        List<Attempt> attempts,
        CapabilityDetails capabilityDetails) {

    public record Call(
            UUID id,
            String requestId,
            String capability,
            String serviceTier,
            String requiredModelIdentity,
            String policyVersion,
            String configFingerprint,
            String outcome,
            String actualProviderId,
            String actualDeploymentId,
            String actualModelIdentity,
            AiDiagnosticCall.Target lastAttemptTarget,
            String providerRequestIdMasked,
            int attemptCount,
            int retryCount,
            int failoverCount,
            boolean failoverOccurred,
            boolean mixedExecution,
            int contributingDeploymentCount,
            boolean committed,
            String normalizedErrorType,
            String failurePhase,
            String terminationReason,
            Integer httpStatus,
            Long routeDurationMs,
            Long firstUpstreamEventMs,
            Long firstMeaningfulChunkMs,
            Long downstreamCommitMs,
            long totalDurationMs,
            @JsonSerialize(using = ToStringSerializer.class) OffsetDateTime startedAt,
            @JsonSerialize(using = ToStringSerializer.class) OffsetDateTime completedAt) {
    }

    public record Candidate(
            int candidateOrdinal,
            String providerId,
            String deploymentId,
            String modelIdentity,
            String capability,
            String serviceTier,
            String decision,
            Integer plannedOrder,
            String rejectionReason,
            boolean defaultCandidate,
            int priority,
            String breakerStateSnapshot) {
    }

    public record RouteEvent(
            int sequenceNo,
            long occurredOffsetMs,
            String eventType,
            Integer workUnitIndex,
            Integer attemptNo,
            Integer candidateOrder,
            String providerId,
            String deploymentId,
            String reason,
            Long waitDurationMs,
            Long remainingBudgetMs,
            Integer eligibleCandidateCount) {
    }

    /** {@code inputFromInclusive/inputToExclusive} 只用于 Embedding；其他能力固定为 null。 */
    public record Attempt(
            int attemptNo,
            int workUnitIndex,
            Integer inputFromInclusive,
            Integer inputToExclusive,
            int candidateOrder,
            String attemptKind,
            int startedSequenceNo,
            int completedSequenceNo,
            String providerId,
            String deploymentId,
            String modelIdentity,
            String breakerStateBefore,
            String breakerStateAfter,
            String outcome,
            String normalizedErrorType,
            String failurePhase,
            Integer httpStatus,
            String providerRequestIdMasked,
            long startedOffsetMs,
            Long firstUpstreamEventMs,
            Long firstMeaningfulChunkMs,
            Long downstreamCommitMs,
            long durationMs,
            Integer inputTokenCount,
            Integer outputTokenCount,
            Integer streamEventCount) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CapabilityDetails(
            Chat chat, Stream stream, Embedding embedding, Rerank rerank) {

        public record Chat(String finishReason, Integer inputTokenCount, Integer outputTokenCount) {
        }

        public record Stream(
                Long firstUpstreamEventMs,
                Long firstMeaningfulChunkMs,
                Long downstreamCommitMs,
                Integer streamEventCount,
                boolean incomplete) {
        }

        /**
         * {@code batches} 按持久化 attempt 里的 {@code workUnitIndex + inputFrom + inputTo} 分组，
         * 从每组最终成功的贡献 attempt 组装——**不能拿当前 catalog 的 batch limit 回推历史范围**，
         * 那个上限随时会改，改完历史详情就全错了。
         *
         * <p>{@code validation} 只在调用成功时给出；失败调用无法把"哪一项校验没过"归因到具体检查，
         * V6 也没有存这三个标志，因此返回 null 而不是猜一个。
         */
        public record Embedding(
                String modelIdentity,
                Integer inputCount,
                Integer batchCount,
                Integer dimensions,
                boolean mixedExecution,
                List<AiDiagnosticCall.Target> contributingDeployments,
                List<Batch> batches,
                Validation validation) {

            public record Batch(
                    int workUnitIndex,
                    Integer inputFromInclusive,
                    Integer inputToExclusive,
                    AiDiagnosticCall.Target actualExecution,
                    int attemptCount,
                    int retryCount,
                    int failoverCount) {
            }

            public record Validation(
                    boolean countAndOrderValid, boolean dimensionsValid, boolean finiteValues) {
            }
        }

        public record Rerank(
                Integer documentCount,
                Integer topN,
                Boolean rerankApplied,
                String degradationReason) {
        }
    }
}
