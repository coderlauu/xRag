import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type {
  IncidentSeverity,
  IncidentSource,
  OpsIncidentCluster,
  OpsRecommendedAction,
  OpsRecommendedActionCode,
  OpsReadinessBlockingReason,
  OpsReleaseGuardRiskLevel,
  OpsTrendMetric,
  OpsTrendSeries,
  OpsTrendSource,
  OpsTrendWindow
} from "@xrag/shared-types";
import { Badge, Button, PageShell, SectionCard, StatCard } from "@xrag/ui";
import { fetchOpsOverview, fetchOpsTrends } from "../../../lib/api";
import { formatLatencyMs, formatUsd } from "../../../lib/answer-state";
import { formatDateTime, formatRelativeTime } from "../../../lib/document-state";
import { DiagnosticWorkflow } from "../components/diagnostic-workflow";

const TREND_WINDOW_OPTIONS: Array<{ value: OpsTrendWindow; label: string; description: string }> = [
  { value: "24h", label: "24 小时", description: "适合看最近 deploy、smoke 与答复波动。" },
  { value: "7d", label: "7 天", description: "适合看一周内的运行与评估轨迹。" },
  { value: "30d", label: "30 天", description: "适合看长期质量和语料趋势。" }
];

const TREND_METRIC_ORDER: OpsTrendMetric[] = [
  "citation_coverage",
  "refusal_rate",
  "latency_p95_ms",
  "avg_token_cost_usd",
  "groundedness",
  "refusal_precision",
  "recall_at_10",
  "mrr",
  "hit_in_answer_rate",
  "embedding_backlog",
  "freshness_lag_p95_ms"
];

