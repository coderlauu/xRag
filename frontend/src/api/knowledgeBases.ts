import { api } from "./client";
import type { Page } from "./types";

/** 与 api.md §2 的响应体一一对应。 */
export interface KnowledgeBase {
  id: number;
  name: string;
  description: string | null;
  embeddingModel: string;
  embeddingDimensions: number;
  documentCount: number;
  chunkCount: number;
  createTime: string;
}

const PATH = "/knowledge-bases";

export const knowledgeBases = {
  list: (page = 1, size = 20) => api.page<KnowledgeBase>(PATH, page, size),

  create: (name: string, description?: string) =>
    api.post<KnowledgeBase>(PATH, { name, description }),

  rename: (id: number, name: string, description?: string | null) =>
    api.put<KnowledgeBase>(`${PATH}/${id}`, { name, description }),

  remove: (id: number) => api.delete<void>(`${PATH}/${id}`),
};

export type { Page };
