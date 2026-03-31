import type {
  DocumentDetail,
  DocumentListResponse,
  JobStatusResponse,
  TagListResponse
} from "@xrag/shared-types";

const sampleDocument: DocumentDetail = {
  id: "doc_123",
  title: "RAG 产品最小闭环拆解",
  content_preview: "这里是文档摘要，用于搜索结果卡片和占位演示。",
  tags: ["RAG", "MVP", "检索"],
  source_type: "file",
  source_origin: "upload",
  file_name: "rag-mvp.pdf",
  parse_status: "success",
  imported_at: "2026-03-31T12:00:00Z",
  content_raw: null,
  content_clean: "这里是正式系统中的 content_clean 占位内容。",
  source_url: null,
  mime_type: "application/pdf",
  parse_error_message: null,
  created_at: "2026-03-31T11:50:00Z"
};

export const sampleDocuments: DocumentListResponse = {
  items: [sampleDocument],
  page: 1,
  page_size: 20,
  total: 1
};

export const sampleTags: TagListResponse = {
  items: [
    { id: "tag_1", name: "RAG", status: "active" },
    { id: "tag_2", name: "MVP", status: "active" },
    { id: "tag_3", name: "检索", status: "active" }
  ]
};

export const sampleJob: JobStatusResponse = {
  id: "job_456",
  document_id: "doc_123",
  job_type: "parse_document",
  status: "running",
  attempt: 1,
  error_message: null
};

export function findDocumentById(documentId: string): DocumentDetail | undefined {
  return sampleDocuments.items.find((item) => item.id === documentId) as DocumentDetail | undefined;
}