export function OpsPage() {
  const [trendWindow, setTrendWindow] = useState<OpsTrendWindow>("7d");

  const overviewQuery = useQuery({
    queryKey: ["ops", "overview"],
    queryFn: () => fetchOpsOverview(),
    refetchInterval: 30_000,
    refetchOnWindowFocus: false
  });

  const trendsQuery = useQuery({
    queryKey: ["ops", "trends", trendWindow],
    queryFn: () => fetchOpsTrends({ window: trendWindow }),
    refetchInterval: 30_000,
    refetchOnWindowFocus: false
  });

  const overview = overviewQuery.data;
  const readiness = overview?.readiness;
  const runtimeQuality = overview?.runtime_quality;
  const evaluationQuality = overview?.evaluation_quality;
  const incidentSummary = overview?.incident_summary;
  const releaseGuard = overview?.release_guard;
  const recommendedActions = overview?.recommended_actions ?? [];
  const noticeCount = overview?.notices.length ?? 0;
  const backlogCount = readiness ? readiness.queued_count + readiness.chunking_count + readiness.embedding_count : 0;
  const runtimeTrendSeries = sortTrendSeries((trendsQuery.data?.series ?? []).filter((series) => series.source === "runtime"));
  const evaluationTrendSeries = sortTrendSeries((trendsQuery.data?.series ?? []).filter((series) => series.source === "evaluation"));

  return (
    <PageShell
      eyebrow="运维"
      title="治理主板"
      description="把 corpus readiness、runtime/evaluation quality、incident cluster 与 release guard 汇到同一块板上。"
    >
      <section className="grid gap-4 md:grid-cols-4">
        <StatCard
          label="语料就绪率"
          value={formatPercent(readiness?.readiness_rate)}
          hint={
            readiness
              ? `${readiness.ready_count}/${readiness.total_count || 0} 已可引用`
              : overviewQuery.isError
                ? "overview 读取失败"
                : "等待治理快照"
          }
          tone={readiness && readiness.blocking_reason !== "none" ? "warning" : "default"}
        />
        <StatCard
          label="Runtime 引用覆盖"
          value={formatPercent(runtimeQuality?.citation_coverage)}
          hint={runtimeQuality ? `${runtimeQuality.answered_session_count} 个 answered 会话` : "等待 runtime 质量数据"}
          tone={runtimeQuality?.citation_coverage !== null && runtimeQuality?.citation_coverage !== undefined && runtimeQuality.citation_coverage < 0.8 ? "warning" : "default"}
        />
        <StatCard
          label="最新评估 groundedness"
          value={formatPercent(evaluationQuality?.groundedness)}
          hint={evaluationQuality ? `${evaluationStatusLabel(evaluationQuality.status)} · ${formatRelativeTime(evaluationQuality.completed_at)}` : "暂无评估运行事实"}
          tone={evaluationQuality?.groundedness !== null && evaluationQuality?.groundedness !== undefined && evaluationQuality.groundedness < 0.9 ? "warning" : "default"}
        />
        <StatCard
          label="发布风险"
          value={releaseGuard ? releaseGuardRiskLabel(releaseGuard.risk_level) : "未知"}
          hint={releaseGuard ? `Smoke ${deploymentSmokeLabel(releaseGuard.smoke_status)}` : "等待 release guard"}
          tone={releaseGuard && releaseGuard.risk_level !== "healthy" ? "warning" : "default"}
        />
      </section>

      <section className="grid gap-5 rounded-[30px] border border-sky-100 bg-[radial-gradient(circle_at_top_left,rgba(186,230,253,0.48),rgba(255,255,255,0.97)_52%,rgba(254,240,138,0.32))] px-6 py-5 shadow-sm">
        {overviewQuery.isError ? (
          <p className="m-0 text-sm leading-6 text-rose-700">
            治理总览读取失败。请先检查 `/api/v1/ops/overview`、数据库连通性和最新 deploy evidence。
          </p>
        ) : overview ? (
          <>
            <div className="flex flex-wrap items-start justify-between gap-4">
              <div className="grid max-w-3xl gap-2">
                <p className="m-0 text-xs uppercase tracking-[0.18em] text-sky-700">Governance Pulse</p>
                <h2 className="m-0 text-2xl font-semibold tracking-[-0.05em] text-slate-950 md:text-3xl">
                  {overview.release_guard.summary}
                </h2>
                <p className="m-0 text-sm leading-7 text-slate-700">{readinessNarrative(overview.readiness)}</p>
              </div>
              <div className="flex flex-wrap gap-2">
                <RiskBadge level={overview.release_guard.risk_level} />
                <BlockingReasonBadge reason={overview.readiness.blocking_reason} />
              </div>
            </div>

            <div className="grid gap-3 md:grid-cols-3">
              <article className="rounded-2xl border border-white/80 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
                <strong className="block text-slate-950">当前快照</strong>
                <span>{formatDateTime(overview.generated_at)}</span>
              </article>
              <article className="rounded-2xl border border-white/80 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
                <strong className="block text-slate-950">治理提示</strong>
                <span>{noticeCount} 条 lightweight notices 正在投递到 Ask / Search / Detail</span>
              </article>
              <article className="rounded-2xl border border-white/80 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
                <strong className="block text-slate-950">本轮重点</strong>
                <span>
                  {recommendedActions.length} 条推荐动作，{incidentSummary?.high_risk_count ?? 0} 条高风险 incident。
                </span>
              </article>
            </div>
          </>
        ) : (
          <p className="m-0 text-sm leading-6 text-slate-600">正在整合治理总览。</p>
        )}
      </section>

      <DiagnosticWorkflow clusters={incidentSummary?.clusters ?? []} onWindowChange={setTrendWindow} window={trendWindow} />

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.05fr)_minmax(320px,0.95fr)]">
        <div className="grid gap-6">
          <SectionCard title="Corpus Readiness" description="决定 Ask 是否有足够稳定、可引用的证据底料。">
            {readiness ? (
              <div className="grid gap-4">
                <div className="flex flex-wrap items-start justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3">
                  <div className="grid gap-1">
                    <strong className="text-slate-950">当前阻塞原因</strong>
                    <p className="m-0 text-sm leading-6 text-slate-700">{blockingReasonSummary(readiness.blocking_reason)}</p>
                  </div>
                  <BlockingReasonBadge reason={readiness.blocking_reason} />
                </div>

                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
                  <StatCard label="Ready" value={formatCount(readiness.ready_count)} hint="citation_ready = true" />
                  <StatCard
                    label="Backlog"
                    value={formatCount(backlogCount)}
                    hint={`${readiness.queued_count} queued / ${readiness.chunking_count} chunking / ${readiness.embedding_count} embedding`}
                    tone={backlogCount > 0 ? "warning" : "default"}
                  />
                  <StatCard
                    label="Failed"
                    value={formatCount(readiness.failed_count)}
                    hint="需要人工排查或重建"
                    tone={readiness.failed_count > 0 ? "warning" : "default"}
                  />
                  <StatCard
                    label="Stale"
                    value={formatCount(readiness.stale_count)}
                    hint="索引需要刷新"
                    tone={readiness.stale_count > 0 ? "warning" : "default"}
                  />
                  <StatCard
                    label="Freshness Lag P95"
                    value={formatLagMs(readiness.freshness_lag_p95_ms)}
                    hint="indexed_at - imported_at"
                    tone={readiness.freshness_lag_p95_ms !== null && readiness.freshness_lag_p95_ms > 6 * 60 * 60 * 1000 ? "warning" : "default"}
                  />
                </div>
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">正在加载 readiness 快照。</p>
            )}
          </SectionCard>

          <SectionCard title="Runtime Quality" description="基于运行中的 Ask 会话，判断线上回答是否稳定、是否仍然可引用。">
            {runtimeQuality ? (
              <div className="grid gap-4">
                <div className="rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3 text-sm leading-6 text-slate-700">
                  当前窗口 `{windowLabel(runtimeQuality.window)}` 内共有 {runtimeQuality.terminal_session_count} 个终态会话，其中{" "}
                  {runtimeQuality.answered_session_count} 个已回答。
                </div>

                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
                  <StatCard label="终态会话" value={formatCount(runtimeQuality.terminal_session_count)} hint={windowLabel(runtimeQuality.window)} />
                  <StatCard label="已回答" value={formatCount(runtimeQuality.answered_session_count)} hint="status = answered" />
                  <StatCard
                    label="引用覆盖"
                    value={formatPercent(runtimeQuality.citation_coverage)}
                    hint="answered 中至少带一条 citation"
                    tone={runtimeQuality.citation_coverage !== null && runtimeQuality.citation_coverage < 0.8 ? "warning" : "default"}
                  />
                  <StatCard
                    label="拒答率"
                    value={formatPercent(runtimeQuality.refusal_rate)}
                    hint="refused / terminal"
                    tone={runtimeQuality.refusal_rate !== null && runtimeQuality.refusal_rate > 0.25 ? "warning" : "default"}
                  />
                  <StatCard
                    label="Latency P95"
                    value={formatLatencyMs(runtimeQuality.latency_p95_ms)}
                    hint={runtimeQuality.avg_token_cost_usd ? formatUsd(runtimeQuality.avg_token_cost_usd) : "暂无成本数据"}
                    tone={runtimeQuality.latency_p95_ms !== null && runtimeQuality.latency_p95_ms > 5000 ? "warning" : "default"}
                  />
                </div>
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">正在加载 runtime quality。</p>
            )}
          </SectionCard>
        </div>

        <div className="grid gap-6">
          <SectionCard title="Evaluation Quality" description="基于最近一次受控评估运行，判断离线质量是否支持当前发布状态。">
            {evaluationQuality ? (
              <div className="grid gap-4">
                <div className="flex flex-wrap items-start justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3">
                  <div className="grid gap-1 text-sm leading-6 text-slate-700">
                    <strong className="text-slate-950">{evaluationQuality.latest_run_ref}</strong>
                    <span>
                      {evaluationQuality.environment} · {evaluationQuality.source} · {formatDateTime(evaluationQuality.completed_at)}
                    </span>
                    <span>
                      commit {shortSha(evaluationQuality.commit_sha)} · dataset {evaluationQuality.dataset_version || "未记录"}
                    </span>
                  </div>
                  <Badge variant={evaluationQuality.status === "completed" ? "success" : "warning"}>
                    {evaluationStatusLabel(evaluationQuality.status)}
                  </Badge>
                </div>

                <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
                  <StatCard label="Groundedness" value={formatPercent(evaluationQuality.groundedness)} hint="答案与证据一致性" />
                  <StatCard label="引用覆盖" value={formatPercent(evaluationQuality.citation_coverage)} hint="评估集中回答是否带 citation" />
                  <StatCard label="拒答精度" value={formatPercent(evaluationQuality.refusal_precision)} hint="refusal precision" />
                  <StatCard label="Recall@10" value={formatPercent(evaluationQuality.recall_at_10)} hint="检索召回" />
                  <StatCard label="MRR" value={formatMetricValue("mrr", evaluationQuality.mrr)} hint="排序相关性" />
                  <StatCard label="Hit In Answer" value={formatPercent(evaluationQuality.hit_in_answer_rate)} hint="命中证据是否进入答案" />
                  <StatCard label="Latency P95" value={formatLatencyMs(evaluationQuality.latency_p95_ms)} hint="评估运行输出" />
                  <StatCard label="平均成本" value={formatUsd(evaluationQuality.avg_token_cost_usd)} hint="评估运行平均 token 成本" />
                </div>
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">暂无 evaluation_runs 事实。Lane B 已提供写入脚本，后续需要接入正式评估节奏。</p>
            )}
          </SectionCard>

          <SectionCard title="Release Guard" description="发布阶段的 smoke、镜像与相关 incident 是否允许继续放量。">
            {releaseGuard ? (
              <div className="grid gap-4">
                <div className="flex flex-wrap items-start justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3">
                  <div className="grid gap-1 text-sm leading-6 text-slate-700">
                    <strong className="text-slate-950">{releaseGuard.summary}</strong>
                    <span>部署于 {formatDateTime(releaseGuard.deployed_at)}，最近 smoke {deploymentSmokeLabel(releaseGuard.smoke_status)}。</span>
                  </div>
                  <RiskBadge level={releaseGuard.risk_level} />
                </div>

                <div className="grid gap-3 sm:grid-cols-2">
                  <ReleaseFactCard label="当前镜像" value={releaseGuard.current_image_tag || "未知"} />
                  <ReleaseFactCard label="上一稳定版本" value={releaseGuard.previous_stable_image_tag || "未知"} />
                  <ReleaseFactCard label="Workflow Run" value={releaseGuard.workflow_run_id || "未记录"} />
                  <ReleaseFactCard label="Smoke 时间" value={formatDateTime(releaseGuard.smoke_at)} />
                  <ReleaseFactCard label="关联评估" value={releaseGuard.related_evaluation_run_ref || "未关联"} />
                  <ReleaseFactCard label="关联 incident" value={formatCount(releaseGuard.related_incident_count)} />
                </div>
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">正在加载 release guard。</p>
            )}
          </SectionCard>
        </div>
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.02fr)_minmax(320px,0.98fr)]">
        <SectionCard title="Incident Clusters" description="先按来源、风险和影响面聚类，再决定排查优先级。">
          {incidentSummary ? (
            <div className="grid gap-4">
              <div className="grid gap-3 sm:grid-cols-2">
                <article className="rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3 text-sm leading-6 text-slate-700">
                  <strong className="block text-slate-950">未解决 incident</strong>
                  <span>{formatCount(incidentSummary.open_count)} 条</span>
                </article>
                <article className="rounded-2xl border border-slate-200 bg-slate-50/85 px-4 py-3 text-sm leading-6 text-slate-700">
                  <strong className="block text-slate-950">高风险 incident</strong>
                  <span>{formatCount(incidentSummary.high_risk_count)} 条</span>
                </article>
              </div>

              {incidentSummary.clusters.length > 0 ? (
                <div className="grid gap-3">
                  {incidentSummary.clusters.map((cluster) => (
                    <IncidentClusterCard cluster={cluster} key={cluster.cluster_key} />
                  ))}
                </div>
              ) : (
                <p className="m-0 text-sm leading-6 text-slate-600">当前没有 incident cluster，继续保持常规巡检。</p>
              )}
            </div>
          ) : (
            <p className="m-0 text-sm leading-6 text-slate-600">正在加载 incident 汇总。</p>
          )}
        </SectionCard>

        <SectionCard title="Recommended Actions" description="这里是治理层给主线程的排查顺序建议，不会自动代替任何操作。">
          {recommendedActions.length > 0 ? (
            <div className="grid gap-3">
              {recommendedActions.map((action) => (
                <RecommendedActionCard action={action} key={action.code} />
              ))}
            </div>
          ) : (
            <p className="m-0 text-sm leading-6 text-slate-600">当前没有推荐动作。</p>
          )}
        </SectionCard>
      </section>

      <SectionCard
        title="Trends"
        description="同一块板上同时看 runtime 与 evaluation 的走势，快速判断问题是短时抖动还是持续劣化。"
      >
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="grid gap-1">
            <p className="m-0 text-sm leading-6 text-slate-700">当前窗口：{windowLabel(trendWindow)}</p>
            <p className="m-0 text-xs leading-6 text-slate-500">
              {TREND_WINDOW_OPTIONS.find((option) => option.value === trendWindow)?.description}
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            {TREND_WINDOW_OPTIONS.map((option) => (
              <Button
                key={option.value}
                onClick={() => setTrendWindow(option.value)}
                size="sm"
                type="button"
                variant={trendWindow === option.value ? "default" : "outline"}
              >
                {option.label}
              </Button>
            ))}
          </div>
        </div>

        {trendsQuery.isError ? (
          <p className="m-0 text-sm leading-6 text-rose-700">趋势数据读取失败，请检查 `/api/v1/ops/trends`。</p>
        ) : (
          <div className="grid gap-6">
            <TrendGroup
              description="来自线上 Ask 会话与运行事实。"
              emptyMessage="当前窗口没有 runtime trend 样本。"
              series={runtimeTrendSeries}
              title="Runtime"
            />
            <TrendGroup
              description="来自 evaluation_runs 写入的受控评估事实。"
              emptyMessage="当前窗口没有 evaluation trend 样本。"
              series={evaluationTrendSeries}
              title="Evaluation"
            />
          </div>
        )}
      </SectionCard>
    </PageShell>
  );
}

