import type {
  BindableKnowledgeBase,
  EnableScope,
  IntentNodeDraft,
  IntentTreeNode,
  IntentTreeSnapshot,
} from "../../api/qa";
import type { UserFacingMessage } from "../../api/errors";
import { NODE_TYPE_LABEL, SYSTEM_ACTIONS, SYSTEM_ACTION_LABEL, incompleteLeaf, nodeStateLabel } from "./labels";

interface NodeInspectorProps {
  snapshot: IntentTreeSnapshot;
  node: IntentTreeNode | null;
  draft: IntentNodeDraft | null;
  dirty: boolean;
  busy: boolean;
  error: UserFacingMessage | null;
  /** 冲突时保留本地表单，只把服务端当前版本号摆出来供比较。 */
  conflictRevisionNo: number | null;
  knowledgeBases: BindableKnowledgeBase[];
  knowledgeBaseQuery: string;
  onKnowledgeBaseQuery: (value: string) => void;
  onChange: (draft: IntentNodeDraft) => void;
  onSave: () => void;
  onDiscard: () => void;
  onEnable: (scope: EnableScope) => void;
  onDisable: () => void;
  onDelete: () => void;
}

/**
 * 节点检查器（ui-spec §7、§9、§10）。
 *
 * 两条硬规则：
 *
 * 1. **启停与删除不混进普通保存**——它们各有级联语义和确认，塞进同一个"保存"里，一个
 *    拼错的描述就会连带触发整棵子树的停用。
 * 2. **前端不自行判定"可以启用"**——`incompleteLeaf()` 只用来提示，按钮照样可点，裁决
 *    权在服务端；否则界面和服务端会各有一套一致性规则，迟早对不上。
 */
