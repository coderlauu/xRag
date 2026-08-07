import type { BreakerState, CallOutcome, Capability, DiagnosticCall } from "../../api/diagnostics";

/**
 * ui-spec §6.3 的最终结果字典。这些文案是规格的一部分，不是随手写的展示串——
 * `DEGRADED` 必须读起来和普通成功不同，`STREAM_FAILED` 必须提示答案不完整。
 */
export const OUTCOME_LABEL: Record<CallOutcome, string> = {
  SUCCEEDED: "成功",
  DEGRADED: "已降级",
  FAILED: "失败",
  CANCELED: "已取消",
  STREAM_FAILED: "流中断",
};

/** 状态不能只靠颜色表达（ui-spec §12），每个结果同时给一个形状记号。 */
export const OUTCOME_MARK: Record<CallOutcome, string> = {
  SUCCEEDED: "●",
  DEGRADED: "◐",
  FAILED: "✕",
  CANCELED: "○",
  STREAM_FAILED: "▲",
};

export const CAPABILITY_LABEL: Record<Capability, string> = {
  CHAT: "Chat",
  STREAM_CHAT: "流式 Chat",
  EMBEDDING: "Embedding",
  RERANK: "Rerank",
};

export const BREAKER_LABEL: Record<BreakerState, string> = {
  CLOSED: "正常",
  OPEN: "已熔断",
  HALF_OPEN: "恢复探测",
  DISABLED: "已停用",
};

export const BREAKER_MARK: Record<BreakerState, string> = {
  CLOSED: "●",
  OPEN: "✕",
  HALF_OPEN: "◐",
  DISABLED: "○",
};

/** ui-spec §13 的文案基线。避免"智能路由成功""无感切换"这类不可验证或掩盖故障的说法。 */
export function outcomeNarrative(call: DiagnosticCall): string | null {
  if (call.outcome === "SUCCEEDED" && call.failoverCount > 0) {
    return `调用成功；首选模型失败后已切换 ${call.failoverCount} 次`;
  }
  if (call.outcome === "STREAM_FAILED") {
    return "生成中断，已显示的内容可能不完整";
  }
  if (call.outcome === "CANCELED") {
    return "调用已取消；不会影响模型健康状态";
  }
  if (call.outcome === "DEGRADED" && call.capability === "RERANK") {
    return "未执行重排序，结果保持原召回顺序";
  }
  if (call.outcome === "FAILED") {
    return call.attemptCount === 0
      ? "请求或模型配置有误，系统未尝试其他模型"
      : "所有合格模型均未完成调用";
  }
  return null;
}

/**
 * ui-spec §6.2 的"实际路由"列。三种情况严格区分：唯一贡献者、mixed、只有尝试没有贡献者。
 * 把最后一次尝试当成实际路由显示，会把一次全部失败的调用画成成功打到了某个模型。
 */
export function routeSummary(call: DiagnosticCall): string {
  if (call.mixedExecution) {
    return `多个 deployment（${call.contributingDeploymentCount}）`;
  }
  if (call.actualProviderId) {
    return `${call.actualProviderId} / ${call.actualDeploymentId}`;
  }
  if (call.lastAttemptTarget) {
    return `最后尝试：${call.lastAttemptTarget.providerId} / ${call.lastAttemptTarget.deploymentId}`;
  }
  return "—";
}

/** ui-spec §6.2 的"过程"列：最终结果之外必须同时表达过程。 */
export function processSummary(call: DiagnosticCall): string {
  if (call.attemptCount === 0) {
    return call.outcome === "DEGRADED" ? "Noop" : "未产生 attempt";
  }
  const parts = [`${call.attemptCount} attempt`];
  if (call.retryCount > 0) parts.push(`重试 ${call.retryCount} 次`);
  if (call.failoverCount > 0) parts.push(`切换 ${call.failoverCount} 次`);
  return parts.join(" · ");
}

export function formatDuration(ms: number | null): string {
  if (ms === null) return "—";
  return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(2)} s`;
}

export function formatTime(iso: string | null): string {
  if (!iso) return "—";
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? "—" : date.toLocaleString();
}

/** ui-spec §4.3：超过 5 分钟未刷新就提示可能过期，不做秒级跳动。 */
export function freshnessLabel(loadedAt: number | null, now: number): string {
  if (loadedAt === null) return "尚未加载";
  const minutes = Math.floor((now - loadedAt) / 60_000);
  if (minutes >= 5) return "数据可能已过期";
  if (minutes < 1) return "刚刚更新";
  return `${minutes} 分钟前更新`;
}

export function shorten(value: string, head = 8): string {
  return value.length <= head * 2 ? value : `${value.slice(0, head)}…${value.slice(-4)}`;
}