function TrendGroup({
  title,
  description,
  series,
  emptyMessage
}: {
  title: string;
  description: string;
  series: OpsTrendSeries[];
  emptyMessage: string;
}) {
  return (
    <div className="grid gap-4">
      <div className="grid gap-1">
        <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">{title}</p>
        <p className="m-0 text-sm leading-6 text-slate-600">{description}</p>
      </div>

      {series.length > 0 ? (
        <div className="grid gap-4 lg:grid-cols-2 xl:grid-cols-3">
          {series.map((item) => (
            <TrendCard key={`${item.source}-${item.metric}`} series={item} />
          ))}
        </div>
      ) : (
        <p className="m-0 text-sm leading-6 text-slate-600">{emptyMessage}</p>
      )}
    </div>
  );
}

function TrendCard({ series }: { series: OpsTrendSeries }) {
  const latestPoint = getLatestPoint(series);
  const previousPoint = getPreviousPoint(series);

  return (
    <article className="grid gap-4 rounded-[24px] border border-slate-200 bg-[linear-gradient(180deg,rgba(248,250,252,0.96),rgba(255,255,255,0.98))] px-4 py-4 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="grid gap-1">
          <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">{trendSourceLabel(series.source)}</p>
          <strong className="text-slate-950">{trendMetricLabel(series.metric)}</strong>
        </div>
        <Badge variant={series.source === "runtime" ? "info" : "default"}>{series.granularity}</Badge>
      </div>

      <div className="grid gap-1">
        <p className="m-0 text-3xl font-semibold tracking-[-0.05em] text-slate-950">
          {formatMetricValue(series.metric, latestPoint?.value)}
        </p>
        <p className="m-0 text-xs leading-6 text-slate-500">
          {latestPoint
            ? `最新 bucket ${formatTrendBucket(latestPoint.ts, series.granularity)}`
            : "当前窗口暂无样本"}
          {previousPoint ? ` · 上一 bucket ${formatMetricValue(series.metric, previousPoint.value)}` : ""}
        </p>
      </div>

      <TrendSparkline series={series} />
    </article>
  );
}

