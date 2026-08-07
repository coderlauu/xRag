import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useSearchParams } from "react-router";
import {
  fetchCallDetail,
  fetchCalls,
  fetchOverview,
  fetchReadiness,
} from "../api/diagnostics";
import type {
  CallFilters,
  CallOutcome,
  DiagnosticCall,
  DiagnosticCallDetail,
  DiagnosticsOverview,
  Readiness,
} from "../api/diagnostics";
import type { Page } from "../api/types";
import { ErrorState } from "../components/ErrorState";
import { Loading } from "../components/Loading";
import { CallDetailPanel } from "./diagnostics/CallDetailPanel";
import { OverviewBar } from "./diagnostics/OverviewBar";
import type { SummaryDrilldown } from "./diagnostics/OverviewBar";
import {
  CAPABILITY_LABEL,
  OUTCOME_LABEL,
  OUTCOME_MARK,
  formatDuration,
  formatTime,
  freshnessLabel,
  processSummary,
  routeSummary,
  shorten,
} from "./diagnostics/labels";

const OVERVIEW_REFRESH_MS = 30_000;
const OUTCOMES: CallOutcome[] = ["SUCCEEDED", "DEGRADED", "FAILED", "CANCELED", "STREAM_FAILED"];

function ignoreAbort(cause: unknown): boolean {
  return cause instanceof DOMException && cause.name === "AbortError";
}

/**
 * A — Trace Console（ui-spec §10）。左侧最近调用是主视觉，右侧固定详情，
 * Provider/熔断压缩成顶部摘要条——核心任务是"从一条失败或变慢的调用开始排查"。
 *
 * 三个数据源并行加载：readiness、overview、calls。**只有 overview 轮询**（可见时 30s，
 * 标签页隐藏暂停）；调用列表只在首次进入、筛选变化或手动刷新时重新加载，否则列表会在
 * 用户阅读时自己跳动。
 */
