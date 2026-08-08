package com.app.qa.retrieval;

/**
 * 检索通道闭集（ADR 0008 决策 1）。
 *
 * <p><b>priority 只有一套</b>，同时用于稳定排序与去重取舍。课程实现里 {@code getPriority()}
 * 与 {@code getType()} 是两套语义重叠的优先级、且没有任何机制保证一致，新增通道时必然出现
 * "priority 排在前面但去重时被判为低优先级"的矛盾。这里只留一个数字。
 *
 * <p>不预留 {@code HYBRID} 之类走不到的枚举值：枚举里存在但没有实现，读代码的人会以为系统支持。
 */
public enum SearchChannelType {

    /** 定向向量。3A 给出 KB_TARGETED 动作时激活，限定在动作里的知识库集合。 */
    INTENT_TARGETED(1),

    /** 关键词。始终激活，作用域与同轮向量通道一致。 */
    KEYWORD(2),

    /** 全局向量。3A 给出 GLOBAL_FALLBACK 动作时激活，不带 kb 过滤。 */
    GLOBAL_VECTOR(3);

    private final int priority;

    SearchChannelType(int priority) {
        this.priority = priority;
    }

    /** 数字越小越优先。 */
    public int priority() {
        return priority;
    }
}