function TrendSparkline({ series }: { series: OpsTrendSeries }) {
  const numericPoints = series.points
    .map((point) => ({ ts: point.ts, value: numericValue(point.value) }))
    .filter((point): point is { ts: string; value: number } => point.value !== null);

  if (numericPoints.length === 0) {
    return (
      <div className="rounded-2xl border border-dashed border-slate-200 bg-white/70 px-4 py-6 text-center text-sm leading-6 text-slate-500">
        暂无时间序列样本
      </div>
    );
  }

  const width = 320;
  const height = 92;
  const padding = 10;
  const values = numericPoints.map((point) => point.value);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const valueRange = max - min || 1;
  const pointRange = Math.max(1, numericPoints.length - 1);
  const linePoints = numericPoints.map((point, index) => {
    const x = padding + (index / pointRange) * (width - padding * 2);
    const normalized = (point.value - min) / valueRange;
    const y = height - padding - normalized * (height - padding * 2);
    return { ...point, x, y };
  });
  const polyline = linePoints.map((point) => `${point.x},${point.y}`).join(" ");
  const gradientId = `${series.source}-${series.metric}`.replace(/[^a-z0-9-]/gi, "-");

  return (
    <div className="grid gap-2 rounded-2xl border border-slate-200 bg-white/80 px-3 py-3">
      <svg aria-hidden="true" className="h-24 w-full" viewBox={`0 0 ${width} ${height}`}>
        <defs>
          <linearGradient id={gradientId} x1="0%" x2="100%" y1="0%" y2="0%">
            <stop offset="0%" stopColor={series.source === "runtime" ? "#0ea5e9" : "#475569"} />
            <stop offset="100%" stopColor={series.source === "runtime" ? "#38bdf8" : "#94a3b8"} />
          </linearGradient>
        </defs>
        <path
          d={`M ${padding} ${height - padding} H ${width - padding}`}
          fill="none"
          opacity="0.2"
          stroke="#94a3b8"
          strokeDasharray="4 6"
          strokeWidth="1"
        />
        <polyline
          fill="none"
          points={polyline}
          stroke={`url(#${gradientId})`}
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth="3"
        />
        {linePoints.map((point) => (
          <circle
            cx={point.x}
            cy={point.y}
            fill={series.source === "runtime" ? "#0284c7" : "#475569"}
            key={`${series.metric}-${point.ts}`}
            r="3.5"
          />
        ))}
      </svg>
      <div className="flex items-center justify-between gap-3 text-xs leading-6 text-slate-500">
        <span>{formatTrendBucket(linePoints[0]?.ts || null, series.granularity)}</span>
        <span>{formatTrendBucket(linePoints[linePoints.length - 1]?.ts || null, series.granularity)}</span>
      </div>
    </div>
  );
}

