import { useEffect, useState } from "react";

/**
 * 两个 Phase 3A 页面共用的三档断点（问答 ui-spec §10、意图树 ui-spec §13 用的是同一组）：
 *
 * - `wide`（>1024px）：主视图与侧栏并排常驻；
 * - `medium`（721~1024px）：主列表常驻窄栏，次要面板降级为抽屉；
 * - `narrow`（≤720px）：单列，侧面板是全高覆盖层或独立详情页。
 */
export type ViewportLayout = "wide" | "medium" | "narrow";

export function layoutFor(width: number): ViewportLayout {
  if (width <= 720) return "narrow";
  if (width <= 1024) return "medium";
  return "wide";
}

/**
 * 用 `window.innerWidth` 而不是 `matchMedia`：抽屉需要 Esc、焦点返回和"打开时不渲染
 * 常驻栏"，这些是 JS 行为，纯 CSS 断点做不到；两处断点定义分家反而会漂移。
 */
export function useViewportLayout(): ViewportLayout {
  const [layout, setLayout] = useState<ViewportLayout>(() => layoutFor(window.innerWidth));

  useEffect(() => {
    const onResize = () => setLayout(layoutFor(window.innerWidth));
    // 首帧再同步一次：`useState` 初值取自渲染前的宽度，测试里 setTestViewport 可能在其后。
    onResize();
    window.addEventListener("resize", onResize);
    return () => window.removeEventListener("resize", onResize);
  }, []);

  return layout;
}
