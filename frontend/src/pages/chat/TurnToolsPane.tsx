import { useState } from "react";
import type { TurnTool } from "../../api/qa";
import { toolFailureLabel, toolStatusLabel } from "./labels";

/**
 * 抽屉里的工具区（ui-spec §13.7.5）。
 *
 * 本轮所有工具调用按发起顺序列出。空态是 `本轮没有调用工具`——与"工具不可用"不是同一句：
 * 一个是压根没要调工具，一个是要调但清单里没有，后者是配置问题、用户无法自助。
 */
export function TurnToolsPane({ tools }: { tools: TurnTool[] }) {
  if (tools.length === 0) {
    return (
      <div className="qa-empty">
        <strong>本轮没有调用工具</strong>
        <span>这一轮不需要外部操作。</span>
      </div>
    );
  }

  return (
    <section className="qa-tools" aria-labelledby="qa-tools-heading">
      <h4 id="qa-tools-heading">工具</h4>
      <ol className="qa-tools-list">
        {tools.map((tool) => (
          <ToolItem key={tool.toolCallId} tool={tool} />
        ))}
      </ol>
    </section>
  );
}

function ToolItem({ tool }: { tool: TurnTool }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <li className="qa-tool">
      <div className="qa-tool-head">
        <span className="qa-tool-name">{tool.displayName ?? tool.toolName}</span>
        <ToolStatusTag status={tool.status} />
        {tool.sideEffect ? <span className="qa-tag qa-tag-danger">有副作用</span> : null}
        {/* 耗时成功和失败都显示——"失败但花了 9 秒"和"立刻失败"是不同的线索。 */}
        {tool.latencyMs !== null ? (
          <span className="qa-tool-latency muted">{tool.latencyMs} ms</span>
        ) : null}
      </div>

      {tool.failureCode ? (
        <p className="qa-tool-failure">{toolFailureLabel(tool.failureCode)}</p>
      ) : null}

      {tool.parameters.length > 0 ? (
        <>
          <button type="button" aria-expanded={expanded} onClick={() => setExpanded((open) => !open)}>
            {expanded ? "收起参数" : "展开参数"}
          </button>
          {expanded ? (
            <dl className="qa-tool-params">
              {tool.parameters.map((parameter) => (
                <div className="qa-tool-param" key={parameter.name}>
                  <dt>{parameter.name}</dt>
                  <dd>{parameter.value ?? <span className="muted">未提供</span>}</dd>
                </div>
              ))}
            </dl>
          ) : null}
        </>
      ) : null}

      {/*
        工具返回是**不可信内容**，一律按纯文本渲染（ui-spec §13.7.3）：不解析 HTML、不渲染
        Markdown 链接与图片、不产生可点击目标。JSX 的文本插值天然转义，这里靠的就是它——
        任何时候想改成 dangerouslySetInnerHTML 或接一个 Markdown 渲染器，先回去读那一节。
      */}
      {tool.resultText ? <p className="qa-tool-result">{tool.resultText}</p> : null}
    </li>
  );
}

/** 状态标签。文字是状态的载体，颜色只是附加——去掉颜色后仍要可区分（ui-spec §13.10）。 */
export function ToolStatusTag({ status }: { status: string }) {
  const tone =
    status === "FAILED" || status === "UNAVAILABLE" || status === "ABORTED"
      ? " qa-tag-danger"
      : status === "PENDING_CONFIRM" || status === "RUNNING"
        ? " qa-tag-warn"
        : "";
  return <span className={`qa-tag${tone}`}>{toolStatusLabel(status)}</span>;
}
