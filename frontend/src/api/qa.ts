import { ApiError, NetworkError, type UserFacingMessage } from "./errors";
import { requestAt, type RequestOptions } from "./client";

export type QaId = string;
export type IsoDateTime = string;
export type TurnStatus =
  | "SAVED"
  | "PROCESSING"
  | "ACTIONS_READY"
  | "WAITING_CLARIFICATION"
  | "REJECTED_TOO_MANY_TOPICS"
  | "FAILED";
export type MessageType =
  | "USER_QUERY"
  | "CLARIFICATION_CHOICE"
  | "CLARIFICATION_GUIDANCE"
  | "SYSTEM_NOTICE";
export type IntentNodeType = "GROUP" | "KB" | "SYSTEM";
export type RetrievalActionType = "KB_TARGETED" | "GLOBAL_FALLBACK" | "SYSTEM" | "NO_MATCH";

export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}

export interface ConversationListItem {
  conversationId: QaId;
  title: string;
  version: number;
  lastActiveAt: IsoDateTime;
  messagePreview: string;
  activeTurn: boolean;
}

export interface ConversationDetail extends Omit<ConversationListItem, "messagePreview" | "activeTurn"> {
  latestTurn: { turnId: QaId; status: TurnStatus; completedAt: IsoDateTime | null } | null;
}

export interface QaMessage {
  messageId: QaId;
  turnId: QaId;
  sequenceNo: number;
  role: "USER" | "ASSISTANT";
  type: MessageType;
  content: string;
  createdAt: IsoDateTime;
}

export interface IntentMatch {
  nodeId: QaId;
  type: "KB" | "SYSTEM";
  fullPath: string;
  score: number;
  rank: number;
}

export interface RetrievalAction {
  actionId: QaId;
  type: RetrievalActionType;
  knowledgeBaseId: number | null;
  intentNodeId: QaId | null;
  systemAction: string | null;
  reasonCode: string;
}

export interface TurnInspector {
  history: { summaryUsed: boolean; recentCompleteTurns: number };
  rewrite: { mode: "MODEL" | "NORMALIZED_FALLBACK"; rewrittenQuestion: string };
  subQuestions: Array<{
    subQuestionId: QaId;
    ordinal: number;
    text: string;
    matches: IntentMatch[];
    actions: RetrievalAction[];
  }>;
}

export interface TurnResult {
  conversation?: {
    conversationId: QaId;
    title: string;
    version: number;
    created: boolean;
  };
  turn: {
    turnId: QaId;
    userMessageId: QaId;
    status: TurnStatus;
    terminalReason: string | null;
    startedAt: IsoDateTime;
    completedAt: IsoDateTime | null;
    degradations: string[];
    intentRevisionId: QaId | null;
  };
  // 后端返回的是显式 null 而不是缺字段（Jackson 序列化 record 的默认行为），
  // 类型必须承认 null，否则 `result.inspector!` 这类写法会在运行时炸开。
  inspector?: TurnInspector | null;
  assistantMessage?: QaMessage | null;
  clarification?: {
    options: Array<{ nodeId: QaId; label: string }>;
    allowNoneOfTheAbove: boolean;
  } | null;
  /** turn 未进过 3B 时是 null——"没检索过"与"检索了没结果"是两件事（api.md §9）。 */
  retrieval?: TurnRetrieval | null;
  /** 工具区属工单 13；这里先承认字段，避免 13 改动 api 层的形状。 */
  tools?: TurnTool[];
  retryAfterMs?: number;
}

export type RetrievalStatus = "RETRIEVING" | "RETRIEVED" | "NO_RESULT" | "RETRIEVAL_FAILED";

/**
 * 3B 检索结果块（api.md §9）。
 *
 * **没有任何分数字段**，顺序即排名（PRD §12.2）。`partial` 是服务端算好的显式字段，
 * 界面不得用 `sources.length` 倒推（PRD §12.4 / ui-spec §13.6）。
 */
export interface TurnRetrieval {
  status: RetrievalStatus;
  partial: boolean;
  rerankApplied: boolean;
  /** `rerankApplied=false` 时非空。界面只显示"未精排"，**不区分成因**（ui-spec §13.4）。 */
  degradation: string | null;
  sources: TurnSource[];
  failedTargets: TurnFailedTarget[];
}

export interface TurnSource {
  ordinal: number;
  chunkId: number;
  knowledgeBaseId: number | null;
  knowledgeBaseName: string | null;
  documentId: number | null;
  documentName: string | null;
  /** 沿用知识库页的分块序号语义，不是行号、不重编号（ui-spec §13.3）。 */
  chunkIndex: number | null;
  /** true 时条目**保留**且禁用跳转，显示"文档已删除"。 */
  documentDeleted: boolean;
}

