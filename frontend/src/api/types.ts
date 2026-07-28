/**
 * 与后端 API 契约（tech/knowledge-base/api.md §1）对应的通用形状。
 * 业务对象的类型由各自的模块文件定义，这里只放全站通用的三样。
 */

/** 分页响应统一形状。`page` 从 1 开始。 */
export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

/** 错误响应体。成功响应直接是业务对象，不套外壳。 */
export interface ApiErrorBody {
  error: string;
  message: string;
}

/** api.md §1 列出的全部错误码。 */
export type ApiErrorCode =
  | "INVALID_REQUEST"
  | "NOT_FOUND"
  | "DOCUMENT_PROCESSING"
  | "INVALID_STATE"
  | "KB_NOT_EMPTY"
  | "KB_HAS_ACTIVE_RUNS"
  | "FILE_TOO_LARGE"
  | "UNSUPPORTED_FILE_TYPE"
  | "UPLOAD_BUSY"
  | "EMBEDDING_FAILED";