function IncidentClusterCard({ cluster }: { cluster: OpsIncidentCluster }) {
  return (
    <article className="grid gap-3 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <strong className="text-slate-950">{cluster.cluster_key}</strong>
        <div className="flex flex-wrap gap-2">
          <Badge variant={cluster.status === "resolved" ? "success" : cluster.severity === "high" ? "warning" : "info"}>
            {incidentStatusLabel(cluster.status)}
          </Badge>
          <Badge variant={cluster.severity === "high" ? "warning" : "default"}>{severityLabel(cluster.severity)}</Badge>
        </div>
      </div>
      <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs uppercase tracking-[0.16em] text-slate-500">
        <span>{sourceLabel(cluster.source)}</span>
        <span>{affectedSurfaceLabel(cluster.affected_surface)}</span>
        <span>{formatCount(cluster.incident_count)} 条</span>
      </div>
      <p className="m-0">
        最新 incident：{cluster.latest_incident_ref || "未记录"}。建议动作：{recommendedActionCodeLabel(cluster.recommended_action_code)}。
      </p>
    </article>
  );
}

function RecommendedActionCard({ action }: { action: OpsRecommendedAction }) {
  return (
    <article className="grid gap-3 rounded-2xl border border-slate-200 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <strong className="text-slate-950">{action.title}</strong>
        <div className="flex flex-wrap gap-2">
          <Badge variant={action.priority === "high" ? "warning" : action.priority === "medium" ? "info" : "default"}>
            {priorityLabel(action.priority)}
          </Badge>
          <Badge variant="default">{actionSurfaceLabel(action.surface)}</Badge>
        </div>
      </div>
      <p className="m-0">{action.summary}</p>
      <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">{recommendedActionCodeLabel(action.code)}</p>
    </article>
  );
}

