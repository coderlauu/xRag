package com.app.knowledge.model;

/**
 * 心跳超时回收时需要的投影。**刻意不复用 {@link IngestionRun}**：这里要的
 * {@code aliveSeconds} 只服务于错误消息的措辞，把它加进 {@code IngestionRun} 会连带
 * 出现在任务历史的 API 响应里，成为一个没人用的字段。
 *
 * @param phase 心跳停止时**最后记录到的**步骤。注意它不等于"失败发生在这一步"——进程
 *     被杀时执行线程可能早已走到下一步，只是没来得及写库。措辞上必须区分这两件事，
 *     否则界面会把「最后到过切分」渲染成「切分失败」，把用户引向完全错误的方向。
 * @param aliveSeconds 任务开始后心跳持续了多久（秒）。无法计算时为 -1。它是区分
 *     "进程消失"与"某一步真的卡住"的唯一判据：前者心跳几乎没跳过就断了。
 */
public record StaleRun(long id, long docId, IngestionPhase phase, long aliveSeconds) {}
