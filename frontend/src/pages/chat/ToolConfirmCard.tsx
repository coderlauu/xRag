import type { TurnTool } from "../../api/qa";

/**
 * 工具确认卡（ui-spec §13.7.2）。只给**有副作用且待确认**的工具。
 *
 * 它必须在消息区，与 3A 的歧义引导卡同一位置、同一心智——确认需要用户动作，藏进默认关闭的
 * 抽屉就等于没有确认（§13.1 不变量 2）。
 *
 * 三条容易写错的规则，都在这里正面兑现：
 * <ul>
 *   <li><b>不抢焦点</b>，用 {@code role="status"} 播报。危险确认的默认焦点不该落在危险按钮上。</li>
 *   <li><b>Tab 先到「取消」</b>——所以 DOM 顺序里取消在前。视觉顺序靠 CSS 处理，不靠
 *       {@code tabIndex} 硬掰。</li>
 *   <li><b>Esc 不等于取消</b>。误触 Esc 不该丢掉一份已抽取好的参数，所以这里不挂任何键盘处理器
 *       ——Esc 只关抽屉，那是 Drawer 自己的事。</li>
 * </ul>
 *
 * 参数**不可就地编辑**（P-3B-02 已确认）：要改就取消，用自然语言重说。可编辑参数会引出
 * "编辑后还要不要按 Schema 校验、校验失败怎么回卡片"一整条分支，而它不在冻结的语义里。
 */
export function ToolConfirmCard({
  tool,
  busy,
  onConfirm,
  onCancel,
}: {
  tool: TurnTool;
  busy: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <section className="qa-tool-card" role="status" aria-labelledby={`qa-tool-title-${tool.toolCallId}`}>
      <h4 id={`qa-tool-title-${tool.toolCallId}`}>
        需要你确认：{tool.displayName ?? tool.toolName}
        <span className="qa-tag qa-tag-danger">有副作用</span>
      </h4>

      {/*
        这句话来自工具白名单配置，**不是模型生成的**（架构 §21.2）。否则模型既决定调什么工具、
        又决定怎么向用户描述它，确认就成了走过场。
      */}
      {tool.description ? <p className="qa-tool-desc">{tool.description}</p> : null}

      <div className="qa-tool-params">
        <p className="muted qa-tool-params-head">由模型从对话中抽取</p>
        <dl>
          {tool.parameters.map((parameter) => (
            <div className="qa-tool-param" key={parameter.name}>
              <dt>
                {parameter.name}
                {parameter.required ? <span className="qa-required">必填</span> : null}
              </dt>
              {/* 值按纯文本渲染。它来自模型抽取，和工具返回一样不可信。 */}
              <dd>{parameter.value ?? <span className="muted">未提供</span>}</dd>
            </div>
          ))}
        </dl>
      </div>

      {/* DOM 顺序：取消在前，所以 Tab 先到它。375px 下 CSS 让它堆在上方。 */}
      <div className="qa-tool-actions">
        <button type="button" className="secondary" disabled={busy} onClick={onCancel}>
          取消
        </button>
        <button type="button" className="danger" disabled={busy} onClick={onConfirm}>
          {busy ? "执行中…" : "确认执行"}
        </button>
      </div>
    </section>
  );
}
