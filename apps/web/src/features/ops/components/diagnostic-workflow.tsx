import { startTransition, useState } from "react";
import type { ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import type {
  OpsAnswerSessionReplayResponse,
  OpsDeploymentCompareResponse,
  OpsDiagnosticOrigin,
  OpsDiagnosticSample,
  OpsDiagnosticSampleKind,
  OpsDocumentReplayResponse,
  OpsIncidentCluster,
  OpsRegressionClass,
  OpsReplayFreshnessFlag,
  OpsTrendWindow
} from "@xrag/shared-types";
import { Badge, Button, SectionCard, StatCard } from "@xrag/ui";
import { formatLatencyMs, formatUsd } from "../../../lib/answer-state";
import { formatDateTime, formatRelativeTime } from "../../../lib/document-state";
import {
  fetchOpsAnswerSessionReplay,
  fetchOpsDeploymentCompare,
  fetchOpsDiagnosticSamples,
  fetchOpsDocumentReplay
} from "../../../lib/api";

type SampleKindFilter = OpsDiagnosticSampleKind | "all";

const PAGE_SIZE = 8;
const WINDOW_OPTIONS: Array<{ value: OpsTrendWindow; label: string }> = [
  { value: "24h", label: "24 小时" },
  { value: "7d", label: "7 天" },
  { value: "30d", label: "30 天" }
];
const ORIGIN_OPTIONS: Array<{ value: OpsDiagnosticOrigin; label: string; hint: string }> = [
  { value: "trend", label: "Trend samples", hint: "从 runtime / evaluation 风险派生样本。" },
  { value: "incident_cluster", label: "Incident drill-down", hint: "按 cluster_key 进入事故样本。" },
  { value: "release_compare", label: "Release compare", hint: "围绕 deployment_record_id 定位回归。" }
];
const SAMPLE_KIND_OPTIONS: Array<{ value: SampleKindFilter; label: string }> = [
  { value: "all", label: "全部样本" },
  { value: "answer_session", label: "Answer session" },
  { value: "document_pipeline", label: "Document pipeline" }
];

interface DiagnosticWorkflowProps {
  clusters: OpsIncidentCluster[];
  onWindowChange: (window: OpsTrendWindow) => void;
  window: OpsTrendWindow;
}

export function DiagnosticWorkflow({ clusters, onWindowChange, window }: DiagnosticWorkflowProps) {
  const [origin, setOrigin] = useState<OpsDiagnosticOrigin>("trend");
  const [sampleKind, setSampleKind] = useState<SampleKindFilter>("all");
  const [page, setPage] = useState(1);
  const [clusterDraft, setClusterDraft] = useState(clusters[0]?.cluster_key ?? "");
  const [clusterKey, setClusterKey] = useState(clusters[0]?.cluster_key ?? "");
  const [deploymentDraft, setDeploymentDraft] = useState("");
  const [deploymentRecordId, setDeploymentRecordId] = useState("");
  const [compareDeploymentRecordId, setCompareDeploymentRecordId] = useState("");
  const [activeSample, setActiveSample] = useState<OpsDiagnosticSample | null>(null);

  const sampleQueryEnabled =
    origin === "trend" ||
    (origin === "incident_cluster" && clusterKey.trim().length > 0) ||
    (origin === "release_compare" && deploymentRecordId.trim().length > 0);
  const queryClusterKey = origin === "incident_cluster" ? clusterKey.trim() : undefined;
  const queryDeploymentRecordId = origin === "release_compare" ? deploymentRecordId.trim() : undefined;
  const querySampleKind = sampleKind === "all" ? undefined : sampleKind;

  const sampleQuery = useQuery({
    queryKey: ["ops", "diagnostic-samples", origin, sampleKind, window, queryClusterKey, queryDeploymentRecordId, page],
    queryFn: () =>
      fetchOpsDiagnosticSamples({
        origin,
        window,
        page,
        page_size: PAGE_SIZE,
        sample_kind: querySampleKind,
        cluster_key: queryClusterKey,
        deployment_record_id: queryDeploymentRecordId
      }),
    enabled: sampleQueryEnabled,
    refetchOnWindowFocus: false
  });

  const answerReplayQuery = useQuery({
    queryKey: ["ops", "answer-replay", activeSample?.source_id],
    queryFn: () => {
      if (!activeSample || activeSample.sample_kind !== "answer_session") {
        throw new Error("missing answer session sample");
      }

      return fetchOpsAnswerSessionReplay(activeSample.source_id);
    },
    enabled: activeSample?.sample_kind === "answer_session",
    refetchOnWindowFocus: false
  });

  const documentReplayQuery = useQuery({
    queryKey: ["ops", "document-replay", activeSample?.source_id],
    queryFn: () => {
      if (!activeSample || activeSample.sample_kind !== "document_pipeline") {
        throw new Error("missing document pipeline sample");
      }

      return fetchOpsDocumentReplay(activeSample.source_id);
    },
    enabled: activeSample?.sample_kind === "document_pipeline",
    refetchOnWindowFocus: false
  });

  const activeCompareDeploymentRecordId =
    compareDeploymentRecordId.trim() || (origin === "release_compare" ? deploymentRecordId.trim() : "");
  const compareQuery = useQuery({
    queryKey: ["ops", "deployment-compare", activeCompareDeploymentRecordId, window, sampleKind],
    queryFn: () =>
      fetchOpsDeploymentCompare({
        deployment_record_id: activeCompareDeploymentRecordId,
        window,
        sample_kind: querySampleKind
      }),
    enabled: activeCompareDeploymentRecordId.length > 0,
    refetchOnWindowFocus: false
  });

  function resetDrillDown() {
    setPage(1);
    setActiveSample(null);
  }

  function changeOrigin(nextOrigin: OpsDiagnosticOrigin) {
    startTransition(() => {
      setOrigin(nextOrigin);
      resetDrillDown();
    });
  }

  function changeWindow(nextWindow: OpsTrendWindow) {
    startTransition(() => {
      onWindowChange(nextWindow);
      resetDrillDown();
    });
  }

  function changeSampleKind(nextKind: SampleKindFilter) {
    startTransition(() => {
      setSampleKind(nextKind);
      resetDrillDown();
    });
  }

  function applyCluster(nextClusterKey = clusterDraft) {
    startTransition(() => {
      setOrigin("incident_cluster");
      setClusterDraft(nextClusterKey);
      setClusterKey(nextClusterKey.trim());
      resetDrillDown();
    });
  }

  function applyDeploymentRecord(nextDeploymentRecordId = deploymentDraft) {
    startTransition(() => {
      setOrigin("release_compare");
      setDeploymentDraft(nextDeploymentRecordId);
      setDeploymentRecordId(nextDeploymentRecordId.trim());
      setCompareDeploymentRecordId(nextDeploymentRecordId.trim());
      resetDrillDown();
    });
  }

  function openReplay(sample: OpsDiagnosticSample) {
    startTransition(() => {
      setActiveSample(sample);
    });
  }

  function openCompare(sample: OpsDiagnosticSample) {
    if (!sample.related_deployment_record_id) {
      return;
    }

    startTransition(() => {
      setCompareDeploymentRecordId(sample.related_deployment_record_id || "");
    });
  }

  const samples = sampleQuery.data?.items ?? [];
  const total = sampleQuery.data?.total ?? 0;
  const maxPage = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div id="ops-diagnostics">
      <SectionCard
        className="border-sky-100 bg-[linear-gradient(135deg,rgba(240,249,255,0.95),rgba(255,255,255,0.94)_44%,rgba(236,253,245,0.86))]"
        title="Diagnostics Workflow"
        description="从治理聚合进入样本列表、answer/document replay 与 deployment compare。所有结论只读取 API facts，不在 Web 侧推断。"
      >
        <div className="grid gap-5">
          <div className="grid gap-3 rounded-[22px] border border-white/70 bg-white/75 p-4">
            <div className="flex flex-wrap gap-2">
              {ORIGIN_OPTIONS.map((option) => (
                <Button
                  key={option.value}
                  onClick={() => changeOrigin(option.value)}
                  size="sm"
                  type="button"
                  variant={origin === option.value ? "default" : "outline"}
                >
                  {option.label}
                </Button>
              ))}
            </div>
            <p className="m-0 text-sm leading-6 text-slate-600">
              {ORIGIN_OPTIONS.find((option) => option.value === origin)?.hint}
            </p>

            <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(220px,0.45fr)_minmax(220px,0.45fr)]">
              <label className="grid gap-1 text-sm font-medium text-slate-700">
                Cluster key
                <div className="flex gap-2">
                  <input
                    className="min-h-10 min-w-0 flex-1 rounded-xl border border-slate-200 bg-white/85 px-3 text-sm text-slate-950 outline-none focus:border-sky-400 focus:ring-2 focus:ring-sky-100"
                    onChange={(event) => setClusterDraft(event.target.value)}
                    placeholder="parse:high:open"
                    value={clusterDraft}
                  />
                  <Button disabled={clusterDraft.trim().length === 0} onClick={() => applyCluster()} type="button" variant="secondary">
                    应用
                  </Button>
                </div>
              </label>

              <label className="grid gap-1 text-sm font-medium text-slate-700">
                Deployment record id
                <div className="flex gap-2">
                  <input
                    className="min-h-10 min-w-0 flex-1 rounded-xl border border-slate-200 bg-white/85 px-3 text-sm text-slate-950 outline-none focus:border-sky-400 focus:ring-2 focus:ring-sky-100"
                    onChange={(event) => setDeploymentDraft(event.target.value)}
                    placeholder="deployment_records.id"
                    value={deploymentDraft}
                  />
                  <Button
                    disabled={deploymentDraft.trim().length === 0}
                    onClick={() => applyDeploymentRecord()}
                    type="button"
                    variant="secondary"
                  >
                    Compare
                  </Button>
                </div>
              </label>

              <label className="grid gap-1 text-sm font-medium text-slate-700">
                Sample kind
                <select
                  className="min-h-10 rounded-xl border border-slate-200 bg-white/85 px-3 text-sm text-slate-950 outline-none focus:border-sky-400 focus:ring-2 focus:ring-sky-100"
                  onChange={(event) => changeSampleKind(event.target.value as SampleKindFilter)}
                  value={sampleKind}
                >
                  {SAMPLE_KIND_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
            </div>

            <div className="flex flex-wrap items-center justify-between gap-3">
              <div className="flex flex-wrap gap-2">
                {WINDOW_OPTIONS.map((option) => (
                  <Button
                    aria-label={`Diagnostics window ${option.label}`}
                    key={option.value}
                    onClick={() => changeWindow(option.value)}
                    size="sm"
                    type="button"
                    variant={window === option.value ? "default" : "outline"}
                  >
                    {option.label}
                  </Button>
                ))}
              </div>
              <QuickClusterButtons clusters={clusters} onPick={applyCluster} />
            </div>
          </div>

          <div className="grid gap-5 xl:grid-cols-[minmax(0,0.95fr)_minmax(360px,1.05fr)]">
            <div className="grid gap-4">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="grid gap-1">
                  <h3 className="m-0 text-base font-semibold tracking-[-0.03em] text-slate-950">Diagnostic samples</h3>
                  <p className="m-0 text-xs leading-6 text-slate-500">
                    {sampleQuery.data
                      ? `${formatCount(sampleQuery.data.total)} 个样本 · ${formatDateTime(sampleQuery.data.generated_at)}`
                      : sampleQueryEnabled
                        ? "等待样本读取"
                        : "请选择必要上下文后读取样本"}
                  </p>
                </div>
                <Badge variant="info">{originLabel(origin)}</Badge>
              </div>

              <SampleList
                activeSampleId={activeSample?.sample_id ?? null}
                enabled={sampleQueryEnabled}
                error={sampleQuery.isError ? toErrorMessage(sampleQuery.error) : null}
                isLoading={sampleQuery.isLoading || sampleQuery.isFetching}
                onOpenCompare={openCompare}
                onOpenReplay={openReplay}
                samples={samples}
              />

              {sampleQuery.data ? (
                <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white/70 px-4 py-3">
                  <p className="m-0 text-sm leading-6 text-slate-600">
                    第 {sampleQuery.data.page} 页 / 共 {maxPage} 页
                  </p>
                  <div className="flex gap-2">
                    <Button
                      disabled={page <= 1 || sampleQuery.isFetching}
                      onClick={() => setPage((current) => Math.max(1, current - 1))}
                      size="sm"
                      type="button"
                      variant="outline"
                    >
                      上一页
                    </Button>
                    <Button
                      disabled={page >= maxPage || sampleQuery.isFetching}
                      onClick={() => setPage((current) => current + 1)}
                      size="sm"
                      type="button"
                      variant="outline"
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              ) : null}
            </div>

            <div className="grid gap-4">
              <ReplayPanel
                activeSample={activeSample}
                answerReplay={answerReplayQuery.data ?? null}
                answerReplayError={answerReplayQuery.isError ? toErrorMessage(answerReplayQuery.error) : null}
                answerReplayLoading={answerReplayQuery.isLoading || answerReplayQuery.isFetching}
                documentReplay={documentReplayQuery.data ?? null}
                documentReplayError={documentReplayQuery.isError ? toErrorMessage(documentReplayQuery.error) : null}
                documentReplayLoading={documentReplayQuery.isLoading || documentReplayQuery.isFetching}
              />

              <DeploymentComparePanel
                activeDeploymentRecordId={activeCompareDeploymentRecordId}
                compare={compareQuery.data ?? null}
                error={compareQuery.isError ? toErrorMessage(compareQuery.error) : null}
                isLoading={compareQuery.isLoading || compareQuery.isFetching}
                onOpenReplay={openReplay}
              />
            </div>
          </div>
        </div>
      </SectionCard>
    </div>
  );
}

