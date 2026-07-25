import { ApiError, NetworkError } from "./errors";
import type { ApiErrorBody, Page } from "./types";

const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

export interface RequestOptions {
  method?: string;
  /** JSON 请求体。与 `formData` 互斥。 */
  body?: unknown;
  /** 上传用。设置后不会带 `Content-Type`，交给浏览器补 multipart 边界。 */
  formData?: FormData;
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
}

function buildUrl(path: string, query?: RequestOptions["query"]): string {
  const url = `${BASE_URL}/api/v1${path}`;
  if (!query) {
    return url;
  }
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) {
      params.set(key, String(value));
    }
  }
  const queryString = params.toString();
  return queryString ? `${url}?${queryString}` : url;
}

/**
 * 全站唯一的请求出口。它只负责三件事，业务语义一概不碰：
 *
 * 1. 拼 `VITE_API_BASE_URL` + `/api/v1` 前缀；
 * 2. 把 `{error, message}` 解包成可以 `catch` 的 {@link ApiError}；
 * 3. 204 与空响应体返回 `undefined` 而不是让 `response.json()` 抛解析错误。
 */
export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, formData, query, signal } = options;

  const headers: Record<string, string> = {};
  if (body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  let response: Response;
  try {
    response = await fetch(buildUrl(path, query), {
      method,
      headers,
      body: formData ?? (body !== undefined ? JSON.stringify(body) : undefined),
      signal,
    });
  } catch (cause) {
    // AbortError 是调用方主动取消（组件卸载、切换筛选），原样抛出让调用方忽略它，
    // 不要包装成"无法连接到服务器"弹给用户。
    if (cause instanceof DOMException && cause.name === "AbortError") {
      throw cause;
    }
    throw new NetworkError(cause);
  }

  if (!response.ok) {
    throw ApiError.fromResponse(response.status, await readJson<ApiErrorBody>(response));
  }

  return (await readJson<T>(response)) as T;
}

/** 响应体为空或不是 JSON 时返回 null，而不是抛出解析异常掩盖真正的错误。 */
async function readJson<T>(response: Response): Promise<T | null> {
  if (response.status === 204) {
    return null;
  }
  const text = await response.text();
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text) as T;
  } catch {
    return null;
  }
}

export const api = {
  get: <T>(path: string, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "GET" }),

  post: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "POST", body }),

  put: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "PUT", body }),

  patch: <T>(path: string, body?: unknown, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "PATCH", body }),

  delete: <T>(path: string, options?: Omit<RequestOptions, "method" | "body">) =>
    request<T>(path, { ...options, method: "DELETE" }),

  upload: <T>(path: string, formData: FormData, options?: Omit<RequestOptions, "method" | "formData">) =>
    request<T>(path, { ...options, method: "POST", formData }),

  /** 分页请求。`page` 从 1 开始，`size` 默认 20、上限 100（api.md §1）。 */
  page: <T>(path: string, page = 1, size = 20, extraQuery?: RequestOptions["query"]) =>
    request<Page<T>>(path, { method: "GET", query: { ...extraQuery, page, size } }),
};
