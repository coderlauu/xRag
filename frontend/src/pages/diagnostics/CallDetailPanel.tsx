import { useEffect, useRef } from "react";
import type { DiagnosticCallDetail } from "../../api/diagnostics";
import { ErrorState } from "../../components/ErrorState";
import { Loading } from "../../components/Loading";
import {
  CAPABILITY_LABEL,
  OUTCOME_LABEL,
  OUTCOME_MARK,
  formatDuration,
  formatTime,
  outcomeNarrative,
  routeSummary,
} from "./labels";

interface Props {
  detail: DiagnosticCallDetail | null;
  loading: boolean;
  error: unknown;
  onClose: () => void;
  onRetry: () => void;
  onCopy: (value: string) => void;
}

/**
 * ui-spec §7 的调用详情。时间线只渲染**真实持久化**的 route event 与 attempt，
 * 不从 attempt 间隙推测 breaker 或预算事件——推测出来的时间线会把"没记录到"画成"没发生"。
 */
export function CallDetailPanel({ detail, loading, error, onClose, onRetry, onCopy }: Props) {
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") {
        onClose();
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [onClose]);

  useEffect(() => {
    closeRef.current?.focus();
  }, [detail?.call.id]);

  return (
    <aside className="trace-detail" aria-label="调用详情">
      <div className="trace-detail-header">
        <h2>调用详情</h2>
        <button type="button" ref={closeRef} onClick={onClose}>
          关闭
        </button>
      </div>

      {loading ? <Loading label="正在加载调用详情…" /> : null}
      {!loading && error ? <ErrorState error={error} onRetry={onRetry} /> : null}
      {!loading && !error && detail ? (
        <div className="trace-detail-body">
          <Header detail={detail} onCopy={onCopy} />
          <Candidates detail={detail} />
          <Timeline detail={detail} />
          <CapabilitySection detail={detail} />
        </div>
      ) : null}
    </aside>
  );
}

function Header({ detail, onCopy }: { detail: DiagnosticCallDetail; onCopy: (v: string) => void }) {
  const call = detail.call;
  const narrative = outcomeNarrative(call);
  return (
    <section>
      <p className={`trace-outcome outcome-${call.outcome.toLowerCase()}`}>
        {OUTCOME_MARK[call.outcome]} {OUTCOME_LABEL[call.outcome]}
      </p>
      {narrative ? <p className="trace-narrative">{narrative}</p> : null}
      <dl className="trace-facts">
        <dt>能力 / 等级</dt>
        <dd>
          {CAPABILITY_LABEL[call.capability]}
          {call.serviceTier ? ` · ${call.serviceTier}` : ""}
        </dd>
        <dt>实际路由</dt>
        <dd>{routeSummary(call)}</dd>
        <dt>开始 / 结束</dt>
        <dd>
          {formatTime(call.startedAt)} → {formatTime(call.completedAt)}
        </dd>
        <dt>总耗时</dt>
        <dd className="numeric">{formatDuration(call.totalDurationMs)}</dd>
        <dt>终止原因</dt>
        <dd>{call.terminationReason}</dd>
        {call.normalizedErrorType ? (
          <>
            <dt>错误类型 / 阶段</dt>
            <dd>
              {call.normalizedErrorType}
              {call.failurePhase ? ` · ${call.failurePhase}` : ""}
            </dd>
          </>
        ) : null}
        <dt>内部请求 ID</dt>
        <dd>
          <code>{call.requestId}</code>{" "}
          <button type="button" className="button-link" onClick={() => onCopy(call.requestId)}>
            复制
          </button>
        </dd>
        {call.providerRequestIdMasked ? (
          <>
            <dt>Provider 请求 ID（已脱敏）</dt>
            <dd>
              <code>{call.providerRequestIdMasked}</code>{" "}
              <button
                type="button"
                className="button-link"
                onClick={() => onCopy(call.providerRequestIdMasked as string)}
              >
                复制
              </button>
            </dd>
          </>
        ) : null}
        <dt>策略版本</dt>
        <dd className="muted">
          {call.policyVersion} · {call.configFingerprint.slice(0, 12)}…
        </dd>
      </dl>
    </section>
  );
}

