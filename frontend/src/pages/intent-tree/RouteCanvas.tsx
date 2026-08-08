import { useRef, useState } from "react";
import type { IntentTreeNode, IntentTreeSnapshot } from "../../api/qa";
import { NODE_TYPE_LABEL, incompleteLeaf, nodeStateLabel } from "./labels";

interface RouteCanvasProps {
  snapshot: IntentTreeSnapshot;
  selectedId: string | null;
  /** 搜索命中及其祖先；`null` 表示没有搜索，全部可见。 */
  visibleIds: Set<string> | null;
  knowledgeBaseName: (id: number) => string | undefined;
  /** `focusInspector` 为真时把焦点送进检查器——从节点菜单点“在检查器中编辑”走的就是这条。 */
  onSelect: (nodeId: string, focusInspector?: boolean) => void;
  onAddChild: (parentId: string) => void;
  onDelete: (nodeId: string) => void;
}

const ZOOM_STEP = 0.15;
const ZOOM_RANGE = { min: 0.6, max: 1.6 };

/**
 * C — Route Canvas 的主视图。
 *
 * **画布只是布局，不是事实来源**：节点位置由 `parentId` + `sortOrder` 推导，不保存坐标，
 * 因此这里没有拖拽换父（3A 明确不做）。缩放是 CSS `scale`，平移用容器自身的滚动——自造
 * 无限画布会同时废掉键盘和滚轮的既有语义，而 ui-spec §13 要求键盘能走完整棵树。
 *
 * DOM 嵌套与树的层级一致，所以 `tree/treeitem/group` 语义是天然的，不用 `aria-owns` 拼。
 */