function ReleaseFactCard({ label, value }: { label: string; value: string }) {
  return (
    <article className="rounded-2xl border border-slate-200 bg-white/85 px-4 py-3 text-sm leading-6 text-slate-700">
      <strong className="block text-slate-950">{label}</strong>
      <span>{value}</span>
    </article>
  );
}

function RiskBadge({ level }: { level: OpsReleaseGuardRiskLevel }) {
  if (level === "critical") {
    return <Badge className="border-rose-200 bg-rose-100 text-rose-800">Critical</Badge>;
  }

  if (level === "warning") {
    return <Badge variant="warning">Warning</Badge>;
  }

  return <Badge variant="success">Healthy</Badge>;
}

function BlockingReasonBadge({ reason }: { reason: OpsReadinessBlockingReason }) {
  if (reason === "none") {
    return <Badge variant="success">无阻塞</Badge>;
  }

  if (reason === "indexing_failed" || reason === "no_ready_documents") {
    return <Badge className="border-rose-200 bg-rose-100 text-rose-800">{blockingReasonLabel(reason)}</Badge>;
  }

  return <Badge variant="warning">{blockingReasonLabel(reason)}</Badge>;
}

function sortTrendSeries(series: OpsTrendSeries[]) {
  return [...series].sort((left, right) => {
    const sourceDelta = trendSourceOrder(left.source) - trendSourceOrder(right.source);
    if (sourceDelta !== 0) {
      return sourceDelta;
    }

    return TREND_METRIC_ORDER.indexOf(left.metric) - TREND_METRIC_ORDER.indexOf(right.metric);
  });
}