export interface TurnFailedTarget {
  knowledgeBaseId: number | null;
  knowledgeBaseName: string | null;
  /** 归一化闭集码值，永不是异常文本。 */
  failureCode: string | null;
}

export interface TurnTool {
  toolCallId: QaId;
  ordinal: number;
  toolName: string;
  displayName: string | null;
  description: string | null;
  sideEffect: boolean;
  status: string;
  parameters: Array<{ name: string; value: string | null; required: boolean }>;
  resultText: string | null;
  failureCode: string | null;
  latencyMs: number | null;
}

export interface IntentTreeRevision {
  revisionId: QaId | null;
  revisionNo: number;
  updatedAt: IsoDateTime;
}

/** 3A 的受控系统场景闭集；MCP 类型要等 3B 规格增量，界面不得自行扩充。 */
export type SystemActionId = "GREETING" | "ASSISTANT_IDENTITY" | "CAPABILITY_HELP";
export type EnableScope = "NODE_ONLY" | "CONFIGURED_SUBTREE";
export type TreeOperation = "ENABLE_CONFIGURED_SUBTREE" | "DISABLE_SUBTREE" | "DELETE_SUBTREE";

export interface IntentTreeNode {
  nodeId: QaId;
  parentId: QaId;
  depth: 1 | 2 | 3;
  sortOrder: number;
  type: IntentNodeType;
  name: string;
  description: string | null;
  examples: string[];
  knowledgeBaseId: number | null;
  systemAction: SystemActionId | null;
  promptSnippet: string | null;
  enabled: boolean;
  /** 服务端按祖先算出的展示字段；它**不替代** `enabled`，两者要分别展示。 */
  effectiveEnabled: boolean;
  fullPath: string;
  childCount: number;
  enabledLeafCount: number;
  versionState: "CURRENT";
}

export interface IntentTreeSummary {
  nodeCount: number;
  enabledLeafCount: number;
  incompleteLeafCount: number;
}

export interface IntentTreeSnapshot {
  revision: IntentTreeRevision;
  root: { nodeId: QaId; name: string; virtual: true; editable: false };
  nodes: IntentTreeNode[];
  summary: IntentTreeSummary;
}

/** 写命令的统一返回形态（api.md §5）。`nodes` 是提交后的完整新快照，不是增量补丁。 */
export interface IntentTreeMutation {
  revision: IntentTreeRevision;
  changedNodeId: QaId;
  treeSummary: IntentTreeSummary;
  nodes: IntentTreeNode[];
}

export interface IntentImpact {
  revisionId: QaId;
  operation: TreeOperation;
  nodeCount: number;
  enabledLeafCount: number;
  boundKnowledgeBaseCount: number;
  blockingNodes: QaId[];
}

export interface BindableKnowledgeBase {
  knowledgeBaseId: number;
  name: string;
  enabled: boolean;
  chunkCount: number;
}

/** 普通可编辑字段的完整替换体。用 PUT 而不是 PATCH，见 api.md §5.2。 */
export interface IntentNodeDraft {
  name: string;
  description: string | null;
  examples: string[];
  knowledgeBaseId: number | null;
  systemAction: SystemActionId | null;
  promptSnippet: string | null;
  sortOrder: number;
}

const QA_ROOT = "/api/qa";

function qaRequest<T>(path: string, options: RequestOptions = {}) {
  return requestAt<T>(QA_ROOT, path, options);
}

export function listQaConversations(query: { cursor?: string; limit?: number } = {}) {
  return qaRequest<CursorPage<ConversationListItem>>("/conversations", { query });
}

export function readQaConversation(conversationId: QaId) {
  return qaRequest<ConversationDetail>(`/conversations/${conversationId}`);
}

export function listQaMessages(
  conversationId: QaId,
  query: { before?: string; limit?: number } = {},
) {
  return qaRequest<{ items: QaMessage[]; previousCursor: string | null }>(
    `/conversations/${conversationId}/messages`,
    { query },
  );
}

export function readQaTurn(conversationId: QaId, turnId: QaId) {
  return qaRequest<TurnResult>(`/conversations/${conversationId}/turns/${turnId}`);
}

/**
 * 只重跑失败的知识库（api.md §11）。
 *
 * 响应是整个 turn 结果，调用方直接替换本地状态——合并发生在服务端，前端不自己把
 * "重试返回的那部分"并进已有来源。
 */
export function retryQaRetrieval(input: { clientRequestId: QaId; turnId: QaId }) {
  return qaRequest<TurnResult>(`/turns/${input.turnId}/retrieval/retry`, {
    method: "POST",
    body: { clientRequestId: input.clientRequestId },
  });
}