function Candidates({ detail }: { detail: DiagnosticCallDetail }) {
  return (
    <section>
      <h3>候选与过滤</h3>
      {detail.candidates.length === 0 ? (
        <p className="muted">本次调用没有产生候选记录。</p>
      ) : (
        <table className="data-table">
          <thead>
            <tr>
              <th>#</th>
              <th>Provider / 模型</th>
              <th>决策</th>
              <th>熔断快照</th>
            </tr>
          </thead>
          <tbody>
            {detail.candidates.map((candidate) => (
              <tr key={candidate.candidateOrdinal}>
                <td className="numeric">{candidate.plannedOrder ?? "—"}</td>
                <td>
                  {candidate.providerId} / {candidate.deploymentId}
                </td>
                <td>
                  {candidate.decision === "ELIGIBLE" ? (
                    <span className="pill pill-ok">● 合格</span>
                  ) : (
                    <span className="pill pill-muted">○ 已过滤</span>
                  )}
                  {candidate.rejectionReason ? (
                    <div className="muted">{candidate.rejectionReason}</div>
                  ) : null}
                </td>
                <td>{candidate.breakerStateSnapshot ?? "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

/** route event 与 attempt 按 offset 合并；同毫秒冲突用调用级 sequence 决定先后。 */
function Timeline({ detail }: { detail: DiagnosticCallDetail }) {
  const entries = [
    ...detail.routeEvents.map((event) => ({
      key: `event-${event.sequenceNo}`,
      offset: event.occurredOffsetMs,
      sequence: event.sequenceNo,
      kind: "event" as const,
      title: event.eventType,
      detail: [
        event.providerId ? `${event.providerId} / ${event.deploymentId}` : null,
        event.reason,
        event.waitDurationMs !== null ? `等待 ${event.waitDurationMs} ms` : null,
        event.eligibleCandidateCount !== null ? `合格候选 ${event.eligibleCandidateCount}` : null,
        event.remainingBudgetMs !== null ? `剩余预算 ${event.remainingBudgetMs} ms` : null,
      ]
        .filter(Boolean)
        .join(" · "),
    })),
    ...detail.attempts.map((attempt) => ({
      key: `attempt-${attempt.attemptNo}`,
      offset: attempt.startedOffsetMs,
      sequence: attempt.startedSequenceNo,
      kind: "attempt" as const,
      title: `Attempt ${attempt.attemptNo}（${attempt.attemptKind}）· ${attempt.outcome}`,
      detail: [
        `${attempt.providerId} / ${attempt.deploymentId}`,
        `耗时 ${attempt.durationMs} ms`,
        attempt.normalizedErrorType
          ? `${attempt.normalizedErrorType}${attempt.failurePhase ? ` · ${attempt.failurePhase}` : ""}`
          : null,
        attempt.httpStatus !== null ? `HTTP ${attempt.httpStatus}` : null,
        `breaker ${attempt.breakerStateBefore} → ${attempt.breakerStateAfter ?? "—"}`,
        attempt.inputFromInclusive !== null
          ? `输入 [${attempt.inputFromInclusive}, ${attempt.inputToExclusive})`
          : null,
      ]
        .filter(Boolean)
        .join(" · "),
    })),
  ].sort((a, b) => a.offset - b.offset || a.sequence - b.sequence);

  return (
    <section>
      <h3>路由与 Attempt 时间线</h3>
      {entries.length === 0 ? (
        <p className="muted">本次调用没有持久化的调度事件。</p>
      ) : (
        <ol className="trace-timeline">
          {entries.map((entry) => (
            <li key={entry.key} className={`trace-timeline-${entry.kind}`}>
              <span className="trace-offset numeric">+{entry.offset} ms</span>
              <span className="trace-timeline-title">{entry.title}</span>
              {entry.detail ? <span className="muted">{entry.detail}</span> : null}
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function CapabilitySection({ detail }: { detail: DiagnosticCallDetail }) {
  const { chat, stream, embedding, rerank } = detail.capabilityDetails;

  if (stream) {
    return (
      <section>
        <h3>流式关键时刻</h3>
        <dl className="trace-facts">
          <dt>首个上游事件</dt>
          <dd className="numeric">{formatDuration(stream.firstUpstreamEventMs)}</dd>
          <dt>首个有效块</dt>
          <dd className="numeric">{formatDuration(stream.firstMeaningfulChunkMs)}</dd>
          <dt>下游提交</dt>
          <dd className="numeric">{formatDuration(stream.downstreamCommitMs)}</dd>
          <dt>事件数</dt>
          <dd className="numeric">{stream.streamEventCount ?? "—"}</dd>
        </dl>
        {stream.incomplete ? (
          <p className="trace-warning">生成中断，已显示的内容可能不完整。</p>
        ) : null}
      </section>
    );
  }

  if (embedding) {
    return (
      <section>
        <h3>Embedding 详情</h3>
        <dl className="trace-facts">
          <dt>向量身份</dt>
          <dd>{embedding.modelIdentity ?? "—"}</dd>
          <dt>输入 / 批次 / 维度</dt>
          <dd className="numeric">
            {embedding.inputCount ?? "—"} / {embedding.batchCount ?? "—"} /{" "}
            {embedding.dimensions ?? "—"}
          </dd>
          <dt>贡献 deployment</dt>
          <dd>
            {embedding.contributingDeployments.length === 0
              ? "—"
              : embedding.contributingDeployments
                  .map((t) => `${t.providerId} / ${t.deploymentId}`)
                  .join("，")}
          </dd>
          <dt>结果校验</dt>
          <dd>
            {embedding.validation
              ? "数量与顺序、维度、数值均已校验通过"
              : "本次调用未产生可校验的完整结果"}
          </dd>
        </dl>
        {embedding.batches.length > 0 ? (
          <table className="data-table">
            <thead>
              <tr>
                <th>批次</th>
                <th>输入范围</th>
                <th>实际执行</th>
                <th className="numeric">attempt / 重试 / 切换</th>
              </tr>
            </thead>
            <tbody>
              {embedding.batches.map((batch) => (
                <tr key={batch.workUnitIndex}>
                  <td className="numeric">{batch.workUnitIndex}</td>
                  <td className="numeric">
                    [{batch.inputFromInclusive}, {batch.inputToExclusive})
                  </td>
                  <td>
                    {batch.actualExecution
                      ? `${batch.actualExecution.providerId} / ${batch.actualExecution.deploymentId}`
                      : "未成功"}
                  </td>
                  <td className="numeric">
                    {batch.attemptCount} / {batch.retryCount} / {batch.failoverCount}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
        {embedding.mixedExecution ? (
          <p className="muted">为保护既有向量空间，只在同一向量身份的等价 deployment 之间切换。</p>
        ) : null}
      </section>
    );
  }

  if (rerank) {
    return (
      <section>
        <h3>Rerank 详情</h3>
        <dl className="trace-facts">
          <dt>候选数 / top-N</dt>
          <dd className="numeric">
            {rerank.documentCount ?? "—"} / {rerank.topN ?? "—"}
          </dd>
          <dt>是否执行重排</dt>
          <dd>{rerank.rerankApplied ? "是" : "否"}</dd>
          {rerank.degradationReason ? (
            <>
              <dt>降级原因</dt>
              <dd>{rerank.degradationReason}</dd>
            </>
          ) : null}
        </dl>
        {rerank.rerankApplied === false ? (
          <p className="trace-warning">未执行重排序，结果保持原召回顺序。</p>
        ) : null}
      </section>
    );
  }

  if (chat) {
    return (
      <section>
        <h3>Chat 详情</h3>
        <dl className="trace-facts">
          <dt>结束原因</dt>
          <dd>{chat.finishReason ?? "—"}</dd>
          <dt>输入 / 输出 token</dt>
          <dd className="numeric">
            {chat.inputTokenCount ?? "—"} / {chat.outputTokenCount ?? "—"}
          </dd>
        </dl>
      </section>
    );
  }

  return null;
}
