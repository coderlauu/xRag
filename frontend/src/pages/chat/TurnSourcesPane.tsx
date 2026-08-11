import { useState } from "react";
import { Link } from "react-router";
import type { TurnRetrieval, TurnSource } from "../../api/qa";
import { retrievalFailureLabel } from "./labels";

/**
 * 来源区（ui-spec §13.3）。
 *
 * 每条来源三项齐全：知识库名、文档名、`#位置`。**不展示分数、名次数字、命中通道、命中次数**
 * ——列表顺序本身就是排名，再标数字等于把不可比的跨通道分数偷偷带回界面（PRD §12.2）。
 *
 * 空态刻意分两种：`本轮没有召回任何来源` 与 `本轮未执行检索`。用一句"暂无数据"合并是错的
 * ——一个该改问法，一个该先回答澄清。
 */
export function TurnSourcesPane({ retrieval }: { retrieval: TurnRetrieval | null | undefined }) {
  if (!retrieval) {
    // turn 没进过 3B：歧义短路、超限等 3A 短路态。不是"没召回"。
    return (
      <div className="qa-empty">
        <strong>本轮未执行检索</strong>
        <span>本轮在理解阶段短路，没有产生检索动作。</span>
      </div>
    );
  }

  return (
    <section className="qa-sources" aria-labelledby="qa-sources-heading">
      <div className="qa-sources-head">
        <h4 id="qa-sources-heading">来源</h4>
        <span className="qa-sources-marks">
          {!retrieval.rerankApplied ? <RerankMark /> : null}
          {retrieval.partial ? <PartialMark /> : null}
        </span>
      </div>

      {retrieval.sources.length === 0 ? (
        <div className="qa-empty">
          <strong>本轮没有召回任何来源</strong>
          <span>知识库里没有匹配这个问题的内容。</span>
        </div>
      ) : (
        <ol className="qa-sources-list">
          {retrieval.sources.map((source) => (
            <SourceItem key={source.chunkId} source={source} />
          ))}
        </ol>
      )}

      {/* 失败项单列在来源列表下方，每项只有库名 + 归一化原因，不透传异常文本（ui-spec §13.6）。 */}
      {retrieval.failedTargets.length > 0 ? (
        <div className="qa-sources-failed">
          <h5>没查成的知识库</h5>
          <ul>
            {retrieval.failedTargets.map((target) => (
              <li key={`${target.knowledgeBaseId}-${target.failureCode}`}>
                <strong>{target.knowledgeBaseName ?? "未知知识库"}</strong>
                <span className="muted">{retrievalFailureLabel(target.failureCode)}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : null}
    </section>
  );
}

/**
 * 一条来源。
 *
 * 原文片段预览默认折叠（P-3B-03 确认的取舍：多一次点击换来列表可扫）。**预览尚无接口**
 * ——正文按 chunkId 另取，api.md 里还没有这个端点，所以这里只放展开位与说明，不假装有内容。
 */
function SourceItem({ source }: { source: TurnSource }) {
  const [expanded, setExpanded] = useState(false);
  const documentName = source.documentDeleted
    ? "文档已删除"
    : (source.documentName ?? "未知文档");
  const canOpenChunks =
    !source.documentDeleted && source.knowledgeBaseId !== null && source.documentId !== null;

  return (
    <li className="qa-source">
      <div className="qa-source-facts">
        <span className="qa-source-kb">{source.knowledgeBaseName ?? "未知知识库"}</span>
        <span className={`qa-source-doc${source.documentDeleted ? " qa-source-doc-deleted" : ""}`}>
          {documentName}
        </span>
        <span className="qa-source-pos">
          {source.chunkIndex === null ? "位置未知" : `#${source.chunkIndex}`}
        </span>
      </div>

      <div className="qa-source-actions">
        <button
          type="button"
          aria-expanded={expanded}
          onClick={() => setExpanded((open) => !open)}
        >
          {expanded ? "收起原文" : "展开原文"}
        </button>
        {canOpenChunks ? (
          <Link
            to={`/knowledge-bases/${source.knowledgeBaseId}/documents/${source.documentId}/chunks`}
          >
            打开分块页
          </Link>
        ) : (
          // 文档已删除时条目保留但禁用跳转（ui-spec §13.3）。用 disabled 的按钮而不是
          // 死链，点了没反应比跳到 404 更容易理解。
          <button type="button" disabled>
            打开分块页
          </button>
        )}
      </div>

      {expanded ? (
        <p className="qa-source-preview muted">
          原文片段预览尚未接入（正文按分块另取，接口待 api.md 冻结）。
        </p>
      ) : null}
    </li>
  );
}

/** `未精排` 标记。**不区分成因**：无 Key、供应商故障、Noop 降级对用户是同一件事。 */
export function RerankMark() {
  return (
    <span className="qa-tag qa-tag-warn" title="本轮重排不可用，结果按检索相关度排序。">
      未精排
    </span>
  );
}

/** `结果不完整` 标记。依据是服务端的 `partial`，不是条数（PRD §12.4）。 */
export function PartialMark() {
  return (
    <span className="qa-tag qa-tag-warn" title="部分知识库本轮没有查成。">
      结果不完整
    </span>
  );
}
