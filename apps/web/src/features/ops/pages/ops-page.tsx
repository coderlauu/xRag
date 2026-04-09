import { useQuery } from "@tanstack/react-query";
import { Badge, PageShell, SectionCard, StatCard } from "@xrag/ui";
import { fetchOpsAnswerSummary, fetchOpsHealthSummary, getLatestDeployment, listOpsIncidents } from "../../../lib/api";
import { formatLatencyMs, formatUsd } from "../../../lib/answer-state";
import type { IncidentSeverity, IncidentSource } from "@xrag/shared-types";

function serviceStatusLabel(status: "healthy" | "warning" | "critical") {
  switch (status) {
    case "healthy":
      return "健康";
    case "warning":
      return "注意";
    case "critical":
      return "严重";
  }
}

function incidentStatusLabel(status: "open" | "tracked" | "resolved") {
  switch (status) {
    case "open":
      return "待处理";
    case "tracked":
      return "处理中";
    case "resolved":
      return "已解决";
  }
}

function incidentSeverityLabel(severity: "low" | "medium" | "high") {
  switch (severity) {
    case "low":
      return "低";
    case "medium":
      return "中";
    case "high":
      return "高";
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

export function OpsPage() {
  const answerSummaryQuery = useQuery({
    queryKey: ["ops", "answer-summary"],
    queryFn: () => fetchOpsAnswerSummary(),
    refetchInterval: 15_000
  });

  const healthQuery = useQuery({
    queryKey: ["ops", "health-summary"],
    queryFn: () => fetchOpsHealthSummary(),
    refetchInterval: 10_000
  });

  const incidentsQuery = useQuery({
    queryKey: ["ops", "incidents"],
    queryFn: () => listOpsIncidents(),
    refetchInterval: 15_000
  });

  const deploymentQuery = useQuery({
    queryKey: ["ops", "deployment-latest"],
    queryFn: () => getLatestDeployment(),
    refetchInterval: 15_000
  });

  const health = healthQuery.data;
  const answerSummary = answerSummaryQuery.data;
  const incidents = incidentsQuery.data?.items || [];
  const deployment = deploymentQuery.data;
  const answerSummaryTotalDocuments =
    (answerSummary?.embedding_backlog || 0) +
    (answerSummary?.ready_document_count || 0) +
    (answerSummary?.stale_document_count || 0) +
    (answerSummary?.failed_document_count || 0);
  const readinessRate =
    answerSummary && answerSummaryTotalDocuments > 0
      ? answerSummary.ready_document_count / answerSummaryTotalDocuments
      : null;
  const answerSummaryStatus =
    answerSummaryTotalDocuments === 0
      ? "暂无索引数据"
      : (answerSummary?.failed_document_count || 0) > 0
        ? "存在失败索引"
      : (answerSummary?.embedding_backlog || 0) > 0 || (answerSummary?.stale_document_count || 0) > 0
        ? "需要处理"
        : "可用于问答";
  const openIncidents = incidents.filter((incident) => incident.status !== "resolved").length;
  const warningServices = health?.services.filter((service) => service.status !== "healthy").length || 0;
  const highRiskIncidents = incidents.filter((incident) => incident.severity === "high").length;
  const groupedBySource = incidents.reduce<Record<IncidentSource, number>>(
    (groups, incident) => ({
      ...groups,
      [incident.source]: groups[incident.source] + 1
    }),
    { upload: 0, parse: 0, ocr: 0, fetch: 0, projection: 0, deploy: 0, ci: 0 }
  );
  const groupedBySeverity = incidents.reduce<Record<IncidentSeverity, number>>(
    (groups, incident) => ({
      ...groups,
      [incident.severity]: groups[incident.severity] + 1
    }),
    { low: 0, medium: 0, high: 0 }
  );
  const coreServices = health?.services.filter((service) =>
    ["api", "worker", "storage", "database"].includes(service.name)
  ) || [];
  const runtimeServices = health?.services.filter((service) =>
    ["ocr-runtime", "link-fetcher", "search-projection", "upload-chain"].includes(service.name)
  ) || [];
  const recommendedActions = [
    warningServices > 0
      ? `先检查 ${warningServices} 个非健康服务的依赖连通性，再继续查看导入失败事件。`
      : "核心服务当前均返回健康，可优先从失败事件和最近部署入手排查。",
    highRiskIncidents > 0
      ? `当前有 ${highRiskIncidents} 条高风险事件，建议优先处理对象缺失、队列积压或 PDF 解析超时。`
      : "当前没有高风险事件，可按影响面从中风险事件开始处理。",
    deployment?.last_smoke_status === "failed"
      ? "最近一次 smoke 失败，回滚前先核对当前镜像与上一稳定版本差异。"
      : "最近一次 smoke 未报错，如出现新问题，优先比对最近镜像和 incident 产生时间。",
    incidents.length > 0
      ? "处理单条失败时，优先从详情页查看诊断码，再回到运维页核对是否存在同源批量问题。"
      : "当前没有 incident，可把这里作为部署完成后的日常巡检入口。"
  ];
  const degradationActions = [
    "如 OCR runtime 持续告警，先关闭 OCR feature flag，再继续保留 PDF 基础导入能力。",
    "如链接抓取器连续失败，先暂停链接抓取入口，必要时引导用户改为复制正文导入。",
    "如搜索投影连续过期，先暂停批量重建，优先保障新导入文档链路。",
    deployment?.previous_stable_image_tag
      ? `如 smoke 失败或 incident 持续扩散，可回退到上一稳定镜像 ${deployment.previous_stable_image_tag}。`
      : "如 smoke 失败或 incident 持续扩散，请先确认上一稳定镜像标签，再执行回滚。"
  ];

  return (
    <PageShell eyebrow="运维" title="运维看板" description="把健康检查、事件分布、推荐动作和回滚基线放到同一块板上。">
      <section className="grid gap-4 md:grid-cols-4">
        <StatCard
          label="健康服务数"
          value={String(health?.services.length || 0)}
          hint={
            healthQuery.isError
              ? "健康摘要读取失败"
              : health?.generated_at
                ? `更新时间 ${health.generated_at}`
                : "等待接口返回"
          }
        />
        <StatCard label="待处理事件" value={String(openIncidents)} hint={`总事件 ${incidents.length} 条`} />
        <StatCard
          label="最近部署"
          value={deployment?.current_image_tag || "未知"}
          hint={deployment ? `Smoke ${deploymentSmokeLabel(deployment.last_smoke_status)}` : "等待部署摘要"}
        />
        <StatCard
          label="高风险事件"
          value={String(highRiskIncidents)}
          hint="优先处理对象缺失、PDF 超时和队列积压"
          tone={highRiskIncidents > 0 ? "warning" : "default"}
        />
      </section>

      <SectionCard
        title="答案摘要"
        description="把索引就绪、答案时延、引用覆盖、拒答率和成本放在一起看，先判断问答链路是否可用。"
      >
        {answerSummaryQuery.isError ? (
          <p className="m-0 text-sm leading-6 text-rose-700">答案摘要加载失败，请检查 API、数据库和 answer_sessions 数据。</p>
        ) : answerSummary ? (
          <div className="grid gap-4">
            <div className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3">
              <div className="grid gap-1">
                <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">当前状态</p>
                <p className="m-0 text-sm leading-6 text-slate-700">
                  {answerSummaryStatus}，共 {answerSummaryTotalDocuments} 个文档参与摘要计算。
                </p>
              </div>
              <Badge variant={readinessRate && readinessRate >= 0.9 ? "success" : "warning"}>
                {formatPercent(readinessRate)}
              </Badge>
            </div>

            <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-5">
              <StatCard
                label="索引积压"
                value={String(answerSummary.embedding_backlog)}
                hint="queued / chunking / embedding 中的文档数"
                tone={answerSummary.embedding_backlog > 0 ? "warning" : "default"}
              />
              <StatCard
                label="可回答文档"
                value={String(answerSummary.ready_document_count)}
                hint="ready 状态文档数"
              />
              <StatCard
                label="过期文档"
                value={String(answerSummary.stale_document_count)}
                hint="需要重新索引的文档数"
                tone={answerSummary.stale_document_count > 0 ? "warning" : "default"}
              />
              <StatCard
                label="失败文档"
                value={String(answerSummary.failed_document_count)}
                hint="索引失败、需要人工排查的文档数"
                tone={answerSummary.failed_document_count > 0 ? "warning" : "default"}
              />
              <StatCard
                label="就绪率"
                value={formatPercent(readinessRate)}
                hint="ready / (ready + backlog + stale + failed)"
                tone={readinessRate !== null && readinessRate < 0.8 ? "warning" : "default"}
              />
            </div>

            <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
              <StatCard
                label="回答延迟 P95"
                value={formatLatencyMs(answerSummary.answer_latency_p95)}
                hint="仅统计 answered / needs_scope / refused 的终态会话"
              />
              <StatCard
                label="引用覆盖率"
                value={formatPercent(answerSummary.citation_coverage)}
                hint="answered 会话中至少包含一条 citation 的比例"
              />
              <StatCard
                label="拒答率"
                value={formatPercent(answerSummary.refusal_rate)}
                hint="refused / (answered + needs_scope + refused)"
              />
              <StatCard
                label="平均成本"
                value={formatUsd(answerSummary.avg_token_cost_usd)}
                hint="终态问答会话的平均 token 成本"
              />
            </div>
          </div>
        ) : (
          <p className="m-0 text-sm leading-6 text-slate-600">正在加载答案摘要。</p>
        )}
      </SectionCard>

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.1fr)_minmax(320px,0.9fr)]">
        <SectionCard title="服务健康" description="面向内部使用的服务连通性摘要。">
          {healthQuery.isError ? (
            <p className="m-0 text-sm leading-6 text-rose-700">健康摘要加载失败，请检查 API 与依赖服务。</p>
          ) : health ? (
            <div className="grid gap-4">
              <div className="grid gap-2">
                <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">核心服务</p>
                <div className="grid gap-3">
                  {coreServices.map((service) => (
                    <article
                      className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700"
                      key={service.name}
                    >
                      <div className="grid gap-1">
                        <strong className="text-slate-950">{serviceLabel(service.name)}</strong>
                        <span>{service.detail}</span>
                      </div>
                      <Badge variant={service.status === "healthy" ? "success" : "warning"}>
                        {serviceStatusLabel(service.status)}
                      </Badge>
                    </article>
                  ))}
                </div>
              </div>
              <div className="grid gap-2">
                <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">运行时分层</p>
                <div className="grid gap-3">
                  {runtimeServices.map((service) => (
                    <article
                      className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-sm leading-6 text-slate-700"
                      key={service.name}
                    >
                      <div className="grid gap-1">
                        <strong className="text-slate-950">{serviceLabel(service.name)}</strong>
                        <span>{service.detail}</span>
                      </div>
                      <Badge variant={service.status === "healthy" ? "success" : "warning"}>
                        {serviceStatusLabel(service.status)}
                      </Badge>
                    </article>
                  ))}
                </div>
              </div>
            </div>
          ) : (
            <p className="m-0 text-sm leading-6 text-slate-600">正在加载健康摘要。</p>
          )}

          {health ? (
            <div className="mt-4 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700">
              当前共有 {warningServices} 个服务处于非健康状态。
            </div>
          ) : null}
        </SectionCard>

        <div className="grid gap-6">
          <SectionCard title="回滚基线" description="部署失败或 smoke 异常时，先看当前镜像、上一稳定版本和 smoke 结果。">
            {deploymentQuery.isError ? (
              <p className="m-0 text-sm leading-6 text-rose-700">回滚基线读取失败，请检查最近一次 deploy workflow。</p>
            ) : deployment ? (
              <div className="grid gap-3 text-sm leading-6 text-slate-700">
                <article className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3">
                  <strong className="block text-slate-950">当前镜像</strong>
                  <span>{deployment.current_image_tag || "未知"}</span>
                </article>
                <article className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3">
                  <strong className="block text-slate-950">上一稳定版本</strong>
                  <span>{deployment.previous_stable_image_tag || "未知"}</span>
                </article>
                <article className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3">
                  <strong className="block text-slate-950">最近 smoke</strong>
                  <span>{deploymentSmokeLabel(deployment.last_smoke_status)}</span>
                  <span className="block text-xs text-slate-500">{deployment.last_smoke_at || "暂无时间戳"}</span>
                </article>
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">正在加载回滚基线。</p>
            )}
          </SectionCard>

          <SectionCard title="错误分布" description="按来源和风险快速判断问题集中在哪一层。">
            {incidents.length > 0 ? (
              <div className="grid gap-4">
                <div className="grid gap-2">
                  <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">按来源</p>
                  <div className="grid gap-2 sm:grid-cols-2">
                    {Object.entries(groupedBySource).map(([source, count]) => (
                      <article
                        className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700"
                        key={source}
                      >
                        <strong className="block text-slate-950">{sourceLabel(source as IncidentSource)}</strong>
                        <span>{count} 条</span>
                      </article>
                    ))}
                  </div>
                </div>
                <div className="grid gap-2">
                  <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">按风险</p>
                  <div className="grid gap-2 sm:grid-cols-3">
                    {Object.entries(groupedBySeverity).map(([severity, count]) => (
                      <article
                        className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700"
                        key={severity}
                      >
                        <strong className="block text-slate-950">{severityLabel(severity as IncidentSeverity)}</strong>
                        <span>{count} 条</span>
                      </article>
                    ))}
                  </div>
                </div>
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">当前没有可统计的 incident，错误分布会在出现链路问题后自动更新。</p>
            )}
          </SectionCard>

          <SectionCard title="近期事件" description="如果链路失败，这里会优先展示 incident 摘要。">
            {incidentsQuery.isError ? (
              <p className="m-0 text-sm leading-6 text-rose-700">事件摘要加载失败，请稍后重试。</p>
            ) : incidents.length > 0 ? (
              <div className="grid gap-3">
                {incidents.map((incident) => (
                  <article
                    className="grid gap-2 rounded-2xl border border-slate-200 bg-white/80 px-4 py-3 text-sm leading-6 text-slate-700"
                    key={incident.incident_ref}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <strong className="text-slate-950">{incident.title}</strong>
                      <Badge variant={incident.status === "resolved" ? "success" : incident.severity === "high" ? "warning" : "info"}>
                        {incidentStatusLabel(incident.status)}
                      </Badge>
                    </div>
                    <div className="text-xs uppercase tracking-[0.18em] text-slate-500">
                      {sourceLabel(incident.source)} · 风险 {incidentSeverityLabel(incident.severity)} · {incident.incident_ref}
                    </div>
                    <p className="m-0">{incident.summary}</p>
                  </article>
                ))}
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">当前没有 incident 数据。</p>
            )}
          </SectionCard>
          <SectionCard title="推荐动作" description="这里给出的动作是排查起点，不替代具体 incident 分析。">
            <ul className="grid gap-3 pl-5 text-sm leading-6 text-slate-700">
              {recommendedActions.map((action) => (
                <li key={action}>{action}</li>
              ))}
            </ul>
          </SectionCard>
          <SectionCard title="降级与回滚建议" description="优先功能降级，其次再回退整套版本。">
            <ul className="grid gap-3 pl-5 text-sm leading-6 text-slate-700">
              {degradationActions.map((action) => (
                <li key={action}>{action}</li>
              ))}
            </ul>
          </SectionCard>
        </div>
      </section>
    </PageShell>
  );
}

function serviceLabel(name: string) {
  switch (name) {
    case "api":
      return "API";
    case "worker":
      return "Worker";
    case "storage":
      return "对象存储";
    case "database":
      return "数据库";
    case "ocr-runtime":
      return "OCR Runtime";
    case "link-fetcher":
      return "链接抓取器";
    case "search-projection":
      return "搜索投影";
    case "upload-chain":
      return "上传链路";
    default:
      return name;
  }
}
