import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  createIntentNode,
  deleteIntentSubtree,
  disableIntentSubtree,
  enableIntentNode,
  listBindableKnowledgeBases,
  readIntentTree,
  toQaUserMessage,
  updateIntentNode,
  type BindableKnowledgeBase,
  type EnableScope,
  type IntentNodeDraft,
  type IntentNodeType,
  type IntentTreeMutation,
  type IntentTreeNode,
  type IntentTreeSnapshot,
  type TreeOperation,
} from "../api/qa";
import { ApiError, type UserFacingMessage } from "../api/errors";
import { useViewportLayout } from "../hooks/useLayout";
import { CreateNodeDialog, DirtyGuardDialog, ImpactDialog } from "./intent-tree/dialogs";
import { NodeInspector } from "./intent-tree/NodeInspector";
import { RouteCanvas } from "./intent-tree/RouteCanvas";
import { matchesSearch } from "./intent-tree/labels";

function draftOf(node: IntentTreeNode): IntentNodeDraft {
  return {
    name: node.name,
    description: node.description,
    examples: [...node.examples],
    knowledgeBaseId: node.knowledgeBaseId,
    systemAction: node.systemAction,
    promptSnippet: node.promptSnippet,
    sortOrder: node.sortOrder,
  };
}

function sameDraft(left: IntentNodeDraft, right: IntentNodeDraft): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

/**
 * 意图树管理页（approved C — Route Canvas）。
 *
 * 这一页只有一个事实来源：**服务端返回的完整快照**。所有写命令都带 `expectedRevisionId`，
 * 成功后用返回的整套 `nodes` 原子替换本地快照，绝不把"我刚改的那个节点"就地 patch 上去
 * 当成服务端已接受——父子一致性和 effective enabled 都是服务端算的，本地 patch 只会造出
 * 一棵看起来对、实际和运行配置不一致的树。
 *
 * 冲突（409）时**保留本地表单**并刷新 current revision，让用户自己比较；自动覆盖任何一边
 * 都是替用户做决定。
 */
