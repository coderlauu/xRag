package com.app.qa.tool;

/**
 * 工具执行结果。成功带纯文本，失败只带归一化码。
 *
 * <p>失败**不带自由文本**：异常 message 可能含内部主机名、端口与堆栈片段，而它既会进界面，
 * 也会进发给模型的上下文（PRD §12.6）。想说得更细就往闭集里加码值，不要在这里开一个字符串口子。
 */
public record ToolExecutionResult(String text, ToolFailureCode failureCode) {

    public static ToolExecutionResult success(String text) {
        return new ToolExecutionResult(text == null ? "" : text, null);
    }

    public static ToolExecutionResult failure(ToolFailureCode code) {
        return new ToolExecutionResult(null, code);
    }

    public boolean ok() {
        return failureCode == null;
    }
}
