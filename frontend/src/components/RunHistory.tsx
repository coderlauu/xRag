import { useEffect, useState } from "react";
import {
  ingestion,
  PHASE_FAILED_LABEL,
  RUN_STATUS_LABEL,
  type IngestionRun,
} from "../api/documents";

/** 入库任务历史。`SKIPPED` 用中性灰，**绝不能用警告色**——它不是失败（ui-spec §5）。 */
export function RunHistory({ docId, refreshKey }: { docId: number; refreshKey: number }) {
  const [runs, setRuns] = useState<IngestionRun[] | null>(null);

  useEffect(() => {
    let cancelled = false;
    ingestion
      .runs(docId)
      .then((page) => {
        if (!cancelled) {
          setRuns(page.items);
        }
      })
      .catch(() => setRuns([]));
    return () => {
      cancelled = true;
    };
  }, [docId, refreshKey]);

  if (runs === null) {
    return <p className="muted">加载处理记录…</p>;
  }
  if (runs.length === 0) {
    return <p className="muted">还没有处理记录。</p>;
  }

  return (
    <ul className="run-list">
      {runs.map((run) => (
        <li key={run.id}>
          <span className={`run-badge run-${run.status.toLowerCase()}`}>
            {RUN_STATUS_LABEL[run.status]}
          </span>
          <span className="run-meta">
            {run.triggerSource === "SCHEDULED" ? "定时同步" : "手动触发"}
            {run.chunkCount !== null ? ` · ${run.chunkCount} 个分块` : ""}
            {run.revision !== null ? ` · 第 ${run.revision} 版` : ""}
          </span>
          {run.status === "FAILED" ? (
            <span className="run-error">
              {run.phase ? `${PHASE_FAILED_LABEL[run.phase]}：` : ""}
              {run.errorMessage ?? "未知原因"}
            </span>
          ) : null}
        </li>
      ))}
    </ul>
  );
}
