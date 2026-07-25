import { api } from "./client";

/** 字段与 api.md §4 的响应体一一对应。 */
export interface DocumentChunk {
  id: number;
  docId: number;
  revision: number;
  chunkIndex: number;
  content: string;
  charCount: number;
  tokenCount: number;
  enabled: boolean;
  createTime: string;
  updateTime: string;
}

/**
 * 启用状态的标签与悬浮说明来自 ui-spec.md §6，**不在组件里临时决定**。
 *
 * "向量已清理"这句必须写出来：重新启用会重新计算向量，也就是会再花一次 Embedding 的钱，
 * 用户有权在点之前知道这件事。
 */
export const CHUNK_ENABLED_LABEL = { true: "已启用", false: "已禁用" } as const;
export const CHUNK_ENABLED_HINT = {
  true: "参与检索",
  false: "不参与检索，向量已清理",
} as const;

/**
 * 保存后的三种反馈（ui-spec §6）。第二、三种都必须说出来，否则用户会怀疑保存没生效。
 *
 * 第三种是实现时补上的：禁用的分块本来就没有向量，编辑它也不会写向量（CHK-22），
 * 此时说"向量已更新"是**错的**——用户会以为这段内容已经能被检索到了。
 */
export const SAVED_MESSAGE = "已保存，向量已更新。";
export const UNCHANGED_MESSAGE = "内容未变化，未重新计算向量。";
export const SAVED_DISABLED_MESSAGE = "已保存。该分块已禁用、不参与检索，因此没有计算向量。";
export const DELETED_MESSAGE = "分块已删除，向量已清理。";
export const CREATED_MESSAGE = "分块已新增，向量已写入，可立即被检索。";
export const TOGGLED_MESSAGE = { true: "已启用，向量已写入。", false: "已禁用，向量已清理。" } as const;

/** 批量启禁用的响应。三个数字始终满足 `requested === changed + alreadyInTargetState`。 */
export interface BatchToggleResult {
  requested: number;
  changed: number;
  alreadyInTargetState: number;
}

/** 单次批量上限，与后端 `ChunkService.MAX_BATCH_SIZE` 必须一致（ui-spec §6）。 */
export const MAX_BATCH_SIZE = 500;

/** 父文档禁用时不能单独启用分块，措辞与后端 409 的 message 逐字相同（ui-spec §7）。 */
export const PARENT_DISABLED_TOOLTIP = "文档已禁用，无法单独启用其中的分块。请先启用文档。";
export const PARENT_DISABLED_ADD_TOOLTIP = "文档已禁用，无法新增分块。请先启用文档。";

export const chunks = {
  list: (docId: number, enabled?: boolean, page = 1, size = 20) =>
    api.page<DocumentChunk>(`/documents/${docId}/chunks`, page, size, { enabled }),

  create: (docId: number, content: string, chunkIndex?: number) =>
    api.post<DocumentChunk>(`/documents/${docId}/chunks`, { content, chunkIndex }),

  updateContent: (chunkId: number, content: string) =>
    api.put<DocumentChunk>(`/chunks/${chunkId}`, { content }),

  setEnabled: (chunkId: number, enabled: boolean) =>
    api.patch<DocumentChunk>(`/chunks/${chunkId}/enabled`, { enabled }),

  setEnabledBatch: (docId: number, chunkIds: number[], enabled: boolean) =>
    api.patch<BatchToggleResult>(`/documents/${docId}/chunks/enabled`, { chunkIds, enabled }),

  remove: (chunkId: number) => api.delete<void>(`/chunks/${chunkId}`),
};
