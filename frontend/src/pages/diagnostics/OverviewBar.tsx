import type { DiagnosticsOverview } from "../../api/diagnostics";
import { BREAKER_LABEL, BREAKER_MARK, CAPABILITY_LABEL, formatTime } from "./labels";

export type SummaryDrilldown = "candidates" | "openBreakers" | "failover" | "failed";

interface Props {
  overview: DiagnosticsOverview;
  activeDrilldown: SummaryDrilldown | null;
  onDrilldown: (target: SummaryDrilldown) => void;
}

/**
 * ui-spec §5 的调度总览。四个摘要数字都可下钻，且**口径固定**：
 * 前两个过滤本页视图，后两个必须以 `failoverOccurred=true` 和
 * `outcome=FAILED&outcome=STREAM_FAILED` 重新请求后端，不能拿当前已加载的一页近似过滤。
 */
export function OverviewBar({ overview, activeDrilldown, onDrilldown }: Props) {
  const { summary, retention, deployments, breakers } = overview;
  const dropped = summary.diagnosticsDroppedSinceStart;
  const writeFailures = summary.diagnosticWriteFailuresSinceStart;
  const diagnosticsIncomplete = (dropped ?? 0) > 0 || (writeFailures ?? 0) > 0;

  const visibleDeployments =
    activeDrilldown === "candidates"
      ? deployments.filter((d) => d.enabled && d.configurationStatus === "CONFIGURED")
      : deployments;
  const visibleBreakers =
    activeDrilldown === "openBreakers" ? breakers.filter((b) => b.state === "OPEN") : breakers;

  return (
    <section className="trace-overview">
      <div className="trace-summary" role="group" aria-label="调度总览摘要">
        <SummaryTile
          label="当前可用候选"
          value={summary.eligibleCandidates}
          active={activeDrilldown === "candidates"}
          onClick={() => onDrilldown("candidates")}
        />
        <SummaryTile
          label="OPEN 熔断"
          value={summary.openBreakers}
          active={activeDrilldown === "openBreakers"}
          onClick={() => onDrilldown("openBreakers")}
        />
        <SummaryTile
          label="24h 发生切换"
          value={summary.failovers24h}
          active={activeDrilldown === "failover"}
          onClick={() => onDrilldown("failover")}
        />
        <SummaryTile
          label="24h 失败/流中断"
          value={summary.failedOrInterrupted24h}
          active={activeDrilldown === "failed"}
          onClick={() => onDrilldown("failed")}
        />
      </div>

      {diagnosticsIncomplete ? (
        <p className="trace-warning" role="status">
          部分调用诊断可能缺失：进程内已丢弃 {dropped ?? "?"} 条、写入失败 {writeFailures ?? "?"} 条。
          这不代表模型调用本身失败。
        </p>
      ) : null}
      {dropped === null || writeFailures === null ? (
        <p className="trace-warning" role="status">
          诊断计数读取失败，无法判断是否有记录缺失。
        </p>
      ) : null}

      <p className="muted trace-retention">
        保留 {retention.maxAgeDays} 天或最近 {retention.maxLogicalCalls.toLocaleString()} 次调用
        {retention.oldestAvailableAt
          ? `；当前最早记录 ${formatTime(retention.oldestAvailableAt)}`
          : "；当前没有已保留的调用"}
      </p>

      <details className="trace-catalog" open={activeDrilldown === "candidates" || activeDrilldown === "openBreakers"}>
        <summary>Provider / 模型能力与熔断状态</summary>

        {visibleDeployments.length === 0 ? (
          <div className="empty-state">
            <p className="state-title">尚未配置模型候选</p>
            <p className="state-detail">
              通过配置文件登记非敏感模型信息，并用环境变量注入密钥。
            </p>
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>Provider / 模型</th>
                <th>能力</th>
                <th>等级</th>
                <th>状态</th>
              </tr>
            </thead>
            <tbody>
              {visibleDeployments.map((deployment) => (
                <tr key={deployment.deploymentId}>
                  <td>
                    <div>{deployment.providerDisplayName ?? deployment.providerId}</div>
                    <div className="muted" title={deployment.modelIdentity}>
                      {deployment.deploymentId}
                    </div>
                  </td>
                  <td>{deployment.capabilities.map((c) => CAPABILITY_LABEL[c]).join("、")}</td>
                  <td>{deployment.tiers.join("、") || "—"}</td>
                  <td>
                    {!deployment.enabled ? (
                      <span className="pill pill-muted">○ 配置禁用</span>
                    ) : deployment.configurationStatus !== "CONFIGURED" ? (
                      <span className="pill pill-dashed">○ 未配置</span>
                    ) : (
                      <span className="pill pill-ok">● 已启用</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <h3>熔断状态</h3>
        {visibleBreakers.length === 0 ? (
          <div className="empty-state">
            <p className="state-title">
              {activeDrilldown === "openBreakers" ? "当前没有熔断中的候选" : "尚无熔断记录"}
            </p>
            <p className="state-detail">所有已启用故障域目前允许调用。</p>
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>故障域</th>
                <th>状态</th>
                <th>允许调用</th>
                <th>最近变化</th>
                <th className="numeric">近期成功/失败</th>
              </tr>
            </thead>
            <tbody>
              {visibleBreakers.map((breaker) => (
                <tr key={breaker.breakerKey}>
                  <td>
                    {breaker.providerId} / {breaker.deploymentId}
                    <div className="muted">{CAPABILITY_LABEL[breaker.capability]}</div>
                  </td>
                  <td>
                    <span className={`pill pill-${breaker.state.toLowerCase()}`}>
                      {BREAKER_MARK[breaker.state]} {BREAKER_LABEL[breaker.state]}
                    </span>
                    {breaker.state === "HALF_OPEN" && breaker.halfOpenPermissionInUse ? (
                      <div className="muted">探测许可占用中</div>
                    ) : null}
                  </td>
                  <td>{breaker.allowsCall ? "是" : "否"}</td>
                  <td>
                    {formatTime(breaker.lastChangedAt)}
                    {breaker.lastChangeReason ? (
                      <div className="muted">{breaker.lastChangeReason}</div>
                    ) : null}
                  </td>
                  <td className="numeric">
                    {breaker.recent.success} / {breaker.recent.failure}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </details>
    </section>
  );
}

function SummaryTile({
  label,
  value,
  active,
  onClick,
}: {
  label: string;
  value: number | null;
  active: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      className={`trace-tile${active ? " active" : ""}`}
      // 两个 span 拼出来的可访问名读起来是"2 24h 失败/流中断"，读屏会把数字和标签黏在一起，
      // 所以显式给一个完整句子。
      aria-label={`${label}：${value ?? "读取失败"}，点击下钻`}
      aria-pressed={active}
      onClick={onClick}
    >
      <span className="trace-tile-value">{value ?? "读取失败"}</span>
      <span className="trace-tile-label">{label}</span>
    </button>
  );
}