function trendSourceOrder(source: OpsTrendSource) {
  return source === "runtime" ? 0 : 1;
}

function getLatestPoint(series: OpsTrendSeries) {
  return [...series.points].reverse().find((point) => point.value !== null);
}

function getPreviousPoint(series: OpsTrendSeries) {
  const validPoints = series.points.filter((point) => point.value !== null);
  return validPoints.length > 1 ? validPoints[validPoints.length - 2] : null;
}

function numericValue(value: number | string | null | undefined) {
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }

  if (typeof value === "string") {
    const parsed = Number(value);
    return Number.isFinite(parsed) ? parsed : null;
  }

  return null;
}

function formatCount(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "未知";
  }

  return new Intl.NumberFormat("zh-CN").format(value);
}

function formatPercent(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "未知";
  }

  return new Intl.NumberFormat("zh-CN", {
    style: "percent",
    minimumFractionDigits: 0,
    maximumFractionDigits: 1
  }).format(value);
}

function formatLagMs(value: number | null | undefined) {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return "未知";
  }

  if (value < 60_000) {
    return `${Math.round(value / 1000)} s`;
  }

  if (value < 60 * 60_000) {
    return `${Math.round(value / 60_000)} min`;
  }

  if (value < 24 * 60 * 60_000) {
    return `${(value / (60 * 60_000)).toFixed(1)} h`;
  }

  return `${(value / (24 * 60 * 60_000)).toFixed(1)} d`;
}

function formatMetricValue(metric: OpsTrendMetric, value: number | string | null | undefined) {
  switch (metric) {
    case "citation_coverage":
    case "refusal_rate":
    case "groundedness":
    case "refusal_precision":
    case "recall_at_10":
    case "hit_in_answer_rate":
      return formatPercent(typeof value === "number" ? value : numericValue(value));
    case "latency_p95_ms":
      return formatLatencyMs(typeof value === "number" ? value : numericValue(value));
    case "avg_token_cost_usd":
      return formatUsd(typeof value === "string" ? value : value === null || value === undefined ? null : String(value));
    case "mrr":
      return value === null || value === undefined ? "未知" : Number(value).toFixed(3);
    case "embedding_backlog":
      return formatCount(typeof value === "number" ? value : numericValue(value));
    case "freshness_lag_p95_ms":
      return formatLagMs(typeof value === "number" ? value : numericValue(value));
  }
}

function windowLabel(window: OpsTrendWindow) {
  switch (window) {
    case "24h":
      return "24 小时";
    case "7d":
      return "7 天";
    case "30d":
      return "30 天";
  }
}

function trendMetricLabel(metric: OpsTrendMetric) {
  switch (metric) {
    case "citation_coverage":
      return "引用覆盖";
    case "refusal_rate":
      return "拒答率";
    case "latency_p95_ms":
      return "Latency P95";
    case "avg_token_cost_usd":
      return "平均成本";
    case "groundedness":
      return "Groundedness";
    case "refusal_precision":
      return "拒答精度";
    case "recall_at_10":
      return "Recall@10";
    case "mrr":
      return "MRR";
    case "hit_in_answer_rate":
      return "Hit In Answer";
    case "embedding_backlog":
      return "Embedding Backlog";
    case "freshness_lag_p95_ms":
      return "Freshness Lag P95";
  }
}

function trendSourceLabel(source: OpsTrendSource) {
  switch (source) {
    case "runtime":
      return "Runtime";
    case "evaluation":
      return "Evaluation";
  }
}

function sourceLabel(source: IncidentSource) {
  switch (source) {
    case "upload":
      return "上传";
    case "parse":
      return "解析";
    case "ocr":
      return "OCR";
    case "fetch":
      return "抓取";
    case "projection":
      return "投影";
    case "deploy":
      return "部署";
    case "ci":
      return "CI";
  }
}

function severityLabel(severity: IncidentSeverity) {
  switch (severity) {
    case "low":
      return "低风险";
    case "medium":
      return "中风险";
    case "high":
      return "高风险";
  }
}

function incidentStatusLabel(status: OpsIncidentCluster["status"]) {
  switch (status) {
    case "open":
      return "待处理";
    case "tracked":
      return "处理中";
    case "resolved":
      return "已解决";
  }
}

