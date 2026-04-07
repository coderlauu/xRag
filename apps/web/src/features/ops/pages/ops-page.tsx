import { useQuery } from "@tanstack/react-query";
import { Badge, PageShell, SectionCard, StatCard } from "@xrag/ui";
import { fetchOpsHealthSummary, getLatestDeployment, listOpsIncidents } from "../../../lib/api";
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

export function OpsPage() {
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
  const incidents = incidentsQuery.data?.items || [];
  const deployment = deploymentQuery.data;
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

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.1fr)_minmax(320px,0.9fr)]">
        <SectionCard title="服务健康" description="面向内部使用的服务连通性摘要。">
          {healthQuery.isError ? (
            <p className="m-0 text-sm leading-6 text-rose-700">健康摘要加载失败，请检查 API 与依赖服务。</p>
          ) : health ? (
            <div className="grid gap-3">
              {health.services.map((service) => (
                <article
                  className="flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700"
                  key={service.name}
                >
                  <div className="grid gap-1">
                    <strong className="text-slate-950">{service.name}</strong>
                    <span>{service.detail}</span>
                  </div>
                  <Badge variant={service.status === "healthy" ? "success" : service.status === "warning" ? "warning" : "info"}>
                    {serviceStatusLabel(service.status)}
                  </Badge>
                </article>
              ))}
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
                      {incident.source} · 风险 {incidentSeverityLabel(incident.severity)} · {incident.incident_ref}
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
        </div>
      </section>
    </PageShell>
  );
}