export function RouteCanvas({
  snapshot,
  selectedId,
  visibleIds,
  knowledgeBaseName,
  onSelect,
  onAddChild,
  onDelete,
}: RouteCanvasProps) {
  const [zoom, setZoom] = useState(1);
  const [collapsed, setCollapsed] = useState<Set<string>>(new Set());
  const [focusId, setFocusId] = useState<string | null>(null);
  const [menuId, setMenuId] = useState<string | null>(null);
  const items = useRef(new Map<string, HTMLDivElement>());

  const childrenOf = (parentId: string) =>
    snapshot.nodes
      .filter((node) => node.parentId === parentId)
      .filter((node) => visibleIds === null || visibleIds.has(node.nodeId))
      .sort((left, right) => left.sortOrder - right.sortOrder || left.name.localeCompare(right.name));

  // 搜索期间强制展开，让命中保留祖先上下文；`collapsed` 本身不动，清除搜索后展开状态自动回来。
  const expanded = (nodeId: string) => visibleIds !== null || !collapsed.has(nodeId);

  const roots = childrenOf(snapshot.root.nodeId);

  /** 当前可见节点的先序序列，就是上下键的移动顺序。 */
  function visibleOrder(): IntentTreeNode[] {
    const ordered: IntentTreeNode[] = [];
    const walk = (parentId: string) => {
      for (const node of childrenOf(parentId)) {
        ordered.push(node);
        if (expanded(node.nodeId)) walk(node.nodeId);
      }
    };
    walk(snapshot.root.nodeId);
    return ordered;
  }

  function focusNode(nodeId: string | undefined) {
    if (!nodeId) return;
    setFocusId(nodeId);
    items.current.get(nodeId)?.focus();
  }

  function toggleCollapsed(nodeId: string, collapse: boolean) {
    setCollapsed((prev) => {
      const next = new Set(prev);
      if (collapse) next.add(nodeId);
      else next.delete(nodeId);
      return next;
    });
  }

  function onKeyDown(event: React.KeyboardEvent, node: IntentTreeNode) {
    // treeitem 是嵌套的，不拦住冒泡的话同一次 Enter 会被祖先再处理一遍，
    // 结果"选中子节点"当场被"选中父节点"覆盖掉。
    event.stopPropagation();
    const ordered = visibleOrder();
    const index = ordered.findIndex((candidate) => candidate.nodeId === node.nodeId);
    const hasChildren = childrenOf(node.nodeId).length > 0;

    if (event.key === "ArrowDown") {
      event.preventDefault();
      focusNode(ordered[index + 1]?.nodeId);
    } else if (event.key === "ArrowUp") {
      event.preventDefault();
      focusNode(ordered[index - 1]?.nodeId);
    } else if (event.key === "ArrowRight") {
      event.preventDefault();
      if (hasChildren && !expanded(node.nodeId)) toggleCollapsed(node.nodeId, false);
      else if (hasChildren) focusNode(childrenOf(node.nodeId)[0]?.nodeId);
    } else if (event.key === "ArrowLeft") {
      event.preventDefault();
      if (hasChildren && expanded(node.nodeId)) toggleCollapsed(node.nodeId, true);
      else focusNode(ordered.find((candidate) => candidate.nodeId === node.parentId)?.nodeId);
    } else if (event.key === "Enter" || event.key === " ") {
      event.preventDefault();
      onSelect(node.nodeId);
    }
  }

  function renderNode(node: IntentTreeNode, level: number) {
    const children = childrenOf(node.nodeId);
    const open = expanded(node.nodeId);
    const bound = node.knowledgeBaseId === null ? null : knowledgeBaseName(node.knowledgeBaseId);

    return (
      <div
        className="intent-branch"
        key={node.nodeId}
        role="treeitem"
        aria-level={level}
        aria-selected={selectedId === node.nodeId}
        aria-expanded={children.length > 0 ? open : undefined}
        aria-label={`${node.name} · ${NODE_TYPE_LABEL[node.type]} · ${nodeStateLabel(node)}`}
        tabIndex={focusId === node.nodeId || (focusId === null && node === roots[0]) ? 0 : -1}
        ref={(element) => {
          if (element) items.current.set(node.nodeId, element);
          else items.current.delete(node.nodeId);
        }}
        onKeyDown={(event) => onKeyDown(event, node)}
        onClick={(event) => {
          event.stopPropagation();
          setFocusId(node.nodeId);
          onSelect(node.nodeId);
        }}
      >
        <div className={`intent-node${selectedId === node.nodeId ? " selected" : ""}`}>
          <div className="intent-node-title">
            <strong>{node.name}</strong>
            <span className="qa-tag">{NODE_TYPE_LABEL[node.type]}</span>
            <span className="qa-tag">{nodeStateLabel(node)}</span>
            {incompleteLeaf(node) ? <span className="qa-tag">配置不完整</span> : null}
          </div>
          <p className="intent-node-meta">
            {node.type === "GROUP"
              ? `${node.childCount} 个子节点 · ${node.enabledLeafCount} 个可匹配叶子`
              : node.type === "KB"
                ? `绑定知识库：${bound ?? (node.knowledgeBaseId === null ? "未绑定" : `#${node.knowledgeBaseId}`)}`
                : `系统场景：${node.systemAction ?? "未选择"}`}
          </p>
          <div className="intent-node-menu">
            <button
              type="button"
              aria-expanded={menuId === node.nodeId}
              onClick={(event) => {
                event.stopPropagation();
                setMenuId((open2) => (open2 === node.nodeId ? null : node.nodeId));
              }}
            >
              节点操作
            </button>
            {menuId === node.nodeId ? (
              <div className="qa-menu-items" role="group" aria-label={`${node.name} 的操作`}>
                {node.type === "GROUP" ? (
                  <button
                    type="button"
                    onClick={(event) => {
                      event.stopPropagation();
                      setMenuId(null);
                      onAddChild(node.nodeId);
                    }}
                  >
                    新增子节点
                  </button>
                ) : null}
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    setMenuId(null);
                    onSelect(node.nodeId, true);
                  }}
                >
                  在检查器中编辑
                </button>
                <button
                  className="danger"
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    setMenuId(null);
                    onDelete(node.nodeId);
                  }}
                >
                  删除整个子树
                </button>
              </div>
            ) : null}
          </div>
        </div>

        {children.length > 0 && open ? (
          <div className="intent-children" role="group">
            {children.map((child) => renderNode(child, level + 1))}
          </div>
        ) : null}
      </div>
    );
  }

  return (
    <div className="intent-canvas-shell">
      <div className="intent-canvas-toolbar">
        <button
          type="button"
          onClick={() => setZoom((value) => Math.max(ZOOM_RANGE.min, value - ZOOM_STEP))}
        >
          缩小
        </button>
        <span aria-live="polite">{Math.round(zoom * 100)}%</span>
        <button
          type="button"
          onClick={() => setZoom((value) => Math.min(ZOOM_RANGE.max, value + ZOOM_STEP))}
        >
          放大
        </button>
        <button type="button" onClick={() => setZoom(1)}>
          重置缩放
        </button>
      </div>

      <div className="intent-canvas-viewport">
        <div className="intent-canvas-stage" style={{ transform: `scale(${zoom})` }}>
          {roots.length === 0 ? null : (
            <div className="intent-branch intent-root-branch">
              <div className="intent-node intent-root-node">
                <div className="intent-node-title">
                  <strong>{snapshot.root.name}</strong>
                  <span className="qa-tag">系统保留根</span>
                </div>
                <p className="intent-node-meta">
                  {snapshot.summary.nodeCount} 个节点 · {snapshot.summary.enabledLeafCount} 个可匹配叶子
                </p>
              </div>
              <div className="intent-children" role="tree" aria-label="意图树">
                {roots.map((node) => renderNode(node, 1))}
              </div>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
