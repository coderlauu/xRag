import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import type { OpsGovernanceNotice, OpsReleaseGuardRiskLevel } from "@xrag/shared-types";
import { Badge, Button } from "@xrag/ui";
import { fetchOpsOverview } from "../../../lib/api";

interface GovernanceNoticeStripProps {
  target: OpsGovernanceNotice["target"];
}

export function GovernanceNoticeStrip({ target }: GovernanceNoticeStripProps) {
  const overviewQuery = useQuery({
    queryKey: ["ops", "overview"],
    queryFn: () => fetchOpsOverview(),
    refetchInterval: 30_000,
    refetchOnWindowFocus: false
  });

  const notices = overviewQuery.data?.notices.filter((notice) => notice.target === target) ?? [];

  if (overviewQuery.isError || notices.length === 0) {
    return null;
  }

  return (
    <section className="rounded-[28px] border border-amber-200/80 bg-[linear-gradient(135deg,rgba(255,251,235,0.94),rgba(255,255,255,0.98),rgba(254,243,199,0.78))] px-5 py-4 shadow-sm">
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="grid gap-1">
          <p className="m-0 text-xs uppercase tracking-[0.18em] text-amber-700">治理提示</p>
          <p className="m-0 text-sm leading-6 text-slate-800">
            {surfaceTitle(target)}当前受治理信号影响。这里仅提示风险与跳转，不会自动拦截当前操作。
          </p>
        </div>
        <Button asChild size="sm" variant="outline">
          <Link to="/ops">查看运维主板</Link>
        </Button>
      </div>

      <div className="mt-4 grid gap-3">
        {notices.map((notice) => (
          <article
            className="grid gap-2 rounded-2xl border border-white/80 bg-white/80 px-4 py-3 text-sm leading-6 text-slate-700"
            key={`${target}-${notice.code}-${notice.title}`}
          >
            <div className="flex flex-wrap items-center gap-2">
              <LevelBadge level={notice.level} />
              <span className="text-xs uppercase tracking-[0.18em] text-slate-500">{noticeCodeLabel(notice.code)}</span>
            </div>
            <strong className="text-slate-950">{notice.title}</strong>
            <p className="m-0">{notice.summary}</p>
          </article>
        ))}
      </div>
    </section>
  );
}

function LevelBadge({ level }: { level: OpsReleaseGuardRiskLevel }) {
  if (level === "critical") {
    return <Badge className="border-rose-200 bg-rose-100 text-rose-800">严重</Badge>;
  }

  if (level === "warning") {
    return <Badge variant="warning">注意</Badge>;
  }

  return <Badge variant="success">正常</Badge>;
}

function surfaceTitle(target: OpsGovernanceNotice["target"]) {
  switch (target) {
    case "ask":
      return "Ask Workspace ";
    case "search":
      return "搜索页 ";
    case "detail":
      return "详情页 ";
  }
}

function noticeCodeLabel(code: OpsGovernanceNotice["code"]) {
  switch (code) {
    case "no_ready_documents":
      return "当前无可引用语料";
    case "indexing_backlog":
      return "索引存在积压";
    case "indexing_failed":
      return "存在失败索引";
    case "stale_corpus":
      return "语料已过期";
    case "inspect_indexing_backlog":
      return "检查索引积压";
    case "inspect_failed_documents":
      return "检查失败文档";
    case "run_backfill_indexing_dry_run":
      return "运行索引回填演练";
    case "inspect_quality_regression":
      return "检查质量回退";
    case "inspect_incident_cluster":
      return "检查 incident cluster";
    case "verify_latest_deployment":
      return "核对最近部署";
    case "rollback_to_previous_stable":
      return "核对上一稳定版本";
    case "monitor_without_action":
      return "继续观察";
    case "none":
      return "无阻塞";
  }
}