function affectedSurfaceLabel(surface: OpsIncidentCluster["affected_surface"]) {
  switch (surface) {
    case "upload":
      return "上传面";
    case "indexing":
      return "索引面";
    case "retrieval":
      return "检索面";
    case "answer":
      return "回答面";
    case "deployment":
      return "发布面";
    case "ci":
      return "CI 面";
    case "ops":
      return "运维面";
  }
}

function actionSurfaceLabel(surface: OpsRecommendedAction["surface"]) {
  switch (surface) {
    case "ops":
      return "治理";
    case "ask":
      return "Ask";
    case "search":
      return "Search";
    case "detail":
      return "Detail";
    case "deployment":
      return "发布";
    case "indexing":
      return "索引";
    case "evaluation":
      return "评估";
  }
}

function priorityLabel(priority: IncidentSeverity) {
  switch (priority) {
    case "low":
      return "低优先级";
    case "medium":
      return "中优先级";
    case "high":
      return "高优先级";
  }
}

function evaluationStatusLabel(status: "completed" | "failed") {
  return status === "completed" ? "已完成" : "失败";
}

function releaseGuardRiskLabel(level: OpsReleaseGuardRiskLevel) {
  switch (level) {
    case "healthy":
      return "健康";
    case "warning":
      return "注意";
    case "critical":
      return "严重";
  }
}

function deploymentSmokeLabel(status: "passed" | "failed" | "unknown") {
  switch (status) {
    case "passed":
      return "通过";
    case "failed":
      return "失败";
    case "unknown":
      return "未知";
  }
}

function blockingReasonLabel(reason: OpsReadinessBlockingReason) {
  switch (reason) {
    case "none":
      return "无阻塞";
    case "no_ready_documents":
      return "无可引用文档";
    case "indexing_backlog":
      return "存在索引积压";
    case "indexing_failed":
      return "存在失败索引";
    case "stale_corpus":
      return "语料已过期";
  }
}

function blockingReasonSummary(reason: OpsReadinessBlockingReason) {
  switch (reason) {
    case "none":
      return "当前语料就绪状态允许 Ask 正常进入证据检索。";
    case "no_ready_documents":
      return "当前没有任何 citation_ready 文档，Ask 仍可提交，但很难形成可信回答。";
    case "indexing_backlog":
      return "当前有文档处在 queued / chunking / embedding 中，搜索与问答结果会存在延迟。";
    case "indexing_failed":
      return "已有文档索引失败，必须先查明失败原因，再判断 Ask 的覆盖面是否可接受。";
    case "stale_corpus":
      return "已有可引用语料，但部分 corpus 已 stale，需要安排重建以避免旧证据。";
  }
}

function recommendedActionCodeLabel(code: OpsRecommendedActionCode) {
  switch (code) {
    case "inspect_indexing_backlog":
      return "检查索引积压";
    case "inspect_failed_documents":
      return "检查失败文档";
    case "run_backfill_indexing_dry_run":
      return "执行索引回填演练";
    case "inspect_quality_regression":
      return "检查质量回退";
    case "inspect_incident_cluster":
      return "检查 incident cluster";
    case "verify_latest_deployment":
      return "核对最近部署事实";
    case "rollback_to_previous_stable":
      return "核对上一稳定版本";
    case "monitor_without_action":
      return "继续观察";
  }
}

function readinessNarrative(
  readiness:
    | {
        blocking_reason: OpsReadinessBlockingReason;
        ready_count: number;
        total_count: number;
      }
    | undefined
) {
  if (!readiness) {
    return "正在整理 readiness 快照。";
  }

  const coverage = readiness.total_count > 0 ? `${readiness.ready_count}/${readiness.total_count}` : "0/0";
  return `${blockingReasonSummary(readiness.blocking_reason)} 当前 ready 覆盖 ${coverage}。`;
}

function shortSha(value: string | null | undefined) {
  if (!value) {
    return "未记录";
  }

  return value.slice(0, 8);
}

function formatTrendBucket(value: string | null | undefined, granularity: OpsTrendSeries["granularity"]) {
  if (!value) {
    return "未知";
  }

  return new Intl.DateTimeFormat("zh-CN", granularity === "hour" ? { hour: "2-digit", minute: "2-digit" } : { month: "numeric", day: "numeric" }).format(
    new Date(value)
  );
}
