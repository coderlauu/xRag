/**
 * 全站统一的加载态。每个页面各写一套的结果是转圈的位置、文案、留白高度都不一样，
 * 页面之间切换时会有明显的跳动。
 */
export function Loading({ label = "加载中…" }: { label?: string }) {
  return (
    <div className="state-block" role="status" aria-live="polite">
      <span className="spinner" aria-hidden="true" />
      <p>{label}</p>
    </div>
  );
}
