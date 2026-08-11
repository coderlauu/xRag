import type { TurnResult } from "../../api/qa";
import { TurnInspectorPane } from "./TurnInspectorPane";
import { TurnSourcesPane } from "./TurnSourcesPane";

/**
 * 本轮处理抽屉的两个分组（ui-spec §13.2）。
 *
 * 顺序固定：「本轮结果」在上、「处理详情」在下。用户关心前者，验收者才关心后者——在 C 布局下
 * 抽屉一打开先看到的必须是来源，而不是阶段条。
 *
 * `3A 验收信息` 副标签从面板标题**下移到「处理详情」分组标题**上。这个标签带着一条删除承诺
 * （ui-spec §9.2：正式交付后删除）；继续挂在整个面板上，3B 的来源就会被读成验收脚手架，
 * Phase 3 收尾时被连带删掉——而来源恰恰是要长期留下的东西。
 *
 * 工具区属工单 13，本组件暂不渲染它。
 */
export function TurnResultPane({ result }: { result: TurnResult | null }) {
  return (
    <div className="qa-turn-result">
      <section className="qa-drawer-group" aria-labelledby="qa-group-result">
        <h3 id="qa-group-result">本轮结果</h3>
        <TurnSourcesPane retrieval={result?.retrieval} />
      </section>

      <section className="qa-drawer-group" aria-labelledby="qa-group-detail">
        <h3 id="qa-group-detail">
          处理详情 <strong className="qa-tag">3A 验收信息</strong>
        </h3>
        <TurnInspectorPane result={result} />
      </section>
    </div>
  );
}
