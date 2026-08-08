package com.app.qa.retrieval;

/**
 * 一个检索通道。
 *
 * <p><b>没有 {@code isEnabled(context)} 这种恒返回 true 的扩展点</b>：激活条件是
 * {@link #isActive(RetrievalScope)}，它有真实的判据（3A 的动作行），不是占位。
 */
public interface SearchChannel {

    SearchChannelType type();

    /** 激活条件由 3A 动作决定，通道不重新解释意图分数（ADR 0008 决策 2）。 */
    boolean isActive(RetrievalScope scope);

    /** 只在 {@link #isActive} 为真时被调用。返回顺序即该通道的排名。 */
    ChannelResult search(RetrievalScope scope, int limit);
}
