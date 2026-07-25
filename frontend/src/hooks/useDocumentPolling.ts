import { useEffect, useState } from "react";
import { ingestion, type DocumentDetail } from "../api/documents";

/**
 * `RUNNING` 期间按 2s 轮询文档详情，到 `SUCCESS` / `FAILED` 后停止。
 *
 * <p>两件必须做对的事：
 * 1. **组件卸载时清理定时器**，否则用户切走页面后请求还在继续发，而且 setState 会打到
 *    已卸载的组件上。
 * 2. **请求也要能被取消**（AbortController）。只清 timer 不取消在途请求的话，卸载瞬间
 *    发出的那一次仍会回来。客户端层会把 AbortError 原样抛出、不包装成网络错误。
 *
 * @param docIds 当前处于 RUNNING 的文档 id；为空时不产生任何请求
 */
export function useDocumentPolling(docIds: number[], onSettled: () => void) {
  const key = docIds.join(",");
  const [progress, setProgress] = useState<Record<number, DocumentDetail>>({});

  useEffect(() => {
    if (key === "") {
      setProgress({});
      return;
    }
    const ids = key.split(",").map(Number);
    const controller = new AbortController();
    let stopped = false;

    const tick = () => {
      Promise.all(ids.map((id) => ingestion.detail(id, controller.signal)))
        .then((details) => {
          if (stopped) {
            return;
          }
          setProgress(Object.fromEntries(details.map((d) => [d.id, d])));
          if (details.some((d) => d.status !== "RUNNING")) {
            onSettled();
          }
        })
        .catch(() => {
          // 轮询失败不打扰用户：下一次 tick 会重试，真正的错误会在用户主动操作时暴露
        });
    };

    tick();
    const timer = window.setInterval(tick, 2000);
    return () => {
      stopped = true;
      window.clearInterval(timer);
      controller.abort();
    };
  }, [key, onSettled]);

  return progress;
}
