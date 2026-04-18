import { useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import type {
  OpsDiagnosticSample,
  OpsRecoveryActionAuditResponse,
  OpsRecoveryActionPreviewResponse,
  OpsRecoveryActionResponse,
  OpsRecoveryActionStatus,
  OpsRecoveryCandidate,
  OpsRecoveryCandidateListQuery,
  OpsRecoveryFactSnapshot,
  OpsRecoveryRecommendationState,
  OpsRecoveryRiskLevel,
  OpsRecoveryTargetRef
} from "@xrag/shared-types";
import { Badge, Button, StatCard, Textarea } from "@xrag/ui";
import {
  createOpsRecoveryAction,
  fetchOpsRecoveryCandidates,
  getOpsRecoveryAction,
  getOpsRecoveryActionAudit,
  previewOpsRecoveryAction
} from "../../../lib/api";
import { formatDateTime, formatRelativeTime } from "../../../lib/document-state";

interface RecoveryWorkflowProps {
  activeSample: OpsDiagnosticSample | null;
}

const CANDIDATE_PAGE_SIZE = 6;
const ACTION_POLL_INTERVAL_MS = 5_000;
const ACTION_STALE_THRESHOLD_MS = 2 * 60 * 1000;

export function RecoveryWorkflow({ activeSample }: RecoveryWorkflowProps) {
  const queryClient = useQueryClient();
  const [selectedCandidateId, setSelectedCandidateId] = useState<string | null>(null);
  const [preview, setPreview] = useState<OpsRecoveryActionPreviewResponse | null>(null);
  const [reason, setReason] = useState("");
  const [confirmChecked, setConfirmChecked] = useState(false);
  const [activeActionId, setActiveActionId] = useState<string | null>(null);
  const [shouldPollAction, setShouldPollAction] = useState(false);

  const candidateQuery = useMemo(() => buildCandidateQuery(activeSample), [activeSample]);

  useEffect(() => {
    setSelectedCandidateId(null);
    setPreview(null);
    setReason("");
    setConfirmChecked(false);
    setActiveActionId(null);
    setShouldPollAction(false);
  }, [activeSample?.sample_id]);

  const candidatesQuery = useQuery({
    queryKey: ["ops", "recovery-candidates", candidateQuery?.source_type, candidateQuery?.source_ref],
    queryFn: () =>
      fetchOpsRecoveryCandidates({
        ...candidateQuery,
        page_size: CANDIDATE_PAGE_SIZE
      }),
    enabled: Boolean(candidateQuery),
    refetchOnWindowFocus: false
  });

  const previewMutation = useMutation({
    mutationFn: (candidate: OpsRecoveryCandidate) =>
      previewOpsRecoveryAction({
        candidate_id: candidate.candidate_id,
        action_type: candidate.action_type,
        target_type: candidate.target_type,
        target_refs: candidate.target_refs
      }),
    onSuccess: (nextPreview) => {
      setPreview(nextPreview);
      setReason("");
      setConfirmChecked(false);
      setActiveActionId(null);
      setShouldPollAction(false);
    }
  });

  const createActionMutation = useMutation({
    mutationFn: (payload: { preview: OpsRecoveryActionPreviewResponse; reason: string }) =>
      createOpsRecoveryAction({
        candidate_id: payload.preview.candidate_id,
        preview_id: payload.preview.preview_id,
        idempotency_key: payload.preview.idempotency_key,
        reason: payload.reason.trim()
      }),
    onSuccess: async (action) => {
      setActiveActionId(action.action_id);
      setShouldPollAction(!isTerminalActionStatus(action.status));
      await queryClient.invalidateQueries({ queryKey: ["ops", "recovery-candidates"] });
    }
  });

  const actionStatusQuery = useQuery({
    queryKey: ["ops", "recovery-action", activeActionId],
    queryFn: () => getOpsRecoveryAction(activeActionId || ""),
    enabled: Boolean(activeActionId),
    refetchOnWindowFocus: false,
    refetchInterval: shouldPollAction ? ACTION_POLL_INTERVAL_MS : false
  });

  const actionAuditQuery = useQuery({
    queryKey: ["ops", "recovery-action-audit", activeActionId],
    queryFn: () => getOpsRecoveryActionAudit(activeActionId || ""),
    enabled: Boolean(activeActionId),
    refetchOnWindowFocus: false,
    refetchInterval: shouldPollAction ? ACTION_POLL_INTERVAL_MS : false
  });

  useEffect(() => {
    const status = actionStatusQuery.data?.status ?? actionAuditQuery.data?.action.status;
    if (status && isTerminalActionStatus(status)) {
      setShouldPollAction(false);
    }
  }, [actionAuditQuery.data?.action.status, actionStatusQuery.data?.status]);

  useEffect(() => {
    const action = actionStatusQuery.data ?? actionAuditQuery.data?.action;

    if (!shouldPollAction || !action) {
      return;
    }

    const lastUpdatedAt = Date.parse(action.updated_at || action.created_at);

    if (!Number.isNaN(lastUpdatedAt) && Date.now() - lastUpdatedAt > ACTION_STALE_THRESHOLD_MS) {
      setShouldPollAction(false);
    }
  }, [actionAuditQuery.data?.action, actionStatusQuery.data, shouldPollAction]);

  const candidates = candidatesQuery.data?.items ?? [];
  const selectedCandidate = candidates.find((candidate) => candidate.candidate_id === selectedCandidateId) ?? null;
  const activeAction = actionStatusQuery.data ?? actionAuditQuery.data?.action ?? null;
  const activeAudit = actionAuditQuery.data ?? null;
  const activeActionIsStale = Boolean(
    shouldPollAction &&
      activeAction &&
      Date.now() - Date.parse(activeAction.updated_at || activeAction.created_at) > ACTION_STALE_THRESHOLD_MS
  );
  const isReadOnlyFollowUp = preview?.action_type === "answer_diagnostic_rerun";
  const canSubmitAction =
    Boolean(preview) &&
    reason.trim().length > 0 &&
    confirmChecked &&
    (!preview?.blocked_reason || isReadOnlyFollowUp);

  async function refreshLinkedFacts() {
    await queryClient.invalidateQueries({ queryKey: ["ops", "recovery-candidates"] });
    await queryClient.invalidateQueries({ queryKey: ["ops", "document-replay"] });
    await queryClient.invalidateQueries({ queryKey: ["ops", "answer-replay"] });
    await queryClient.invalidateQueries({ queryKey: ["ops", "deployment-compare"] });
    await queryClient.invalidateQueries({ queryKey: ["ops", "rollback-plan"] });
  }

  function handlePreview(candidate: OpsRecoveryCandidate) {
    setSelectedCandidateId(candidate.candidate_id);
    setPreview(null);
    setReason("");
    setConfirmChecked(false);
    setActiveActionId(null);
    setShouldPollAction(false);
    previewMutation.reset();
    previewMutation.mutate(candidate);
  }

  function handleSubmitAction() {
    if (!preview || !canSubmitAction || createActionMutation.isPending) {
      return;
    }

    createActionMutation.mutate({
      preview,
      reason
    });
  }

  return (
    <PanelFrame title="Recovery workflow">
      {!activeSample ? (
        <EmptyState title="等待 replay 上下文" body="先从左侧样本列表打开 answer replay 或 document replay，再查看 recovery candidates。" />
      ) : (
        <div className="grid gap-4">
          <div className="grid gap-1">
            <div className="flex flex-wrap items-center justify-between gap-3">
              <strong className="text-sm text-slate-950">Recovery candidates</strong>
              <Badge variant="info">{activeSample.sample_kind === "answer_session" ? "Answer sample" : "Document sample"}</Badge>
            </div>
            <p className="m-0 text-sm leading-6 text-slate-600">
              候选动作只来自当前 replay 事实，不在前端自行推断根因。
            </p>
          </div>

          <CandidateList
            candidates={candidates}
            error={candidatesQuery.isError ? toErrorMessage(candidatesQuery.error) : null}
            isLoading={candidatesQuery.isLoading || candidatesQuery.isFetching}
            onPreview={handlePreview}
            selectedCandidateId={selectedCandidateId}
          />

          <PreviewPanel
            canSubmitAction={canSubmitAction}
            confirmChecked={confirmChecked}
            createActionError={createActionMutation.isError ? toErrorMessage(createActionMutation.error) : null}
            createActionPending={createActionMutation.isPending}
            isReadOnlyFollowUp={isReadOnlyFollowUp}
            onConfirmChange={setConfirmChecked}
            onReasonChange={setReason}
            onSubmit={handleSubmitAction}
            preview={preview}
            previewError={previewMutation.isError ? toErrorMessage(previewMutation.error) : null}
            previewLoading={previewMutation.isPending}
            reason={reason}
            selectedCandidate={selectedCandidate}
          />

          <ActionPanel
            action={activeAction}
            actionAudit={activeAudit}
            actionError={actionStatusQuery.isError ? toErrorMessage(actionStatusQuery.error) : null}
            actionLoading={Boolean(activeActionId) && (actionStatusQuery.isLoading || actionAuditQuery.isLoading)}
            actionStale={activeActionIsStale}
            auditError={actionAuditQuery.isError ? toErrorMessage(actionAuditQuery.error) : null}
            onRefresh={async () => {
              await Promise.all([actionStatusQuery.refetch(), actionAuditQuery.refetch(), refreshLinkedFacts()]);
            }}
            polling={shouldPollAction}
          />
        </div>
      )}
    </PanelFrame>
  );
}

function CandidateList({
  candidates,
  error,
  isLoading,
  onPreview,
  selectedCandidateId
}: {
  candidates: OpsRecoveryCandidate[];
  error: string | null;
  isLoading: boolean;
  onPreview: (candidate: OpsRecoveryCandidate) => void;
  selectedCandidateId: string | null;
}) {
  if (error) {
    return <EmptyState title="候选读取失败" body={error} tone="error" />;
  }

  if (isLoading) {
    return <EmptyState title="加载中" body="正在读取当前 replay 对应的 recovery candidates。" />;
  }

  if (candidates.length === 0) {
    return <EmptyState title="暂无候选" body="当前 replay 没有可解释的 recovery candidates，页面保持空集合而不伪造动作建议。" />;
  }

  return (
    <div className="grid gap-3">
      {candidates.map((candidate) => {
        const satisfiedCount = candidate.preconditions.filter((item) => item.satisfied).length;
        const totalCount = candidate.preconditions.length;
        const targetSummary = candidate.target_refs.map(formatTargetRef).join(" / ");

        return (
          <article
            className={[
              "grid gap-3 rounded-[22px] border bg-white/85 px-4 py-4 text-sm leading-6 shadow-sm",
              selectedCandidateId === candidate.candidate_id ? "border-sky-300 ring-2 ring-sky-100" : "border-slate-200"
            ].join(" ")}
            key={candidate.candidate_id}
          >
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div className="grid gap-1">
                <strong className="text-slate-950">{candidate.title}</strong>
                <span className="font-mono text-xs text-slate-500">{candidate.candidate_id}</span>
              </div>
              <div className="flex flex-wrap gap-2">
                <RiskBadge level={candidate.risk_level} />
                <RecommendationBadge state={candidate.recommendation_state} />
              </div>
            </div>

            <p className="m-0 text-slate-700">{candidate.summary}</p>

            <div className="grid gap-2 rounded-2xl border border-slate-100 bg-slate-50/80 px-3 py-3 text-xs leading-5 text-slate-600">
              <span>Target: {targetSummary}</span>
              <span>Source ref: {candidate.source_ref}</span>
              <span>Preview ref: {candidate.preview_ref.path}</span>
              <span>
                Preconditions: {satisfiedCount}/{totalCount} satisfied
              </span>
              {candidate.blocked_reason ? <span>Blocked reason: {candidate.blocked_reason}</span> : null}
            </div>

            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex flex-wrap gap-2">
                {candidate.preconditions.slice(0, 2).map((item) => (
                  <Badge key={item.code} variant={item.satisfied ? "success" : "warning"}>
                    {item.label}
                  </Badge>
                ))}
              </div>

              <Button onClick={() => onPreview(candidate)} size="sm" type="button" variant="outline">
                预览动作
              </Button>
            </div>
          </article>
        );
      })}
    </div>
  );
}

function PreviewPanel({
  canSubmitAction,
  confirmChecked,
  createActionError,
  createActionPending,
  isReadOnlyFollowUp,
  onConfirmChange,
  onReasonChange,
  onSubmit,
  preview,
  previewError,
  previewLoading,
  reason,
  selectedCandidate
}: {
  canSubmitAction: boolean;
  confirmChecked: boolean;
  createActionError: string | null;
  createActionPending: boolean;
  isReadOnlyFollowUp: boolean;
  onConfirmChange: (checked: boolean) => void;
  onReasonChange: (reason: string) => void;
  onSubmit: () => void;
  preview: OpsRecoveryActionPreviewResponse | null;
  previewError: string | null;
  previewLoading: boolean;
  reason: string;
  selectedCandidate: OpsRecoveryCandidate | null;
}) {
  if (!selectedCandidate && !previewLoading) {
    return (
      <SubPanel title="Preview / 确认">
        <EmptyState title="等待 candidate" body="先选择一个 candidate 并触发 preview，确认区才会显示 target scope、blast radius 和 reason 输入。" />
      </SubPanel>
    );
  }

  if (previewError) {
    return (
      <SubPanel title="Preview / 确认">
        <EmptyState title="Preview 失败" body={previewError} tone="error" />
      </SubPanel>
    );
  }

  if (previewLoading) {
    return (
      <SubPanel title="Preview / 确认">
        <EmptyState title="加载中" body="正在生成 preview / dry-run 结果。" />
      </SubPanel>
    );
  }

  if (!preview || !selectedCandidate) {
    return (
      <SubPanel title="Preview / 确认">
        <EmptyState title="缺少 preview" body="请重新选择 candidate 生成 preview。" />
      </SubPanel>
    );
  }

  const blockedByPreview = Boolean(preview.blocked_reason) && !isReadOnlyFollowUp;

  return (
    <SubPanel title="Preview / 确认">
      <div className="grid gap-4">
        <div className="grid gap-3 rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <strong className="text-slate-950">{selectedCandidate.title}</strong>
            <div className="flex flex-wrap gap-2">
              <RiskBadge level={preview.risk_level} />
              <RecommendationBadge state={preview.recommendation_state} />
            </div>
          </div>
          <p className="m-0 text-sm leading-6 text-slate-700">{selectedCandidate.summary}</p>
          <div className="grid gap-1 text-xs leading-5 text-slate-600">
            <span>Blast radius: {preview.estimated_blast_radius}</span>
            <span>Preview id: {preview.preview_id}</span>
            <span>Idempotency key: {preview.idempotency_key}</span>
            <span>
              Expires: {formatDateTime(preview.expires_at)} · {formatRelativeTime(preview.expires_at)}
            </span>
          </div>
        </div>

        <div className="grid gap-3 md:grid-cols-2">
          <StatCard label="Target 数量" value={String(preview.target_refs.length)} hint={preview.target_refs.map(formatTargetRef).join(" / ")} />
          <StatCard
            label="前置条件"
            value={`${preview.preconditions.filter((item) => item.satisfied).length}/${preview.preconditions.length}`}
            hint={blockedByPreview ? "存在未满足前置条件" : "当前 preview 已完成前置条件检查"}
            tone={blockedByPreview ? "warning" : "default"}
          />
        </div>

        {blockedByPreview ? (
          <Callout tone="warning" title="当前 preview 处于 blocked">
            {preview.blocked_reason}
          </Callout>
        ) : isReadOnlyFollowUp ? (
          <Callout tone="info" title="只读 follow-up">
            `answer_diagnostic_rerun` 在 Phase 3B 只会生成 blocked/manual-follow-up 审计记录，不会替换用户可见 answer。
          </Callout>
        ) : (
          <Callout tone="default" title="执行风险说明">
            当前动作不会自动 rollback。请先核对目标范围、blast radius 和现有失败事实，再做显式确认。
          </Callout>
        )}

        <FactSnapshotSection snapshot={preview.source_facts} title="Source facts" />
        <FactSnapshotSection snapshot={preview.before_facts} title="Before facts" />

        <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3">
          <strong className="text-sm text-slate-950">Preview preconditions</strong>
          <div className="grid gap-2">
            {preview.preconditions.map((item) => (
              <article className="rounded-xl border border-slate-100 bg-slate-50/80 px-3 py-2 text-xs leading-5 text-slate-700" key={item.code}>
                <div className="flex flex-wrap items-center gap-2">
                  <Badge variant={item.satisfied ? "success" : "warning"}>
                    {item.satisfied ? "已满足" : "未满足"}
                  </Badge>
                  <strong className="text-slate-950">{item.label}</strong>
                </div>
                {item.detail ? <p className="m-0 mt-1">{item.detail}</p> : null}
              </article>
            ))}
          </div>
        </div>

        <div className="grid gap-3 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3">
          <label className="grid gap-2 text-sm font-medium text-slate-800">
            执行原因
            <Textarea
              onChange={(event) => onReasonChange(event.target.value)}
              placeholder={
                isReadOnlyFollowUp
                  ? "写明为什么要记录这次只读 follow-up，以及人工下一步要看什么。"
                  : "写明为什么此时执行 rerun / reindex，以及你核对了哪些事实。"
              }
              value={reason}
            />
          </label>

          <label className="flex items-start gap-3 rounded-xl border border-slate-100 bg-slate-50/80 px-3 py-3 text-sm leading-6 text-slate-700">
            <input checked={confirmChecked} onChange={(event) => onConfirmChange(event.target.checked)} type="checkbox" />
            <span>
              {isReadOnlyFollowUp
                ? "我已确认本次只会记录人工 follow-up，不会自动改写 answer、citation 或 freshness 事实。"
                : "我已确认目标范围、blast radius 与不可自动 rollback 风险，并准备由服务端进入 action status 轮询。"}
            </span>
          </label>

          {createActionError ? <Callout tone="error" title="创建 action 失败">{createActionError}</Callout> : null}

          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="m-0 text-xs leading-5 text-slate-500">
              没有 preview 结果或没有显式确认时，不提供执行提交。
            </p>
            <Button disabled={!canSubmitAction || createActionPending} onClick={onSubmit} type="button">
              {createActionPending
                ? "正在提交"
                : isReadOnlyFollowUp
                  ? "记录人工跟进"
                  : "确认并执行"}
            </Button>
          </div>
        </div>
      </div>
    </SubPanel>
  );
}

function ActionPanel({
  action,
  actionAudit,
  actionError,
  actionLoading,
  actionStale,
  auditError,
  onRefresh,
  polling
}: {
  action: OpsRecoveryActionResponse | null;
  actionAudit: OpsRecoveryActionAuditResponse | null;
  actionError: string | null;
  actionLoading: boolean;
  actionStale: boolean;
  auditError: string | null;
  onRefresh: () => Promise<void>;
  polling: boolean;
}) {
  if (!action && !actionLoading) {
    return (
      <SubPanel title="Action status / Audit">
        <EmptyState title="等待 action" body="执行确认后，这里会进入 action status 和 audit 详情，而不是停留在确认区。" />
      </SubPanel>
    );
  }

  if (actionError) {
    return (
      <SubPanel title="Action status / Audit">
        <EmptyState title="Action 状态读取失败" body={actionError} tone="error" />
      </SubPanel>
    );
  }

  if (actionLoading) {
    return (
      <SubPanel title="Action status / Audit">
        <EmptyState title="加载中" body="正在读取 action status 与 audit。" />
      </SubPanel>
    );
  }

  if (!action) {
    return (
      <SubPanel title="Action status / Audit">
        <EmptyState title="暂无 action" body="当前还没有可展示的 action 事实。" />
      </SubPanel>
    );
  }

  return (
    <SubPanel title="Action status / Audit">
      <div className="grid gap-4">
        <div className="grid gap-3 rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <div className="grid gap-1">
              <strong className="text-slate-950">{action.action_type}</strong>
              <span className="font-mono text-xs text-slate-500">{action.action_id}</span>
            </div>
            <ActionStatusBadge status={action.status} />
          </div>

          <div className="grid gap-1 text-xs leading-5 text-slate-600">
            <span>Created: {formatDateTime(action.created_at)}</span>
            <span>Updated: {formatDateTime(action.updated_at)}</span>
            <span>Target: {action.target_refs.map(formatTargetRef).join(" / ")}</span>
            <span>Queue refs: {action.queue_job_refs.length > 0 ? action.queue_job_refs.map((item) => item.path).join(" / ") : "无"}</span>
          </div>

          {actionStale ? (
            <Callout tone="warning" title="轮询 fallback 提示">
              服务端尚未收口到 terminal status。页面已停止无意义轮询；请刷新相关事实，检查 queue job refs 和对应 replay，而不是在前端伪造终态。
            </Callout>
          ) : polling ? (
            <Callout tone="info" title="Action 仍在进行中">
              页面会基于服务端 action status 继续轮询，直到进入 terminal state。
            </Callout>
          ) : null}

          {(action.diagnosis_code || action.error_message) ? (
            <Callout tone="warning" title="错误 / 诊断">
              {action.diagnosis_code ? `diagnosis_code: ${action.diagnosis_code}` : action.error_message || "无"}
              {action.error_message ? ` · ${action.error_message}` : ""}
            </Callout>
          ) : null}

          <div className="flex flex-wrap gap-2">
            <Button onClick={() => void onRefresh()} size="sm" type="button" variant="outline">
              刷新 action 与关联事实
            </Button>
          </div>
        </div>

        {auditError ? <EmptyState title="Audit 读取失败" body={auditError} tone="error" /> : null}

        {actionAudit ? (
          <div className="grid gap-4">
            <div className="grid gap-3 md:grid-cols-2">
              <StatCard label="Actor" value={actionAudit.actor} hint={formatDateTime(actionAudit.generated_at)} />
              <StatCard label="Timeline" value={String(actionAudit.status_timeline.length)} hint="服务端状态时间线条目" />
            </div>

            <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
              <strong className="text-slate-950">Reason</strong>
              <p className="m-0">{actionAudit.reason}</p>
            </div>

            <FactSnapshotSection snapshot={actionAudit.source_facts} title="Audit source facts" />
            <FactSnapshotSection snapshot={actionAudit.before_facts} title="Audit before facts" />
            {actionAudit.after_facts ? <FactSnapshotSection snapshot={actionAudit.after_facts} title="Audit after facts" /> : null}

            <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3">
              <strong className="text-sm text-slate-950">Status timeline</strong>
              <div className="grid gap-2">
                {actionAudit.status_timeline.map((item) => (
                  <article className="rounded-xl border border-slate-100 bg-slate-50/80 px-3 py-2 text-xs leading-5 text-slate-700" key={`${item.status}-${item.at}`}>
                    <div className="flex flex-wrap items-center gap-2">
                      <ActionStatusBadge status={item.status} />
                      <strong className="text-slate-950">{formatDateTime(item.at)}</strong>
                    </div>
                    <p className="m-0 mt-1">{item.summary}</p>
                  </article>
                ))}
              </div>
            </div>

            <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3">
              <strong className="text-sm text-slate-950">Manual follow-up</strong>
              <ul className="m-0 grid gap-2 pl-5 text-sm leading-6 text-slate-700">
                {actionAudit.manual_follow_up.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          </div>
        ) : null}
      </div>
    </SubPanel>
  );
}

function FactSnapshotSection({ snapshot, title }: { snapshot: OpsRecoveryFactSnapshot; title: string }) {
  return (
    <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <strong className="text-sm text-slate-950">{title}</strong>
        <span className="text-xs leading-5 text-slate-500">
          {formatDateTime(snapshot.captured_at)} · {snapshot.target_refs.map(formatTargetRef).join(" / ")}
        </span>
      </div>
      <div className="grid gap-2 md:grid-cols-2">
        {Object.entries(snapshot.facts).map(([key, value]) => (
          <article className="rounded-xl border border-slate-100 bg-slate-50/80 px-3 py-2 text-xs leading-5 text-slate-700" key={key}>
            <strong className="block text-slate-950">{key}</strong>
            <span>{formatFactValue(value)}</span>
          </article>
        ))}
      </div>
    </div>
  );
}

function PanelFrame({ children, title }: { children: ReactNode; title: string }) {
  return (
    <section className="grid gap-3 rounded-[22px] border border-white/70 bg-white/78 p-4 shadow-sm">
      <h3 className="m-0 text-base font-semibold tracking-[-0.03em] text-slate-950">{title}</h3>
      {children}
    </section>
  );
}

function SubPanel({ children, title }: { children: ReactNode; title: string }) {
  return (
    <div className="grid gap-3 rounded-2xl border border-slate-200 bg-white/72 px-4 py-4">
      <h4 className="m-0 text-sm font-semibold tracking-[-0.03em] text-slate-950">{title}</h4>
      {children}
    </div>
  );
}

function EmptyState({
  body,
  title,
  tone = "default"
}: {
  body: string;
  title: string;
  tone?: "default" | "error";
}) {
  return (
    <div
      className={[
        "rounded-2xl border px-4 py-5 text-sm leading-6",
        tone === "error" ? "border-rose-200 bg-rose-50 text-rose-700" : "border-dashed border-slate-200 bg-white/65 text-slate-600"
      ].join(" ")}
    >
      <strong className={tone === "error" ? "block text-rose-900" : "block text-slate-950"}>{title}</strong>
      <span>{body}</span>
    </div>
  );
}

function Callout({
  children,
  title,
  tone
}: {
  children: ReactNode;
  title: string;
  tone: "default" | "info" | "warning" | "error";
}) {
  const toneClasses =
    tone === "error"
      ? "border-rose-200 bg-rose-50 text-rose-700"
      : tone === "warning"
        ? "border-amber-200 bg-amber-50 text-amber-900"
        : tone === "info"
          ? "border-sky-200 bg-sky-50 text-sky-800"
          : "border-slate-200 bg-slate-50 text-slate-700";

  return (
    <div className={`rounded-2xl border px-4 py-3 text-sm leading-6 ${toneClasses}`}>
      <strong className="block">{title}</strong>
      <span>{children}</span>
    </div>
  );
}

function RiskBadge({ level }: { level: OpsRecoveryRiskLevel }) {
  if (level === "high") {
    return <Badge variant="warning">高风险</Badge>;
  }

  if (level === "medium") {
    return <Badge variant="info">中风险</Badge>;
  }

  return <Badge variant="success">低风险</Badge>;
}

function RecommendationBadge({ state }: { state: OpsRecoveryRecommendationState }) {
  switch (state) {
    case "recommended":
      return <Badge variant="warning">推荐执行</Badge>;
    case "available":
      return <Badge variant="info">可人工执行</Badge>;
    case "blocked":
      return <Badge className="border-rose-200 bg-rose-100 text-rose-800">已阻塞</Badge>;
    case "not_applicable":
      return <Badge variant="default">继续观察</Badge>;
  }
}

function ActionStatusBadge({ status }: { status: OpsRecoveryActionStatus }) {
  switch (status) {
    case "succeeded":
      return <Badge variant="success">已成功</Badge>;
    case "failed":
      return <Badge variant="warning">已失败</Badge>;
    case "blocked":
      return <Badge className="border-rose-200 bg-rose-100 text-rose-800">已阻塞</Badge>;
    case "running":
      return <Badge variant="info">执行中</Badge>;
    case "cancelled":
      return <Badge variant="default">已取消</Badge>;
    case "queued":
      return <Badge variant="default">排队中</Badge>;
  }
}

function buildCandidateQuery(activeSample: OpsDiagnosticSample | null): OpsRecoveryCandidateListQuery | null {
  if (!activeSample) {
    return null;
  }

  if (activeSample.sample_kind === "answer_session") {
    return {
      source_type: "answer_session_replay",
      source_ref: activeSample.source_id
    };
  }

  return {
    source_type: "document_replay",
    source_ref: activeSample.source_id
  };
}

function formatTargetRef(targetRef: OpsRecoveryTargetRef) {
  return `${targetRef.type}:${targetRef.id}`;
}

function formatFactValue(value: unknown) {
  if (value === null || value === undefined) {
    return "null";
  }

  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return String(value);
  }

  return JSON.stringify(value);
}

function isTerminalActionStatus(status: OpsRecoveryActionStatus) {
  return status === "succeeded" || status === "failed" || status === "cancelled" || status === "blocked";
}

function toErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "unknown error";
}
