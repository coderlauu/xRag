import { useQuery } from "@tanstack/react-query";
import { Badge, PageShell, SectionCard, StatCard } from "@xrag/ui";
import { fetchOpsHealthSummary, getLatestDeployment, listOpsIncidents } from "../../../lib/api";

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

  return (
    <PageShell eyebrow="运维" title="Ops Board" description="服务健康、近期事件和最新部署状态的一体化只读视图。">
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard
          label="健康服务数"
          value={String(health?.services.length || 0)}
          hint={health?.generated_at ? `更新时间 ${health.generated_at}` : "等待接口返回"}
        />
        <StatCard label="近期事件数" value={String(incidents.length)} hint="来自只读 incidents 接口" />
        <StatCard
          label="最近部署"
          value={deployment?.current_image_tag || "未知"}
          hint={deployment?.last_smoke_status || "unknown"}
        />
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.1fr)_minmax(320px,0.9fr)]">
        <SectionCard title="服务健康" description="面向内部使用的服务连通性摘要。">
          {health ? (
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
                    {service.status}
                  </Badge>
                </article>
              ))}
            </div>
          ) : (
            <p className="m-0 text-sm leading-6 text-slate-600">正在加载健康摘要。</p>
          )}
        </SectionCard>

        <div className="grid gap-6">
          <SectionCard title="近期事件" description="如果链路失败，这里会优先展示 incident 摘要。">
            {incidents.length > 0 ? (
              <div className="grid gap-3">
                {incidents.map((incident) => (
                  <article
                    className="grid gap-2 rounded-2xl border border-slate-200 bg-white/80 px-4 py-3 text-sm leading-6 text-slate-700"
                    key={incident.incident_ref}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <strong className="text-slate-950">{incident.title}</strong>
                      <Badge variant={incident.status === "resolved" ? "success" : incident.severity === "high" ? "warning" : "info"}>
                        {incident.status}
                      </Badge>
                    </div>
                    <div className="text-xs uppercase tracking-[0.18em] text-slate-500">
                      {incident.source} · {incident.severity} · {incident.incident_ref}
                    </div>
                    <p className="m-0">{incident.summary}</p>
                  </article>
                ))}
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">当前没有 incident 数据。</p>
            )}
          </SectionCard>

          <SectionCard title="部署摘要" description="读取最新镜像 tag 和 smoke 状态。">
            {deployment ? (
              <div className="grid gap-2 text-sm leading-6 text-slate-700">
                <article>当前镜像：{deployment.current_image_tag || "未知"}</article>
                <article>上一稳定版本：{deployment.previous_stable_image_tag || "未知"}</article>
                <article>最近 smoke：{deployment.last_smoke_status}</article>
                <article>最近 smoke 时间：{deployment.last_smoke_at || "未知"}</article>
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">正在加载部署摘要。</p>
            )}
          </SectionCard>
        </div>
      </section>
    </PageShell>
  );
}
