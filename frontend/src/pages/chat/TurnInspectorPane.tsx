import type { TurnResult } from "../../api/qa";
import { ACTION_LABEL, degradationLabel, scoreBand, TURN_STATUS_LABEL } from "./labels";

/** 稳定终态各自的一句话说明，用来解释"为什么这里没有子问题结果"。 */
const TERMINAL_NOTE: Record<string, string> = {
  WAITING_CLARIFICATION: "本轮在歧义处短路，尚未形成检索动作。",
  REJECTED_TOO_MANY_TOPICS: "已识别超过三个主题，未展示被截断的结果。",
  FAILED: "本轮理解失败，未形成检索动作。",
};

/**
 * 本轮处理面板（ui-spec §6）。
 *
 * 它展示的是**批准过的 3A 验收字段**，不是模型思维链：没有 Prompt、没有供应商 reason、
 * 没有 logicalCallId，分数也只以强/中/低区间出现。字段清单以 api.md §6 为准。
 */
export function TurnInspectorPane({ result }: { result: TurnResult | null }) {
  return (
    <div className="qa-inspector-body">
      <p className="muted">3A 验收信息 · 这里展示规范化问题与路由结果，不是模型思维链。</p>

      {!result ? (
        <div className="qa-empty">
          <strong>还没有本轮结果</strong>
          <span>发送问题后显示规范化问题与路由结果。</span>
        </div>
      ) : (
        <>
          <dl>
            <dt>终态</dt>
            <dd>{TURN_STATUS_LABEL[result.turn.status]}</dd>
            <dt>意图版本</dt>
            <dd>{result.turn.intentRevisionId ?? "本轮未固定版本"}</dd>
            {result.inspector ? (
              <>
                <dt>历史</dt>
                <dd>
                  {result.inspector.history.summaryUsed ? "使用摘要" : "未使用摘要"} · 近{" "}
                  {result.inspector.history.recentCompleteTurns} 轮完整历史
                </dd>
                <dt>问题改写</dt>
                <dd>
                  {result.inspector.rewrite.mode === "MODEL"
                    ? result.inspector.rewrite.rewrittenQuestion
                    : `使用原问题：${result.inspector.rewrite.rewrittenQuestion}`}
                </dd>
              </>
            ) : null}
          </dl>

          {TERMINAL_NOTE[result.turn.status] ? (
            <p className="qa-inspector-note">{TERMINAL_NOTE[result.turn.status]}</p>
          ) : null}

          {result.inspector?.subQuestions.map((question) => (
            <section className="qa-subquestion" key={question.subQuestionId}>
              <h3>子问题 {question.ordinal + 1}</h3>
              <p>{question.text}</p>
              {question.matches.length === 0 ? (
                <p className="muted">没有保留下来的意图候选。</p>
              ) : (
                question.matches.map((match) => (
                  <p className="qa-match" key={match.nodeId}>
                    <span className="qa-match-path">{match.fullPath}</span>
                    <span className="qa-tag">{scoreBand(match.score)}</span>
                  </p>
                ))
              )}
              {question.actions.map((action) => (
                <p className="qa-action" key={action.actionId}>
                  <span>{ACTION_LABEL[action.type]}</span>
                  {action.knowledgeBaseId !== null ? (
                    <span className="muted"> · 知识库 #{action.knowledgeBaseId}</span>
                  ) : null}
                  {action.systemAction ? (
                    <span className="muted"> · 系统场景 {action.systemAction}</span>
                  ) : null}
                </p>
              ))}
            </section>
          ))}

          {result.turn.degradations.length > 0 ? (
            <section className="qa-degradations">
              <h3>降级事实</h3>
              {result.turn.degradations.map((code) => (
                <p key={code}>{degradationLabel(code)}</p>
              ))}
            </section>
          ) : null}
        </>
      )}
    </div>
  );
}