function QuickClusterButtons({ clusters, onPick }: { clusters: OpsIncidentCluster[]; onPick: (clusterKey: string) => void }) {
  const visibleClusters = clusters.filter((cluster) => cluster.status !== "resolved").slice(0, 3);

  if (visibleClusters.length === 0) {
    return (
      <p className="m-0 text-xs leading-6 text-slate-500">
        暂无 open incident cluster；可直接查看 trend samples。
      </p>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-2">
      <span className="text-xs uppercase tracking-[0.18em] text-slate-500">Quick drill-down</span>
      {visibleClusters.map((cluster) => (
        <Button key={cluster.cluster_key} onClick={() => onPick(cluster.cluster_key)} size="sm" type="button" variant="outline">
          {cluster.cluster_key}
        </Button>
      ))}
    </div>
  );
}

function SampleList({
  activeSampleId,
  enabled,
  error,
  isLoading,
  onOpenCompare,
  onOpenReplay,
  samples
}: {
  activeSampleId: string | null;
  enabled: boolean;
  error: string | null;
  isLoading: boolean;
  onOpenCompare: (sample: OpsDiagnosticSample) => void;
  onOpenReplay: (sample: OpsDiagnosticSample) => void;
  samples: OpsDiagnosticSample[];
}) {
  if (!enabled) {
    return (
      <EmptyState
        title="缺少诊断上下文"
        body="Incident drill-down 需要 cluster_key；release compare 需要 deployment_record_id。"
      />
    );
  }

  if (error) {
    return <EmptyState body={error} tone="error" title="样本读取失败" />;
  }

  if (isLoading) {
    return <EmptyState body="正在读取 diagnostic samples。" title="加载中" />;
  }

  if (samples.length === 0) {
    return <EmptyState body="当前窗口没有可回放样本，页面保持空集合而不伪造诊断事实。" title="暂无样本" />;
  }

  return (
    <div className="grid gap-3">
      {samples.map((sample) => (
        <SampleCard
          isActive={activeSampleId === sample.sample_id}
          key={sample.sample_id}
          onOpenCompare={onOpenCompare}
          onOpenReplay={onOpenReplay}
          sample={sample}
        />
      ))}
    </div>
  );
}

function SampleCard({
  isActive,
  onOpenCompare,
  onOpenReplay,
  sample
}: {
  isActive: boolean;
  onOpenCompare: (sample: OpsDiagnosticSample) => void;
  onOpenReplay: (sample: OpsDiagnosticSample) => void;
  sample: OpsDiagnosticSample;
}) {
  return (
    <article
      className={[
        "grid gap-3 rounded-[22px] border bg-white/85 px-4 py-4 text-sm leading-6 shadow-sm",
        isActive ? "border-sky-300 ring-2 ring-sky-100" : "border-slate-200"
      ].join(" ")}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="grid gap-1">
          <strong className="text-slate-950">{sample.title}</strong>
          <span className="font-mono text-xs text-slate-500">{sample.sample_id}</span>
        </div>
        <div className="flex flex-wrap gap-2">
          <SeverityBadge severity={sample.severity} />
          <Badge variant="info">{sampleKindLabel(sample.sample_kind)}</Badge>
        </div>
      </div>
      <p className="m-0 text-slate-700">{sample.summary}</p>
      <div className="grid gap-2 rounded-2xl border border-slate-100 bg-slate-50/80 px-3 py-3 text-xs leading-5 text-slate-600">
        <span>Source: {sample.source_id}</span>
        <span>Detected: {formatDateTime(sample.detected_at)} · {formatRelativeTime(sample.detected_at)}</span>
        <span>Replay: {sample.next_replay_ref.path}</span>
        <span>Incident: {sample.related_incident_ref || "未关联"}</span>
        <span>Deployment: {sample.related_deployment_record_id || "未关联"}</span>
      </div>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <RegressionBadge regressionClass={sample.regression_class} />
        <div className="flex flex-wrap gap-2">
          <Button onClick={() => onOpenReplay(sample)} size="sm" type="button">
            打开 replay
          </Button>
          <Button
            disabled={!sample.related_deployment_record_id}
            onClick={() => onOpenCompare(sample)}
            size="sm"
            type="button"
            variant="outline"
          >
            Compare deployment
          </Button>
        </div>
      </div>
    </article>
  );
}

function ReplayPanel({
  activeSample,
  answerReplay,
  answerReplayError,
  answerReplayLoading,
  documentReplay,
  documentReplayError,
  documentReplayLoading
}: {
  activeSample: OpsDiagnosticSample | null;
  answerReplay: OpsAnswerSessionReplayResponse | null;
  answerReplayError: string | null;
  answerReplayLoading: boolean;
  documentReplay: OpsDocumentReplayResponse | null;
  documentReplayError: string | null;
  documentReplayLoading: boolean;
}) {
  if (!activeSample) {
    return (
      <PanelFrame title="Replay">
        <EmptyState body="从左侧样本列表打开 answer session 或 document pipeline replay。" title="请选择样本" />
      </PanelFrame>
    );
  }

  if (activeSample.sample_kind === "answer_session") {
    return (
      <PanelFrame title="Answer replay">
        {answerReplayError ? (
          <EmptyState body={answerReplayError} tone="error" title="Answer replay 读取失败" />
        ) : answerReplayLoading ? (
          <EmptyState body="正在读取 answer session replay。" title="加载中" />
        ) : answerReplay ? (
          <AnswerReplayCard replay={answerReplay} />
        ) : (
          <EmptyState body="Replay 为空。" title="暂无回放事实" />
        )}
      </PanelFrame>
    );
  }

  return (
    <PanelFrame title="Document replay">
      {documentReplayError ? (
        <EmptyState body={documentReplayError} tone="error" title="Document replay 读取失败" />
      ) : documentReplayLoading ? (
        <EmptyState body="正在读取 document replay。" title="加载中" />
      ) : documentReplay ? (
        <DocumentReplayCard replay={documentReplay} />
      ) : (
        <EmptyState body="Replay 为空。" title="暂无回放事实" />
      )}
    </PanelFrame>
  );
}

function AnswerReplayCard({ replay }: { replay: OpsAnswerSessionReplayResponse }) {
  const retrievalSummary = replay.retrieval.summary;

  return (
    <div className="grid gap-4">
      <div className="grid gap-2 rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <strong className="text-slate-950">{replay.session.question}</strong>
          <Badge variant={replay.session.status === "answered" ? "success" : replay.session.status === "failed" ? "warning" : "info"}>
            {replay.session.status}
          </Badge>
        </div>
        <p className="m-0 text-sm leading-6 text-slate-700">{replay.session.scope_summary}</p>
        <p className="m-0 text-xs leading-6 text-slate-500">
          Session {replay.session.session_id} · updated {formatDateTime(replay.session.updated_at)}
        </p>
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        <StatCard label="Retrieval hits" value={formatCount(retrievalSummary?.merged_hit_count)} hint="merged_hit_count" />
        <StatCard label="Eligible docs" value={formatCount(retrievalSummary?.eligible_document_count)} hint="scope snapshot" />
        <StatCard label="Citations" value={formatCount(replay.session.citations.length)} hint="answer citations" />
        <StatCard label="Latency" value={formatLatencyMs(replay.session.latency_ms)} hint={formatUsd(replay.session.total_cost_usd)} />
      </div>

      <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3">
        <strong className="text-sm text-slate-950">Freshness flags</strong>
        <FreshnessFlags flags={replay.related_context.freshness_flags} />
      </div>

      <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
        <strong className="text-slate-950">Replay facts</strong>
        <span>Diagnosis: {replay.session.diagnosis_code || "无"}</span>
        <span>Refusal: {replay.session.refusal_reason || "无"}</span>
        <span>Related incident: {replay.related_context.related_incident_ref || "未关联"}</span>
        <span>Related deployment: {replay.related_context.related_deployment_record_id || "未关联"}</span>
        <span>Related evaluation: {replay.related_context.related_evaluation_run_ref || "未关联"}</span>
      </div>
    </div>
  );
}

function DocumentReplayCard({ replay }: { replay: OpsDocumentReplayResponse }) {
  return (
    <div className="grid gap-4">
      <div className="grid gap-2 rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <strong className="text-slate-950">{replay.document.title}</strong>
          <Badge variant={replay.document.citation_ready ? "success" : "warning"}>
            {replay.document.citation_ready ? "citation ready" : "citation blocked"}
          </Badge>
        </div>
        <p className="m-0 text-sm leading-6 text-slate-700">{replay.document.content_preview || "暂无内容预览"}</p>
        <p className="m-0 text-xs leading-6 text-slate-500">
          Document {replay.document.id} · imported {formatDateTime(replay.document.imported_at)}
        </p>
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        <StatCard label="Parse" value={replay.document.parse_status} hint={replay.document.diagnosis_code || "无诊断码"} />
        <StatCard label="Index" value={replay.document.index_status} hint={replay.document.indexed_at ? formatDateTime(replay.document.indexed_at) : "未索引"} />
        <StatCard label="Evidence" value={formatCount(replay.evidence.items.length)} hint="citation chunks" />
        <StatCard label="Related answers" value={formatCount(replay.related_context.related_answer_session_count)} hint="引用过该文档的会话" />
      </div>

      <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
        <strong className="text-slate-950">Blocking context</strong>
        <span>Reason: {replay.related_context.blocking_reason || "无"}</span>
        <span>Incident: {replay.related_context.related_incident_ref || "未关联"}</span>
        <span>Deployment: {replay.related_context.related_deployment_record_id || "未关联"}</span>
      </div>

      <div className="grid gap-3 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3">
        <strong className="text-sm text-slate-950">Pipeline timeline</strong>
        {replay.timeline.items.length > 0 ? (
          <div className="grid gap-2">
            {replay.timeline.items.slice(0, 6).map((item) => (
              <article className="rounded-xl border border-slate-100 bg-slate-50/80 px-3 py-2 text-xs leading-5 text-slate-600" key={`${item.event_type}-${item.created_at}`}>
                <strong className="block text-slate-900">
                  {item.stage} · {item.status}
                </strong>
                <span>{formatDateTime(item.created_at)} · {item.summary}</span>
              </article>
            ))}
          </div>
        ) : (
          <p className="m-0 text-sm leading-6 text-slate-600">暂无 timeline 事件。</p>
        )}
      </div>
    </div>
  );
}

function DeploymentComparePanel({
  activeDeploymentRecordId,
  compare,
  error,
  isLoading,
  onOpenReplay
}: {
  activeDeploymentRecordId: string;
  compare: OpsDeploymentCompareResponse | null;
  error: string | null;
  isLoading: boolean;
  onOpenReplay: (sample: OpsDiagnosticSample) => void;
}) {
  return (
    <PanelFrame title="Deployment compare">
      {activeDeploymentRecordId.length === 0 ? (
        <EmptyState
          body="输入 deployment_record_id，或从带有关联 deployment 的样本打开 compare。"
          title="等待 deployment anchor"
        />
      ) : error ? (
        <EmptyState body={error} tone="error" title="Compare 读取失败" />
      ) : isLoading ? (
        <EmptyState body={`正在读取 ${activeDeploymentRecordId} 的 release compare。`} title="加载中" />
      ) : compare ? (
        <DeploymentCompareCard compare={compare} onOpenReplay={onOpenReplay} />
      ) : (
        <EmptyState body="Compare 为空。" title="暂无发布对比事实" />
      )}
    </PanelFrame>
  );
}

function DeploymentCompareCard({
  compare,
  onOpenReplay
}: {
  compare: OpsDeploymentCompareResponse;
  onOpenReplay: (sample: OpsDiagnosticSample) => void;
}) {
  return (
    <div className="grid gap-4">
      <div className="grid gap-2 rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3 text-sm leading-6 text-slate-700">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <strong className="text-slate-950">{compare.deployment.current_image_tag}</strong>
          <Badge variant={compare.deployment.smoke_status === "passed" ? "success" : compare.deployment.smoke_status === "failed" ? "warning" : "default"}>
            smoke {compare.deployment.smoke_status}
          </Badge>
        </div>
        <span>Deployment {compare.deployment.deployment_record_id}</span>
        <span>Previous stable: {compare.baseline.previous_stable_image_tag || "未记录"}</span>
        <span>Deployed: {formatDateTime(compare.deployment.deployed_at)}</span>
      </div>

      <div className="grid gap-3 md:grid-cols-2">
        <StatCard
          label="Before samples"
          value={formatCount(compare.before_window.sample_count)}
          hint={`${formatDateTime(compare.before_window.start_at)} 至 ${formatDateTime(compare.before_window.end_at)}`}
        />
        <StatCard
          label="After samples"
          value={formatCount(compare.after_window.sample_count)}
          hint={`${formatDateTime(compare.after_window.start_at)} 至 ${formatDateTime(compare.after_window.end_at)}`}
          tone={compare.after_window.high_severity_count > 0 ? "warning" : "default"}
        />
        <StatCard label="New regression" value={formatCount(compare.delta_summary.new_regression_count)} hint="new_regression" tone={compare.delta_summary.new_regression_count > 0 ? "warning" : "default"} />
        <StatCard label="Existing debt" value={formatCount(compare.delta_summary.existing_debt_count)} hint="existing_debt" />
      </div>

      <p className="m-0 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
        {compare.delta_summary.summary}
      </p>

      {compare.affected_samples.length > 0 ? (
        <div className="grid gap-2">
          <strong className="text-sm text-slate-950">Affected samples</strong>
          {compare.affected_samples.slice(0, 4).map((sample) => (
            <article
              className="grid gap-3 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700"
              key={sample.sample_id}
            >
              <div className="flex flex-wrap items-center gap-2">
                <strong className="text-slate-950">{sample.title}</strong>
                <RegressionBadge regressionClass={sample.regression_class} />
              </div>
              <span>{sample.summary}</span>
              <Button onClick={() => onOpenReplay(sample)} size="sm" type="button" variant="outline">
                打开 replay
              </Button>
            </article>
          ))}
        </div>
      ) : (
        <EmptyState body="该 deployment compare 当前没有 affected samples。" title="暂无受影响样本" />
      )}
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

function FreshnessFlags({ flags }: { flags: OpsReplayFreshnessFlag[] }) {
  if (flags.length === 0) {
    return <Badge variant="success">无 freshness flag</Badge>;
  }

  return (
    <div className="flex flex-wrap gap-2">
      {flags.map((flag) => (
        <Badge key={flag} variant={flag === "unknown" ? "default" : "warning"}>
          {freshnessFlagLabel(flag)}
        </Badge>
      ))}
    </div>
  );
}

function SeverityBadge({ severity }: { severity: OpsDiagnosticSample["severity"] }) {
  if (severity === "high") {
    return <Badge variant="warning">高风险</Badge>;
  }

  if (severity === "medium") {
    return <Badge variant="info">中风险</Badge>;
  }

  return <Badge variant="default">低风险</Badge>;
}

function RegressionBadge({ regressionClass }: { regressionClass: OpsRegressionClass | null }) {
  if (!regressionClass) {
    return <Badge variant="default">未分类</Badge>;
  }

  if (regressionClass === "new_regression") {
    return <Badge variant="warning">新回归</Badge>;
  }

  if (regressionClass === "existing_debt") {
    return <Badge variant="info">旧债务</Badge>;
  }

  return <Badge variant="default">未知分类</Badge>;
}

function originLabel(origin: OpsDiagnosticOrigin) {
  switch (origin) {
    case "trend":
      return "趋势入口";
    case "incident_cluster":
      return "Incident 入口";
    case "release_compare":
      return "发布对比入口";
  }
}

function sampleKindLabel(kind: OpsDiagnosticSampleKind) {
  switch (kind) {
    case "answer_session":
      return "Answer";
    case "document_pipeline":
      return "Document";
  }
}

function freshnessFlagLabel(flag: OpsReplayFreshnessFlag) {
  switch (flag) {
    case "stale_document":
      return "文档 stale";
    case "citation_unready":
      return "引用未就绪";
    case "retrieval_scope_empty":
      return "检索范围为空";
    case "unknown":
      return "未知 freshness";
  }
}

function formatCount(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "未知";
  }

  return new Intl.NumberFormat("zh-CN").format(value);
}

function toErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : "unknown error";
}