/**
 * 裁决一次有副作用的工具调用（api.md §10）。
 *
 * **响应只有 tools，不是整个 turn。** 契约 §10 要求返回整个 turn 结果，但工单 10 实现的外壳
 * 是 `{tools}`，工单 11 记录了这处未改。调用方因此只能更新工具那一块——这是现状而不是设计，
 * 别据此假设 turn 的其余部分也刷新了。
 */
export function decideQaToolCall(input: {
  clientRequestId: QaId;
  turnId: QaId;
  toolCallId: QaId;
  decision: "CONFIRM" | "CANCEL";
}) {
  return qaRequest<{ tools: TurnTool[] }>(
    `/turns/${input.turnId}/tool-calls/${input.toolCallId}/decision`,
    { method: "POST", body: { clientRequestId: input.clientRequestId, decision: input.decision } },
  );
}

/**
 * 重试一次失败的工具调用（ui-spec §13.7.4）。
 *
 * 有副作用的工具重试会产生一条**新的** toolCallId 并回到 `PENDING_CONFIRM`——上一次的确认
 * 不能被复用，"确认前一律不执行"对每一次执行都成立。
 */
export function retryQaToolCall(input: {
  clientRequestId: QaId;
  turnId: QaId;
  toolCallId: QaId;
}) {
  return qaRequest<{ tools: TurnTool[] }>(
    `/turns/${input.turnId}/tool-calls/${input.toolCallId}/retry`,
    { method: "POST", body: { clientRequestId: input.clientRequestId } },
  );
}

export function submitQaQuestion(input: {
  clientRequestId: QaId;
  conversationId: QaId | null;
  text: string;
}) {
  return qaRequest<TurnResult>("/turns", {
    method: "POST",
    body: {
      clientRequestId: input.clientRequestId,
      conversationId: input.conversationId,
      message: { type: "QUESTION", text: input.text },
    },
  });
}

export function submitQaClarification(input: {
  clientRequestId: QaId;
  conversationId: QaId;
  sourceTurnId: QaId;
  selectedNodeId?: QaId;
  noneOfTheAbove: boolean;
}) {
  return qaRequest<TurnResult>("/turns", {
    method: "POST",
    body: {
      clientRequestId: input.clientRequestId,
      conversationId: input.conversationId,
      message: {
        type: "CLARIFICATION_CHOICE",
        sourceTurnId: input.sourceTurnId,
        selectedNodeId: input.selectedNodeId ?? null,
        noneOfTheAbove: input.noneOfTheAbove,
      },
    },
  });
}

export function renameQaConversation(conversationId: QaId, title: string, expectedVersion: number) {
  return qaRequest<{ conversationId: QaId; title: string; version: number }>(
    `/conversations/${conversationId}`,
    { method: "PATCH", body: { title, expectedVersion } },
  );
}

export function deleteQaConversation(conversationId: QaId, expectedVersion: number) {
  return qaRequest<void>(`/conversations/${conversationId}`, {
    method: "DELETE",
    body: { expectedVersion },
  });
}

export function readIntentTree() {
  return qaRequest<IntentTreeSnapshot>("/intent-tree");
}

export function listBindableKnowledgeBases(
  query: { query?: string; cursor?: string; limit?: number } = {},
) {
  return qaRequest<{ items: BindableKnowledgeBase[]; nextCursor: string | null }>(
    "/intent-tree/bindable-knowledge-bases",
    { query },
  );
}

export function createIntentNode(input: {
  expectedRevisionId: QaId | null;
  parentId: QaId;
  type: IntentNodeType;
  draft: IntentNodeDraft;
}) {
  return qaRequest<IntentTreeMutation>("/intent-tree/nodes", {
    method: "POST",
    body: {
      expectedRevisionId: input.expectedRevisionId,
      parentId: input.parentId,
      type: input.type,
      ...input.draft,
    },
  });
}

export function updateIntentNode(nodeId: QaId, expectedRevisionId: QaId | null, draft: IntentNodeDraft) {
  return qaRequest<IntentTreeMutation>(`/intent-tree/nodes/${nodeId}`, {
    method: "PUT",
    body: { expectedRevisionId, ...draft },
  });
}

/**
 * 影响预览。它**不锁住 revision**：真正的命令仍然带同一个 expected，树在这中间变了就让
 * 命令 409，由界面重新预览——预览本身不是承诺。
 */
export function previewIntentImpact(
  nodeId: QaId,
  expectedRevisionId: QaId | null,
  operation: TreeOperation,
) {
  return qaRequest<IntentImpact>(`/intent-tree/nodes/${nodeId}/impact`, {
    method: "POST",
    body: { expectedRevisionId, operation },
  });
}

export function disableIntentSubtree(nodeId: QaId, expectedRevisionId: QaId | null) {
  return qaRequest<IntentTreeMutation>(`/intent-tree/nodes/${nodeId}/disable`, {
    method: "POST",
    body: { expectedRevisionId, scope: "SUBTREE" },
  });
}