export function NodeInspector({
  snapshot,
  node,
  draft,
  dirty,
  busy,
  error,
  conflictRevisionNo,
  knowledgeBases,
  knowledgeBaseQuery,
  onKnowledgeBaseQuery,
  onChange,
  onSave,
  onDiscard,
  onEnable,
  onDisable,
  onDelete,
}: NodeInspectorProps) {
  if (!node || !draft) {
    return (
      <div className="intent-inspector-body">
        <div className="qa-empty">
          <strong>未选择节点</strong>
          <span>
            当前共 {snapshot.summary.nodeCount} 个节点，{snapshot.summary.enabledLeafCount} 个可匹配叶子
            {snapshot.summary.incompleteLeafCount > 0
              ? `，${snapshot.summary.incompleteLeafCount} 个叶子配置不完整`
              : ""}
            。
          </span>
          <span>在画布中选择一个节点开始编辑。</span>
        </div>
      </div>
    );
  }

  const leaf = node.type !== "GROUP";
  const parent = snapshot.nodes.find((candidate) => candidate.nodeId === node.parentId);
  const boundMissing =
    node.type === "KB" &&
    draft.knowledgeBaseId !== null &&
    !knowledgeBases.some((base) => base.knowledgeBaseId === draft.knowledgeBaseId);

  const patch = (changes: Partial<IntentNodeDraft>) => onChange({ ...draft, ...changes });

  return (
    <div className="intent-inspector-body">
      <p className="intent-path">{node.fullPath}</p>

      <section>
        <h3>基本信息</h3>
        <label>
          <span>名称</span>
          <input
            value={draft.name}
            maxLength={80}
            aria-label="节点名称"
            onChange={(event) => patch({ name: event.target.value })}
          />
        </label>
        <p className="intent-facts">
          <span className="qa-tag">{NODE_TYPE_LABEL[node.type]}</span>
          <span className="qa-tag">{nodeStateLabel(node)}</span>
          {incompleteLeaf(node) ? <span className="qa-tag">配置不完整</span> : null}
          <span className="muted">父节点：{parent?.name ?? snapshot.root.name}（3A 不支持换父）</span>
        </p>
      </section>

      <section>
        <h3>匹配规则</h3>
        <p className="field-hint">
          写清什么问题应该来这里，以及它和兄弟节点的区别。知识库简介不会自动代替此字段。
        </p>
        <label>
          <span>匹配描述{leaf ? "（必填）" : "（组织节点仅供运营说明）"}</span>
          <textarea
            value={draft.description ?? ""}
            rows={4}
            maxLength={1000}
            aria-label="匹配描述"
            onChange={(event) => patch({ description: event.target.value })}
          />
        </label>

        {leaf ? (
          <div className="intent-examples">
            <span>正向示例（至少一条，建议 3~5 条）</span>
            {draft.examples.map((example, index) => (
              <div className="intent-example-row" key={index}>
                <input
                  value={example}
                  maxLength={200}
                  aria-label={`正向示例 ${index + 1}`}
                  onChange={(event) =>
                    patch({
                      examples: draft.examples.map((item, position) =>
                        position === index ? event.target.value : item,
                      ),
                    })
                  }
                />
                <button
                  type="button"
                  aria-label={`删除正向示例 ${index + 1}`}
                  onClick={() =>
                    patch({ examples: draft.examples.filter((_, position) => position !== index) })
                  }
                >
                  删除
                </button>
              </div>
            ))}
            <button type="button" onClick={() => patch({ examples: [...draft.examples, ""] })}>
              新增示例
            </button>
            <p className="field-hint">示例里不要写秘密或真实个人信息；系统不会自动脱敏。</p>
          </div>
        ) : null}
      </section>

      {node.type === "KB" ? (
        <section>
          <h3>路由绑定</h3>
          {boundMissing ? (
            <p className="inline-error" role="alert">
              <strong>绑定的知识库不可用，请重新选择后再启用。</strong>
            </p>
          ) : null}
          <label>
            <span>搜索知识库</span>
            <input
              value={knowledgeBaseQuery}
              aria-label="搜索知识库"
              onChange={(event) => onKnowledgeBaseQuery(event.target.value)}
            />
          </label>
          <div className="intent-kb-list" role="radiogroup" aria-label="绑定知识库">
            {knowledgeBases.length === 0 ? (
              <p className="muted">没有可绑定的知识库。</p>
            ) : (
              knowledgeBases.map((base) => (
                <label key={base.knowledgeBaseId}>
                  <input
                    type="radio"
                    name="intent-kb"
                    checked={draft.knowledgeBaseId === base.knowledgeBaseId}
                    onChange={() => patch({ knowledgeBaseId: base.knowledgeBaseId })}
                  />
                  <span>{base.name}</span>
                  <span className="muted">
                    #{base.knowledgeBaseId} · {base.chunkCount} 个分块
                    {base.enabled ? "" : " · 已停用"}
                  </span>
                </label>
              ))
            )}
          </div>
        </section>
      ) : null}

      {node.type === "SYSTEM" ? (
        <section>
          <h3>路由绑定</h3>
          <label>
            <span>系统场景</span>
            <select
              value={draft.systemAction ?? ""}
              aria-label="系统场景"
              onChange={(event) =>
                patch({ systemAction: event.target.value === "" ? null : (event.target.value as never) })
              }
            >
              <option value="">未选择</option>
              {SYSTEM_ACTIONS.map((action) => (
                <option key={action} value={action}>
                  {SYSTEM_ACTION_LABEL[action]}
                </option>
              ))}
            </select>
          </label>
          <p className="field-hint">3A 只接受这个受控闭集，不能输入任意可执行内容。</p>
        </section>
      ) : null}

      {leaf ? (
        <section>
          <h3>回答附加规则（后续生成阶段使用）</h3>
          <p className="field-hint">它只会追加到系统规则，不能替换安全规则；3A 保存但尚不执行。</p>
          <textarea
            value={draft.promptSnippet ?? ""}
            rows={3}
            maxLength={1000}
            aria-label="回答附加规则"
            onChange={(event) => patch({ promptSnippet: event.target.value })}
          />
        </section>
      ) : null}

      <section>
        <h3>状态与影响范围</h3>
        {node.enabled ? (
          <button type="button" disabled={busy} onClick={onDisable}>
            停用整个子树
          </button>
        ) : (
          <div className="intent-enable-actions">
            <button type="button" disabled={busy} onClick={() => onEnable("NODE_ONLY")}>
              仅启用当前节点
            </button>
            {node.type === "GROUP" ? (
              <button type="button" disabled={busy} onClick={() => onEnable("CONFIGURED_SUBTREE")}>
                启用整个已配置完整的子树
              </button>
            ) : null}
          </div>
        )}
        <p className="field-hint">
          启停不包含在普通保存里；父节点只能整棵子树处理，不提供“只处理父节点”。
        </p>
      </section>

      <section className="intent-danger">
        <h3>危险操作</h3>
        <button className="danger" type="button" disabled={busy} onClick={onDelete}>
          删除整个子树
        </button>
        <p className="field-hint">逻辑删除，当前页面不提供恢复。</p>
      </section>

      {conflictRevisionNo !== null ? (
        <p className="inline-error" role="alert">
          <strong>该节点已被更新（服务端当前版本 {conflictRevisionNo}）。</strong>
          <span> 上面的输入已原样保留，请比较后再决定是否重新保存。</span>
        </p>
      ) : null}

      {error ? (
        <p className="inline-error" role="alert">
          <strong>{error.title}</strong>
          {error.detail ? <span> {error.detail}</span> : null}
        </p>
      ) : null}

      <div className="intent-inspector-actions">
        <button type="button" disabled={!dirty || busy} onClick={onDiscard}>
          取消修改
        </button>
        <button className="primary" type="button" disabled={!dirty || busy} onClick={onSave}>
          {busy ? "保存中…" : "保存节点"}
        </button>
      </div>
    </div>
  );
}