export function IntentTreePage() {
  const layout = useViewportLayout();

  const [snapshot, setSnapshot] = useState<IntentTreeSnapshot | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<UserFacingMessage | null>(null);
  const [loadedAt, setLoadedAt] = useState<Date | null>(null);

  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [draft, setDraft] = useState<IntentNodeDraft | null>(null);
  const [search, setSearch] = useState("");

  const [busy, setBusy] = useState(false);
  const [commandError, setCommandError] = useState<UserFacingMessage | null>(null);
  const [conflictRevisionNo, setConflictRevisionNo] = useState<number | null>(null);

  const [pendingSelection, setPendingSelection] = useState<string | null>(null);
  const [command, setCommand] = useState<{ nodeId: string; operation: TreeOperation } | null>(null);
  const [creatingUnder, setCreatingUnder] = useState<string | null>(null);

  const [knowledgeBases, setKnowledgeBases] = useState<BindableKnowledgeBase[]>([]);
  const [knowledgeBaseQuery, setKnowledgeBaseQuery] = useState("");
  const inspectorHeading = useRef<HTMLHeadingElement>(null);

  const selected = snapshot?.nodes.find((node) => node.nodeId === selectedId) ?? null;
  const dirty = selected !== null && draft !== null && !sameDraft(draft, draftOf(selected));

  /**
   * 重新拉取整树。它**不动本地表单**——刷新和冲突复核都需要用户自己比较服务端版本和
   * 手上的输入，替他覆盖任何一边都是替他做决定。
   *
   * 声明必须排在下面那个 `useEffect` 之前：依赖数组是在渲染期求值的，`const` 不提升。
   */
  const loadTree = useCallback(async () => {
    setLoading(true);
    try {
      const next = await readIntentTree();
      setSnapshot(next);
      setLoadedAt(new Date());
      setLoadError(null);
    } catch (cause) {
      setLoadError(toQaUserMessage(cause));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void loadTree();
  }, [loadTree]);

  /** 快照换版本后，选中的节点可能已经不在新树里；这时退回未选择状态而不是留一个悬空 id。 */
  useEffect(() => {
    if (selectedId !== null && snapshot && !snapshot.nodes.some((n) => n.nodeId === selectedId)) {
      setSelectedId(null);
      setDraft(null);
    }
  }, [snapshot, selectedId]);

  useEffect(() => {
    let cancelled = false;
    listBindableKnowledgeBases(knowledgeBaseQuery ? { query: knowledgeBaseQuery } : {})
      .then((page) => {
        if (!cancelled) setKnowledgeBases(page.items);
      })
      .catch(() => {
        // 选择器不可用不该把整棵树判成加载失败；绑定失效会在检查器里单独提示。
        if (!cancelled) setKnowledgeBases([]);
      });
    return () => {
      cancelled = true;
    };
  }, [knowledgeBaseQuery]);

  function applyMutation(mutation: IntentTreeMutation) {
    setSnapshot((prev) =>
      prev === null
        ? prev
        : {
            ...prev,
            revision: mutation.revision,
            nodes: mutation.nodes,
            summary: mutation.treeSummary,
          },
    );
    setLoadedAt(new Date());
  }

  async function runCommand(
    action: () => Promise<IntentTreeMutation>,
    onCommitted?: (mutation: IntentTreeMutation) => void,
  ) {
    setBusy(true);
    setCommandError(null);
    try {
      const mutation = await action();
      applyMutation(mutation);
      setConflictRevisionNo(null);
      onCommitted?.(mutation);
    } catch (cause) {
      setCommandError(toQaUserMessage(cause));
      if (cause instanceof ApiError && cause.code === "INTENT_TREE_REVISION_CONFLICT") {
        setConflictRevisionNo(Number(cause.extensions.currentRevisionNo ?? 0));
        await loadTree();
      }
    } finally {
      setBusy(false);
    }
  }

  function commitSelection(nodeId: string | null) {
    setSelectedId(nodeId);
    const node = nodeId === null ? null : snapshot?.nodes.find((item) => item.nodeId === nodeId);
    setDraft(node ? draftOf(node) : null);
    setCommandError(null);
    setConflictRevisionNo(null);
  }

  /** 切节点/返回列表前的 dirty guard；未保存内容不能被一次点击悄悄丢掉。 */
  function requestSelection(nodeId: string | null, focusInspector = false) {
    if (dirty && nodeId !== selectedId) {
      setPendingSelection(nodeId ?? "");
      return;
    }
    commitSelection(nodeId);
    if (focusInspector) inspectorHeading.current?.focus();
  }

  const visibleIds = useMemo(() => {
    if (!snapshot || !search.trim()) return null;
    const byId = new Map(snapshot.nodes.map((node) => [node.nodeId, node]));
    const nameOf = (id: number) =>
      knowledgeBases.find((base) => base.knowledgeBaseId === id)?.name;
    const visible = new Set<string>();
    for (const node of snapshot.nodes) {
      if (!matchesSearch(node, search, nameOf)) continue;
      // 命中要保留祖先上下文，否则搜索结果会变成一堆没有路径的同名叶子。
      let cursor: IntentTreeNode | undefined = node;
      while (cursor && !visible.has(cursor.nodeId)) {
        visible.add(cursor.nodeId);
        cursor = byId.get(cursor.parentId);
      }
    }
    return visible;
  }, [snapshot, search, knowledgeBases]);

  const statusText = loading
    ? "正在加载意图树…"
    : loadError
      ? "意图树加载失败"
      : busy
        ? "正在保存…"
        : conflictRevisionNo !== null
          ? "配置已被更新，请比较后重试"
          : dirty
            ? "有未保存修改"
            : snapshot
              ? `当前显示最新配置 · 版本 ${snapshot.revision.revisionNo}${
                  loadedAt ? ` · 最近刷新 ${loadedAt.toLocaleTimeString()}` : ""
                }`
              : "尚未加载";

  const detailView = layout === "narrow" && selectedId !== null;
  const searchEmpty = snapshot !== null && visibleIds !== null && visibleIds.size === 0;

  return (
    <section className="qa-page intent-page" aria-labelledby="intent-tree-title">
      <div className="page-header">
        <div>
          <h1 id="intent-tree-title">意图树</h1>
          <p>只有启用的叶子参与问题匹配；匹配描述会直接影响路由准确率。</p>
        </div>
        <div className="intent-header-actions">
          <input
            value={search}
            aria-label="搜索节点"
            placeholder="搜索名称、路径、匹配描述或知识库"
            onChange={(event) => setSearch(event.target.value)}
          />
          <button
            type="button"
            disabled={snapshot === null}
            onClick={() => setCreatingUnder(snapshot?.root.nodeId ?? null)}
          >
            新增根节点
          </button>
          <button type="button" onClick={() => void loadTree()}>
            刷新
          </button>
        </div>
      </div>

      <p className="intent-status" role="status">
        {statusText}
      </p>

      {loadError ? (
        <div className="state-block state-error">
          <p className="state-title">{loadError.title}</p>
          <p className="state-detail">上一次加载的树可能已经过期，这里不把它当成最新配置。</p>
          <button type="button" onClick={() => void loadTree()}>
            重试
          </button>
        </div>
      ) : null}

      {snapshot && snapshot.summary.nodeCount === 0 ? (
        <div className="state-block state-empty">
          <p className="state-title">还没有意图节点</p>
          <p className="state-detail">先创建一个业务域，再在下面添加绑定知识库的叶子。</p>
          <button
            className="primary"
            type="button"
            onClick={() => setCreatingUnder(snapshot.root.nodeId)}
          >
            创建第一个业务域
          </button>
        </div>
      ) : null}

      {snapshot && snapshot.summary.nodeCount > 0 ? (
        <div className={`intent-workspace intent-layout-${layout}`}>
          {/*
            窄屏用原生 `hidden` 而不是卸载画布：展开状态与滚动位置要在"返回节点列表"后
            原样还在。`hidden` 同时把子树移出无障碍树，不需要额外的 aria 处理。
          */}
          <div className="intent-canvas-column" hidden={detailView}>
            {searchEmpty ? (
              <div className="state-block state-empty">
                <p className="state-title">没有匹配「{search}」的节点</p>
                <p className="state-detail">换个关键词，或清除搜索回到完整配置。</p>
                <button type="button" onClick={() => setSearch("")}>
                  清除搜索
                </button>
              </div>
            ) : (
              <RouteCanvas
                snapshot={snapshot}
                selectedId={selectedId}
                visibleIds={visibleIds}
                knowledgeBaseName={(id) =>
                  knowledgeBases.find((base) => base.knowledgeBaseId === id)?.name
                }
                onSelect={(nodeId, focusInspector) => requestSelection(nodeId, focusInspector)}
                onAddChild={(parentId) => setCreatingUnder(parentId)}
                onDelete={(nodeId) => setCommand({ nodeId, operation: "DELETE_SUBTREE" })}
              />
            )}
          </div>

          {layout === "narrow" && !detailView ? null : (
            <aside className="intent-inspector" aria-label="节点检查器">
              <div className="intent-inspector-head">
                <h2 ref={inspectorHeading} tabIndex={-1}>
                  节点检查器
                </h2>
                {layout === "narrow" ? (
                  <button type="button" onClick={() => requestSelection(null)}>
                    返回节点列表
                  </button>
                ) : null}
              </div>
              <NodeInspector
                snapshot={snapshot}
                node={selected}
                draft={draft}
                dirty={dirty}
                busy={busy}
                error={commandError}
                conflictRevisionNo={conflictRevisionNo}
                knowledgeBases={knowledgeBases}
                knowledgeBaseQuery={knowledgeBaseQuery}
                onKnowledgeBaseQuery={setKnowledgeBaseQuery}
                onChange={setDraft}
                onDiscard={() => selected && commitSelection(selected.nodeId)}
                onSave={() => {
                  if (!selected || !draft) return;
                  void runCommand(
                    () => updateIntentNode(selected.nodeId, snapshot.revision.revisionId, draft),
                    (mutation) => {
                      const saved = mutation.nodes.find((node) => node.nodeId === selected.nodeId);
                      if (saved) setDraft(draftOf(saved));
                    },
                  );
                }}
                onEnable={(scope: EnableScope) => {
                  if (!selected) return;
                  if (scope === "CONFIGURED_SUBTREE") {
                    setCommand({
                      nodeId: selected.nodeId,
                      operation: "ENABLE_CONFIGURED_SUBTREE",
                    });
                    return;
                  }
                  void runCommand(() =>
                    enableIntentNode(selected.nodeId, snapshot.revision.revisionId, "NODE_ONLY"),
                  );
                }}
                onDisable={() =>
                  selected && setCommand({ nodeId: selected.nodeId, operation: "DISABLE_SUBTREE" })
                }
                onDelete={() =>
                  selected && setCommand({ nodeId: selected.nodeId, operation: "DELETE_SUBTREE" })
                }
              />
            </aside>
          )}
        </div>
      ) : null}

      {command && snapshot ? (
        <ImpactDialog
          node={snapshot.nodes.find((node) => node.nodeId === command.nodeId)!}
          operation={command.operation}
          expectedRevisionId={snapshot.revision.revisionId}
          busy={busy}
          commandError={commandError}
          onCancel={() => setCommand(null)}
          onConfirm={() => {
            const { nodeId, operation } = command;
            const revisionId = snapshot.revision.revisionId;
            void runCommand(
              () =>
                operation === "DISABLE_SUBTREE"
                  ? disableIntentSubtree(nodeId, revisionId)
                  : operation === "DELETE_SUBTREE"
                    ? deleteIntentSubtree(nodeId, revisionId)
                    : enableIntentNode(nodeId, revisionId, "CONFIGURED_SUBTREE"),
              () => {
                setCommand(null);
                if (operation === "DELETE_SUBTREE") commitSelection(null);
              },
            );
          }}
        />
      ) : null}

      {creatingUnder && snapshot ? (
        <CreateNodeDialog
          snapshot={snapshot}
          parentId={creatingUnder}
          busy={busy}
          error={commandError}
          onCancel={() => setCreatingUnder(null)}
          onCreate={(type: IntentNodeType, name, description) =>
            void runCommand(
              () =>
                createIntentNode({
                  expectedRevisionId: snapshot.revision.revisionId,
                  parentId: creatingUnder,
                  type,
                  draft: {
                    name,
                    description: description || null,
                    examples: [],
                    knowledgeBaseId: null,
                    systemAction: null,
                    promptSnippet: null,
                    sortOrder: 0,
                  },
                }),
              (mutation) => {
                setCreatingUnder(null);
                const created = mutation.nodes.find(
                  (node) => node.nodeId === mutation.changedNodeId,
                );
                setSelectedId(mutation.changedNodeId);
                setDraft(created ? draftOf(created) : null);
              },
            )
          }
        />
      ) : null}

      {pendingSelection !== null ? (
        <DirtyGuardDialog
          onStay={() => setPendingSelection(null)}
          onDiscard={() => {
            commitSelection(pendingSelection === "" ? null : pendingSelection);
            setPendingSelection(null);
          }}
        />
      ) : null}
    </section>
  );
}
