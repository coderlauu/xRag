import { useCallback, useEffect, useState } from "react";
import { api } from "../api/client";
import { ErrorState } from "../components/ErrorState";
import { Loading } from "../components/Loading";

interface HealthResponse {
  status: string;
}

/**
 * 后端存活探测。这是脚手架时期就有的页面，保留下来作为诊断入口——依赖没起来时
 * 能一眼看出是后端不通还是业务出错，省掉每次都开 Network 面板。
 *
 * 它同时是 api 客户端层与加载/错误态组件的第一个真实使用者。
 */
export function DiagnosticsPage() {
  const [health, setHealth] = useState<HealthResponse | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    api
      .get<HealthResponse>("/health", { signal: controller.signal })
      .then(setHealth)
      .catch((cause: unknown) => {
        if (cause instanceof DOMException && cause.name === "AbortError") {
          return;
        }
        setError(cause);
      })
      .finally(() => setLoading(false));
    return () => controller.abort();
  }, []);

  useEffect(load, [load]);

  return (
    <section>
      <h1>诊断</h1>
      {loading ? <Loading label="正在探测后端…" /> : null}
      {!loading && error ? <ErrorState error={error} onRetry={load} /> : null}
      {!loading && !error ? (
        <p>
          后端存活状态：<code>{health?.status ?? "未知"}</code>
        </p>
      ) : null}
    </section>
  );
}
