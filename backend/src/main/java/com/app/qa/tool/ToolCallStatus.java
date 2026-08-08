package com.app.qa.tool;

/**
 * 工具调用状态闭集（架构 §21.4）。与 {@code qa_tool_call.ck_qa_tool_status} 一一对应。
 *
 * <p>主路径是四态：请求（含参数）→ 用户确认 → 执行中 → 结果或失败。另外三个是不进主路径的
 * 终态，每一个都对应 PRD §12 明确要求"必须让用户看见"的一种情况——它们存在的理由不是
 * 完备性，而是**静默会骗人**。
 */
public enum ToolCallStatus {

    /** 有副作用的工具，参数已复核通过，等待用户裁决。此状态下不得有任何外部调用。 */
    PENDING_CONFIRM,
    RUNNING,
    SUCCEEDED,
    FAILED,
    /** 用户取消。 */
    CANCELLED,
    /** 未注册或不在白名单。不能静默跳过——那会让用户以为系统不支持，而真相是配置缺失。 */
    UNAVAILABLE,
    /** 参数抽取失败或参数非法，未执行。绝不用默认值继续（PRD §12.7）。 */
    ABORTED;

    public boolean isTerminal() {
        return this != PENDING_CONFIRM && this != RUNNING;
    }
}
