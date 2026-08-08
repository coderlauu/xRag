import type { IntentNodeType, IntentTreeNode, SystemActionId } from "../../api/qa";

export const NODE_TYPE_LABEL: Record<IntentNodeType, string> = {
  GROUP: "组织",
  KB: "KB",
  SYSTEM: "SYSTEM",
};

/** 受控系统场景闭集（后端 `SystemAction`）。界面不允许自由输入。 */
export const SYSTEM_ACTION_LABEL: Record<SystemActionId, string> = {
  GREETING: "打招呼",
  ASSISTANT_IDENTITY: "助手身份说明",
  CAPABILITY_HELP: "能力帮助",
};

export const SYSTEM_ACTIONS: SystemActionId[] = [
  "GREETING",
  "ASSISTANT_IDENTITY",
  "CAPABILITY_HELP",
];

/**
 * 节点状态（ui-spec §5.2）。
 *
 * `祖先已停用` 必须与节点自身 `enabled=false` 区分：两者的恢复方式不同——前者要先启用
 * 祖先，后者启用自己就够。压成同一个"已停用"会让运营者反复点没有效果的按钮。
 */
export function nodeStateLabel(node: IntentTreeNode): string {
  if (node.enabled && node.effectiveEnabled) return "已启用";
  if (node.enabled) return "祖先已停用";
  return "已停用";
}

/** 叶子是否配置完整。前端只用它做**提示**，能不能启用一律由服务端裁决。 */
export function incompleteLeaf(node: IntentTreeNode): boolean {
  if (node.type === "GROUP") return false;
  if (!node.description?.trim() || node.examples.length === 0) return true;
  return node.type === "KB" && node.knowledgeBaseId === null;
}

/** 搜索匹配名称、完整路径、匹配描述和绑定知识库名称（ui-spec §11.1）。 */
export function matchesSearch(
  node: IntentTreeNode,
  keyword: string,
  knowledgeBaseName: (id: number) => string | undefined,
): boolean {
  const needle = keyword.trim().toLowerCase();
  if (!needle) return true;
  const bound = node.knowledgeBaseId === null ? "" : knowledgeBaseName(node.knowledgeBaseId) ?? "";
  return [node.name, node.fullPath, node.description ?? "", bound]
    .join(" ")
    .toLowerCase()
    .includes(needle);
}
