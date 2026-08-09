package com.app.qa.tool;

import java.time.Duration;

/**
 * 工具的实际执行方（SPI）。
 *
 * <p><b>传输方式不在 3B Step C 的范围内</b>，所以这里只定义接口不定义协议：白名单负责"允许调什么"，
 * 复核负责"参数能不能用"，本接口负责"怎么调到"。真实工具服务（MCP over HTTP 之类）接进来时
 * 实现这个接口即可，不需要动状态机、复核与确认流。
 *
 * <p>实现必须遵守两条：
 * <ul>
 *   <li><b>不抛异常</b>——失败包成 {@link ToolExecutionResult#failure}，上层因此没有异常分支，
 *       也就不会有人不小心把异常 message 透传给用户或模型。</li>
 *   <li><b>自己遵守 timeout</b>——超时归一化为 {@link ToolFailureCode#EXECUTION_TIMEOUT}。</li>
 * </ul>
 */
public interface ToolExecutor {

    /** 与白名单里的 {@code name} 一致。 */
    String name();

    ToolExecutionResult execute(String argumentsJson, Duration timeout);
}
