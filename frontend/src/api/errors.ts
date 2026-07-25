import type { ApiErrorBody } from "./types";

/**
 * 给用户看的两段式提示：`title` 说发生了什么，`detail` 说下一步怎么办。
 * 分成两段而不是一整句，是因为 ui-spec §7 的文案里第一句要加粗——它是用户扫一眼
 * 就要抓住的信息，后半句是操作指引。
 */
export interface UserFacingMessage {
  title: string;
  detail?: string;
}

/**
 * 错误码到用户提示的映射，逐字对应 tech/knowledge-base/ui-spec.md §7。
 *
 * 文案里的具体数字（50MB、3 个并发、四种格式）是有意写死的：只说"文件太大"，
 * 用户不知道要压到多小才行。这几个值与后端配置耦合，改配置时这里要一起改。
 *
 * `INVALID_REQUEST` 与 `INVALID_STATE` 不在表内——它们必须直接展示后端 `message`。
 * 前者是逐字段的校验信息，后者一个码对应至少三种情况（给禁用文档触发分块 / 给禁用
 * 文档新增分块 / 启用分块但父文档禁用），前端从码上无法区分，见 api.md §1。
 */
const MESSAGES: Record<string, UserFacingMessage> = {
  FILE_TOO_LARGE: {
    title: "文件超过 50MB 上限，无法上传。",
    detail: "请压缩或拆分后重试。",
  },
  UNSUPPORTED_FILE_TYPE: {
    title: "不支持这种文件格式。",
    detail: "目前支持 .txt、.md、.pdf、.docx。",
  },
  UPLOAD_BUSY: {
    title: "当前上传任务较多，请稍后重试。",
    detail: "系统同时最多处理 3 个上传。",
  },
  DOCUMENT_PROCESSING: {
    title: "文档正在处理中，请等待处理完成后再操作。",
  },
  NOT_FOUND: {
    title: "内容不存在或已被删除。",
    detail: "请返回列表刷新后重试。",
  },
  EMBEDDING_FAILED: {
    title: "向量计算服务暂时不可用。",
    detail: "请稍后重试；若持续失败请检查模型服务配置。",
  },
};

/** 直接把后端 `message` 展示给用户的错误码。 */
const SHOW_SERVER_MESSAGE = new Set(["INVALID_REQUEST", "INVALID_STATE"]);

/**
 * 响应体不是契约里的 JSON 时，按状态码兜底推断错误码。
 *
 * 这不是多余的防御：超过 `max-file-size` 的请求由 Tomcat/Spring 在进入控制器之前就
 * 拒掉，返回的是容器默认错误页而不是 `{error, message}`，413 这一路尤其容易走到这里。
 */
const STATUS_FALLBACK: Record<number, string> = {
  404: "NOT_FOUND",
  413: "FILE_TOO_LARGE",
  415: "UNSUPPORTED_FILE_TYPE",
  429: "UPLOAD_BUSY",
};

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  /** 后端原文，可能为空（容器默认错误页没有它）。 */
  readonly serverMessage: string;

  constructor(status: number, code: string, serverMessage: string) {
    super(`${status} ${code}: ${serverMessage}`);
    this.name = "ApiError";
    this.status = status;
    this.code = code;
    this.serverMessage = serverMessage;
  }

  static fromResponse(status: number, body: ApiErrorBody | null): ApiError {
    const code = body?.error ?? STATUS_FALLBACK[status] ?? "UNKNOWN";
    return new ApiError(status, code, body?.message ?? "");
  }
}

/** 网络层失败（DNS、连接被拒、CORS、离线）。它没有 HTTP 状态码，与 ApiError 区分开。 */
export class NetworkError extends Error {
  constructor(cause: unknown) {
    super("无法连接到后端");
    this.name = "NetworkError";
    this.cause = cause;
  }
}

/**
 * 任意异常 → 可展示的提示。页面只管把它渲染出来，不用各自判断错误类型。
 *
 * 后两条兜底文案（网络不可达、未知错误）是 ui-spec §7 表格之外的补充，已同步写进
 * 该表，规格仍以 ui-spec 为准。
 */
export function toUserMessage(error: unknown): UserFacingMessage {
  if (error instanceof ApiError) {
    if (SHOW_SERVER_MESSAGE.has(error.code) && error.serverMessage) {
      return { title: error.serverMessage };
    }
    const mapped = MESSAGES[error.code];
    if (mapped) {
      return mapped;
    }
    return {
      title: "操作失败，请稍后重试。",
      detail: error.serverMessage || `服务端返回 ${error.status}。`,
    };
  }
  if (error instanceof NetworkError) {
    return {
      title: "无法连接到服务器。",
      detail: "请确认后端已启动、网络正常后重试。",
    };
  }
  return { title: "操作失败，请稍后重试。" };
}
