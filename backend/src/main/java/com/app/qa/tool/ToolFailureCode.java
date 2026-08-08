package com.app.qa.tool;

/**
 * 归一化失败码闭集。
 *
 * <p><b>存在的唯一理由是拦住异常 message。</b>原始异常可能带内部主机名、端口、连接串和堆栈
 * 片段，而这些既会进入界面，也会进入发给模型的上下文（PRD §12.6）。所以失败原因只能从这个
 * 闭集里取，不接受自由文本——`failure_code varchar(48)` 在数据库层也只留得下码值。
 */
public enum ToolFailureCode {

    /** 参数缺失、类型不符或取值不在允许范围。 */
    ARGUMENTS_INVALID,
    /** 工具不在白名单。 */
    NOT_WHITELISTED,
    /** 执行超时。 */
    EXECUTION_TIMEOUT,
    /** 工具返回了错误。 */
    EXECUTION_FAILED
}