export function DiagnosticsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const filters = useMemo(() => readFilters(searchParams), [searchParams]);

  const [readiness, setReadiness] = useState<Readiness | null>(null);
  const [overview, setOverview] = useState<DiagnosticsOverview | null>(null);
  const [overviewError, setOverviewError] = useState<unknown>(null);
  const [overviewLoadedAt, setOverviewLoadedAt] = useState<number | null>(null);
  const [now, setNow] = useState(() => Date.now());

  const [calls, setCalls] = useState<Page<DiagnosticCall> | null>(null);
  const [callsLoading, setCallsLoading] = useState(true);
  const [callsError, setCallsError] = useState<unknown>(null);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [detail, setDetail] = useState<DiagnosticCallDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<unknown>(null);
  const [copied, setCopied] = useState(false);
  const [drilldown, setDrilldown] = useState<SummaryDrilldown | null>(null);

  const overviewInFlight = useRef(false);
  const selectedRowRef = useRef<HTMLTableRowElement | null>(null);

  const loadOverview = useCallback(async () => {
    // 不重叠：上一轮还没回来就跳过这一次，否则慢响应会把请求越堆越多。
    if (overviewInFlight.current) {
      return;
    }
    overviewInFlight.current = true;
    try {
      const [next, ready] = await Promise.allSettled([fetchOverview(), fetchReadiness()]);
      if (next.status === "fulfilled") {
        setOverview(next.value);
        setOverviewLoadedAt(Date.now());
        setOverviewError(null);
      } else if (!ignoreAbort(next.reason)) {
        // 刷新失败保留旧数据并标记可能过期，不清空成整页错误（ui-spec §8.1）。
        setOverviewError(next.reason);
      }
      setReadiness(ready.status === "fulfilled" ? ready.value : null);
    } finally {
      overviewInFlight.current = false;
    }
  }, []);

  const loadCalls = useCallback(
    (signal?: AbortSignal) => {
      setCallsLoading(true);
      setCallsError(null);
      fetchCalls(filters, signal)
        .then(setCalls)
        .catch((cause: unknown) => {
          if (!ignoreAbort(cause)) setCallsError(cause);
        })
        .finally(() => setCallsLoading(false));
    },
    [filters],
  );

  useEffect(() => {
    void loadOverview();
  }, [loadOverview]);

  useEffect(() => {
    const controller = new AbortController();
    loadCalls(controller.signal);
    return () => controller.abort();
  }, [loadCalls]);

  // 页面可见时每 30s 只刷 overview；隐藏时暂停（ui-spec §4.3）。
  useEffect(() => {
    const timer = window.setInterval(() => {
      if (document.visibilityState === "visible") {
        void loadOverview();
      }
    }, OVERVIEW_REFRESH_MS);
    return () => window.clearInterval(timer);
  }, [loadOverview]);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000);
    return () => window.clearInterval(timer);
  }, []);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    const controller = new AbortController();
    setDetailLoading(true);
    setDetailError(null);
    fetchCallDetail(selectedId, controller.signal)
      .then(setDetail)
      .catch((cause: unknown) => {
        if (!ignoreAbort(cause)) setDetailError(cause);
      })
      .finally(() => setDetailLoading(false));
    return () => controller.abort();
  }, [selectedId]);

  const updateFilters = useCallback(
    (patch: Partial<CallFilters>) => {
      // 筛选变化后页码回到第一页；选中的 call ID 只留在页面状态，不进 URL。
      setSearchParams(writeFilters({ ...filters, page: 1, ...patch }), { replace: false });
    },
    [filters, setSearchParams],
  );

  const onDrilldown = useCallback(
    (target: SummaryDrilldown) => {
      const next = drilldown === target ? null : target;
      setDrilldown(next);
      if (target === "failover") {
        updateFilters(
          next ? { failoverOccurred: true, outcomes: [] } : { failoverOccurred: undefined },
        );
      } else if (target === "failed") {
        updateFilters(
          next
            ? { outcomes: ["FAILED", "STREAM_FAILED"], failoverOccurred: undefined }
            : { outcomes: [] },
        );
      }
    },
    [drilldown, updateFilters],
  );

  const copy = useCallback((value: string) => {
    void navigator.clipboard?.writeText(value);
    setCopied(true);
    window.setTimeout(() => setCopied(false), 2000);
  }, []);

  const closeDetail = useCallback(() => {
    setSelectedId(null);
    selectedRowRef.current?.focus();
  }, []);

  const stale = overviewLoadedAt !== null && now - overviewLoadedAt > 5 * 60_000;
  const hasFilters =
    filters.capability !== undefined ||
    filters.tier !== undefined ||
    filters.outcomes.length > 0 ||
    filters.providerId !== undefined ||
    filters.requestId !== undefined ||
    filters.failoverOccurred !== undefined;

  return (
    <section className="trace-console">
      <header className="page-header">
        <div>
          <h1>模型调度诊断</h1>
          <p className="muted">只记录路由与调用事实，不保存 Prompt、文档正文或模型输出</p>
        </div>
        <div className="trace-header-actions">
          <span className={stale ? "trace-stale" : "muted"}>
            {freshnessLabel(overviewLoadedAt, now)}
          </span>
          <button
            type="button"
            onClick={() => {
              void loadOverview();
              loadCalls();
            }}
            disabled={callsLoading}
          >
            {callsLoading ? "刷新中…" : "刷新"}
          </button>
        </div>
      </header>

      <p className={`trace-health ${readiness?.status === "ready" ? "ok" : "down"}`} role="status">
        {readiness === null
          ? "正在探测后端…"
          : readiness.status === "ready"
            ? "后端可用"
            : `后端不可用：${Object.entries(readiness.checks)
                .filter(([, value]) => value !== "ok")
                .map(([key]) => key)
                .join("、")}`}
      </p>

      {stale ? (
        <p className="trace-warning">诊断数据可能已过期，请刷新后再判断当前状态。</p>
      ) : null}
      {overviewError ? (
        <p className="trace-warning">总览刷新失败，下方显示的是上次成功加载的数据。</p>
      ) : null}

      {overview ? (
        <OverviewBar overview={overview} activeDrilldown={drilldown} onDrilldown={onDrilldown} />
      ) : overviewError ? (
        <ErrorState error={overviewError} onRetry={() => void loadOverview()} />
      ) : (
        <Loading label="正在加载调度总览…" />
      )}

      <div className={`trace-body${selectedId ? " with-detail" : ""}`}>
        <div className="trace-list">
          <Filters filters={filters} onChange={updateFilters} />

          {callsLoading ? <Loading label="正在加载最近调用…" /> : null}
          {!callsLoading && callsError ? (
            <ErrorState error={callsError} onRetry={() => loadCalls()} />
          ) : null}
          {!callsLoading && !callsError && calls ? (
            calls.items.length === 0 ? (
              <div className="empty-state">
                <p className="state-title">
                  {hasFilters ? "没有符合条件的调用" : "暂无模型调用"}
                </p>
                <p className="state-detail">
                  {hasFilters
                    ? `当前筛选：${filterSummary(filters)}`
                    : "当 Phase 2 能力被调用后，这里会出现路由和 attempt 记录。"}
                </p>
                {hasFilters ? (
                  <button type="button" onClick={() => setSearchParams({})}>
                    清除筛选
                  </button>
                ) : null}
              </div>
            ) : (
              <>
                <div className="table-scroll">
                  <table className="data-table trace-table">
                    <thead>
                      <tr>
                        <th>时间</th>
                        <th>结果</th>
                        <th>能力 / 等级</th>
                        <th>实际路由</th>
                        <th>过程</th>
                        <th className="numeric">首个有效块</th>
                        <th className="numeric">总耗时</th>
                        <th>请求 ID</th>
                      </tr>
                    </thead>
                    <tbody>
                      {calls.items.map((call) => (
                        <tr
                          key={call.id}
                          tabIndex={0}
                          role="button"
                          aria-label={`查看调用 ${call.requestId} 的详情：${OUTCOME_LABEL[call.outcome]}`}
                          aria-pressed={selectedId === call.id}
                          className={selectedId === call.id ? "selected" : undefined}
                          ref={selectedId === call.id ? selectedRowRef : undefined}
                          onClick={() => setSelectedId(call.id)}
                          onKeyDown={(event) => {
                            if (event.key === "Enter" || event.key === " ") {
                              event.preventDefault();
                              setSelectedId(call.id);
                            }
                          }}
                        >
                          <td>{formatTime(call.startedAt)}</td>
                          <td>
                            <span className={`pill outcome-${call.outcome.toLowerCase()}`}>
                              {OUTCOME_MARK[call.outcome]} {OUTCOME_LABEL[call.outcome]}
                            </span>
                          </td>
                          <td>
                            {CAPABILITY_LABEL[call.capability]}
                            {call.serviceTier ? ` · ${call.serviceTier}` : ""}
                          </td>
                          <td>{routeSummary(call)}</td>
                          <td>{processSummary(call)}</td>
                          <td className="numeric">
                            {call.capability === "STREAM_CHAT"
                              ? formatDuration(call.firstMeaningfulChunkMs)
                              : "—"}
                          </td>
                          <td className="numeric">{formatDuration(call.totalDurationMs)}</td>
                          <td>
                            <code title={call.requestId}>{shorten(call.requestId)}</code>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="trace-pagination">
                  <button
                    type="button"
                    disabled={filters.page <= 1}
                    onClick={() => setSearchParams(writeFilters({ ...filters, page: filters.page - 1 }))}
                  >
                    上一页
                  </button>
                  <span className="muted">
                    第 {calls.page} 页 · 共 {calls.total} 条
                  </span>
                  <button
                    type="button"
                    disabled={filters.page * calls.size >= calls.total}
                    onClick={() => setSearchParams(writeFilters({ ...filters, page: filters.page + 1 }))}
                  >
                    下一页
                  </button>
                </div>
              </>
            )
          ) : null}
        </div>

        {selectedId ? (
          <CallDetailPanel
            detail={detail}
            loading={detailLoading}
            error={detailError}
            onClose={closeDetail}
            onRetry={() => setSelectedId(selectedId)}
            onCopy={copy}
          />
        ) : null}
      </div>

      {copied ? (
        <p className="trace-copied" role="status">
          已复制
        </p>
      ) : null}
    </section>
  );
}

function Filters({
  filters,
  onChange,
}: {
  filters: CallFilters;
  onChange: (patch: Partial<CallFilters>) => void;
}) {
  return (
    <div className="filter-bar">
      <label>
        时间范围
        <select
          value={filters.window}
          onChange={(event) => onChange({ window: event.target.value as CallFilters["window"] })}
        >
          <option value="1h">最近 1 小时</option>
          <option value="24h">最近 24 小时</option>
          <option value="7d">最近 7 天</option>
        </select>
      </label>
      <label>
        能力
        <select
          value={filters.capability ?? ""}
          onChange={(event) => onChange({ capability: event.target.value || undefined })}
        >
          <option value="">全部</option>
          {(Object.keys(CAPABILITY_LABEL) as (keyof typeof CAPABILITY_LABEL)[]).map((key) => (
            <option key={key} value={key}>
              {CAPABILITY_LABEL[key]}
            </option>
          ))}
        </select>
      </label>
      <label>
        服务等级
        <select
          value={filters.tier ?? ""}
          onChange={(event) => onChange({ tier: event.target.value || undefined })}
        >
          <option value="">全部</option>
          <option value="ECONOMY">Economy</option>
          <option value="BALANCED">Balanced</option>
          <option value="QUALITY">Quality</option>
        </select>
      </label>
      <label>
        最终结果
        <select
          value={filters.outcomes.length === 1 ? filters.outcomes[0] : ""}
          onChange={(event) =>
            onChange({ outcomes: event.target.value ? [event.target.value as CallOutcome] : [] })
          }
        >
          <option value="">{filters.outcomes.length > 1 ? "失败 + 流中断" : "全部"}</option>
          {OUTCOMES.map((outcome) => (
            <option key={outcome} value={outcome}>
              {OUTCOME_LABEL[outcome]}
            </option>
          ))}
        </select>
      </label>
      <label>
        Provider
        <input
          type="text"
          value={filters.providerId ?? ""}
          placeholder="曾发出真实 attempt"
          onChange={(event) => onChange({ providerId: event.target.value || undefined })}
        />
      </label>
      <label>
        请求 ID
        <input
          type="text"
          value={filters.requestId ?? ""}
          placeholder="内部或脱敏 ID，精确匹配"
          onChange={(event) => onChange({ requestId: event.target.value || undefined })}
        />
      </label>
    </div>
  );
}

function filterSummary(filters: CallFilters): string {
  const parts: string[] = [];
  if (filters.capability) parts.push(CAPABILITY_LABEL[filters.capability as never] ?? filters.capability);
  if (filters.outcomes.length > 0) parts.push(filters.outcomes.map((o) => OUTCOME_LABEL[o]).join(" + "));
  if (filters.tier) parts.push(filters.tier);
  if (filters.providerId) parts.push(`Provider ${filters.providerId}`);
  if (filters.requestId) parts.push(`请求 ID ${filters.requestId}`);
  if (filters.failoverOccurred !== undefined) {
    parts.push(filters.failoverOccurred ? "发生过切换" : "未发生切换");
  }
  parts.push({ "1h": "最近 1 小时", "24h": "最近 24 小时", "7d": "最近 7 天" }[filters.window]);
  return parts.join("、");
}

function readFilters(params: URLSearchParams): CallFilters {
  const window = params.get("window");
  const failover = params.get("failoverOccurred");
  return {
    window: window === "1h" || window === "7d" ? window : "24h",
    capability: params.get("capability") ?? undefined,
    tier: params.get("tier") ?? undefined,
    outcomes: params.getAll("outcome") as CallOutcome[],
    providerId: params.get("providerId") ?? undefined,
    requestId: params.get("requestId") ?? undefined,
    failoverOccurred: failover === null ? undefined : failover === "true",
    page: Math.max(1, Number(params.get("page") ?? "1") || 1),
  };
}

function writeFilters(filters: CallFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.window !== "24h") params.set("window", filters.window);
  if (filters.capability) params.set("capability", filters.capability);
  if (filters.tier) params.set("tier", filters.tier);
  if (filters.providerId) params.set("providerId", filters.providerId);
  if (filters.requestId) params.set("requestId", filters.requestId);
  if (filters.failoverOccurred !== undefined) {
    params.set("failoverOccurred", String(filters.failoverOccurred));
  }
  for (const outcome of filters.outcomes) params.append("outcome", outcome);
  if (filters.page > 1) params.set("page", String(filters.page));
  return params;
}
