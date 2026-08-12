import type { RetrievalActionType, TurnStatus } from "../../api/qa";

/**
 * 稳定枚举到中文文案的唯一映射。
 *
 * 界面不直接渲染后端枚举：`REJECTED_TOO_MANY_TOPICS` 这类值对验收者也许可读，对普通
 * 用户是噪音，而 ui-spec §10 又要求"状态不能只靠颜色"——文字标签就是那个非颜色信号。
 */
export const TURN_STATUS_LABEL: Record<TurnStatus, string> = {
  SAVED: "已保存，等待处理",
  PROCESSING: "正在理解问题",
  ACTIONS_READY: "已形成检索动作",
  WAITING_CLARIFICATION: "等待你选择方向",
  REJECTED_TOO_MANY_TOPICS: "主题过多，已请你拆分",
  QUEUED: "排队中",
  GENERATING: "正在生成",
  COMPLETED: "已完成",
  STOPPED: "已停止",
  FAILED: "本轮失败",
  TIMED_OUT: "等待超时",
};

/**
 * 稳定降级码（后端 `qa_turn.degradation_codes` 白名单）到用户可读事实。
 *
 * 只覆盖 3A 编排真正会写进本轮的五个码：评分阶段的降级留在 `qa_turn_stage`，不进 API。
 */
const DEGRADATION_LABEL: Record<string, string> = {
  HISTORY_UNAVAILABLE: "近期历史加载失败，仅使用摘要与当前问题",
  SUMMARY_UNAVAILABLE: "摘要生成失败，沿用上一版摘要",
  SUMMARY_CONFLICT: "摘要已被其他请求更新，本轮沿用已安装版本",
  REWRITE_MODEL_UNAVAILABLE: "问题改写不可用，使用原问题",
  REWRITE_INVALID_RESPONSE: "问题改写输出不可用，使用原问题",
};

/** 未知码原样显示，不编造解释——它多半意味着后端加了新码而界面还没跟上。 */
export function degradationLabel(code: string): string {
  return DEGRADATION_LABEL[code] ?? code;
}

/**
 * 检索失败原因的用户文案（ui-spec §13.6：库名 + 归一化原因 + 状态，不透传异常文本）。
 *
 * 落在这里而不是后端：后端给的是归一化码值，措辞属于界面。未知码值原样显示——
 * 编一句"未知错误"会掩盖掉"后端加了新码值而前端没跟上"这件事。
 */
const RETRIEVAL_FAILURE_LABEL: Record<string, string> = {
  RETRIEVAL_TIMEOUT: "检索超时",
  KNOWLEDGE_BASE_UNAVAILABLE: "知识库暂时不可用",
};

export function retrievalFailureLabel(code: string | null): string {
  if (!code) {
    return "原因未记录";
  }
  return RETRIEVAL_FAILURE_LABEL[code] ?? code;
}

/**
 * 工具状态文案（ui-spec §13.7.1 的状态闭集）。
 *
 * 七个状态各有自己的一句话，**不合并**：`已取消`/`不可用`/`已中止` 对用户意味着三件不同的事
 * ——一个是自己取消的、一个是配置缺失（无法自助）、一个是参数没抽出来（补充后重问）。
 */
export const TOOL_STATUS_LABEL: Record<string, string> = {
  PENDING_CONFIRM: "待确认",
  RUNNING: "执行中",
  SUCCEEDED: "已完成",
  FAILED: "失败",
  CANCELLED: "已取消",
  UNAVAILABLE: "不可用",
  ABORTED: "已中止",
};

export function toolStatusLabel(status: string): string {
  return TOOL_STATUS_LABEL[status] ?? status;
}

/**
 * 工具失败原因的用户文案（ui-spec §13.7.3：只展示归一化文案，绝不透传异常 message）。
 *
 * 未知码值原样显示——编一句"未知错误"会掩盖"后端加了新码值而前端没跟上"。
 */
const TOOL_FAILURE_LABEL: Record<string, string> = {
  EXECUTION_TIMEOUT: "执行超时",
  EXECUTION_FAILED: "执行失败",
  ARGUMENTS_INVALID: "参数不合法",
  NOT_WHITELISTED: "不在可用工具清单中",
};

export function toolFailureLabel(code: string | null): string {
  if (!code) {
    return "原因未记录";
  }
  return TOOL_FAILURE_LABEL[code] ?? code;
}

export const ACTION_LABEL: Record<RetrievalActionType, string> = {
  KB_TARGETED: "定向检索知识库",
  GLOBAL_FALLBACK: "全局兜底检索",
  SYSTEM: "系统场景应答",
  NO_MATCH: "未匹配到方向",
};

/** ui-spec §7：验收面板只用强/中/低区间表达分数，聊天区完全不出现分数。 */
export function scoreBand(score: number): string {
  if (score >= 0.8) return "强匹配";
  if (score >= 0.6) return "中匹配";
  return "低匹配";
}

/** 对话列表的"最近活动"。跨天后退回日期，避免"720 小时前"这种没人读的写法。 */
export function relativeTime(iso: string, now = Date.now()): string {
  const time = new Date(iso).getTime();
  if (Number.isNaN(time)) return "";
  const minutes = Math.floor((now - time) / 60_000);
  if (minutes < 1) return "刚刚";
  if (minutes < 60) return `${minutes} 分钟前`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} 小时前`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days} 天前`;
  return new Date(iso).toLocaleDateString();
}
