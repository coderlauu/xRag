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
  FAILED: "本轮理解失败",
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