export function enableIntentNode(
  nodeId: QaId,
  expectedRevisionId: QaId | null,
  scope: EnableScope,
) {
  return qaRequest<IntentTreeMutation>(`/intent-tree/nodes/${nodeId}/enable`, {
    method: "POST",
    body: { expectedRevisionId, scope },
  });
}

/** 删除走命令端点而不是 `DELETE` + body，理由见 api.md §5.5。 */
export function deleteIntentSubtree(nodeId: QaId, expectedRevisionId: QaId | null) {
  return qaRequest<IntentTreeMutation>(`/intent-tree/nodes/${nodeId}/delete`, {
    method: "POST",
    body: { expectedRevisionId, scope: "SUBTREE" },
  });
}

const QA_MESSAGES: Record<string, UserFacingMessage> = {
  INTENT_TREE_REVISION_CONFLICT: {
    title: "意图树配置已被更新。",
    detail: "本地输入会保留，请刷新比较后再保存。",
  },
  INTENT_TREE_CONSTRAINT_VIOLATION: {
    title: "这次修改不满足意图树的结构或配置约束。",
    detail: "请按下面列出的阻塞项修正后重试；本次没有产生新版本。",
  },
  INTENT_NODE_NOT_FOUND: {
    title: "当前版本里没有这个节点。",
    detail: "它可能已被其他人删除，请刷新意图树。",
  },
  VIRTUAL_ROOT_IMMUTABLE: {
    title: "「全部意图」是系统保留根。",
    detail: "它不能被修改、启停或删除，只能在它下面新增业务域。",
  },
  INTENT_PROMPT_BUDGET_EXCEEDED: {
    title: "启用后完整评分 Prompt 会超出预算。",
    detail: "请先精简匹配描述或停用其他叶子，再启用这个节点。",
  },
  INTENT_TREE_PERSISTENCE_FAILED: {
    title: "意图树没有保存成功。",
    detail: "本次命令未产生新版本，运行中的配置没有被部分修改。",
  },
  CONVERSATION_VERSION_CONFLICT: {
    title: "对话标题已被更新。",
    detail: "请刷新后比较再保存。",
  },
  CONVERSATION_BUSY: {
    title: "当前对话仍在处理中。",
    detail: "请等待本轮完成后再发送。",
  },
  CONVERSATION_NOT_FOUND: {
    title: "对话不存在或已删除。",
    detail: "请从对话列表重新选择。",
  },
  CONVERSATION_HAS_ACTIVE_TURN: {
    title: "本轮仍在处理，暂时不能删除这个对话。",
    detail: "等本轮到达稳定终态后再试。",
  },
  TURN_NOT_FOUND: {
    title: "本轮记录不存在或不属于当前对话。",
    detail: "请刷新对话后重试。",
  },
  MESSAGE_PERSISTENCE_FAILED: {
    title: "消息没有保存成功，本轮没有开始处理。",
    detail: "输入已保留，请直接重试发送。",
  },
  TURN_PERSISTENCE_FAILED: {
    title: "本轮没有收敛为稳定状态。",
    detail: "请刷新对话查看最终结果。",
  },
  IDEMPOTENCY_KEY_REUSED: {
    title: "这次提交与之前的请求内容不一致。",
    detail: "请修改后重新发送。",
  },
  INVALID_CLARIFICATION_SOURCE: {
    title: "这个澄清来源不属于当前对话。",
    detail: "请刷新对话后重新选择方向。",
  },
  INVALID_CLARIFICATION_OPTION: {
    title: "这个方向已经失效。",
    detail: "请刷新对话后重新选择。",
  },
  QA_PROCESSOR_UNAVAILABLE: {
    title: "问答处理器尚未就绪。",
    detail: "请稍后重试；持续失败时检查模型服务配置。",
  },
  INVALID_CURSOR: {
    title: "翻页游标已失效。",
    detail: "请重新加载第一页。",
  },
  VALIDATION_FAILED: {
    title: "输入内容不符合要求。",
    detail: "请检查标记字段后重试。",
  },
};

/** QA ProblemDetail 到安全文案的唯一映射；未知 code 不展示服务端 detail。 */
export function toQaUserMessage(error: unknown): UserFacingMessage {
  if (error instanceof ApiError) {
    return QA_MESSAGES[error.code] ?? { title: "操作失败，请稍后重试。" };
  }
  // ui-spec §8 要求错误说明失败点：网络层不可达和"后端拒绝了这次请求"是两种不同的
  // 处置，都压成"操作失败"会让用户以为问题出在自己的输入上。
  if (error instanceof NetworkError) {
    return { title: "无法连接到服务器。", detail: "请确认后端已启动、网络正常后重试。" };
  }
  return { title: "操作失败，请稍后重试。" };
}
