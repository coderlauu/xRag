/**
 * AI 知识库建设模块。完整设计见 {@code tech/knowledge-base/}（architecture / data-model
 * / api 三件套）。
 *
 * <h2>分层</h2>
 * <ul>
 *   <li>{@code web} —— REST 控制器、请求/响应 DTO、参数校验</li>
 *   <li>{@code service} —— 业务编排：事务边界、状态机、一致性保证都在这一层</li>
 *   <li>{@code repository} —— JdbcTemplate 数据访问，一个类对应一张表</li>
 *   <li>{@code model} —— 领域对象与枚举，术语严格对齐 {@code CONTEXT.md}</li>
 *   <li>{@code ingestion} —— 文本提取、分块算法、任务派发与执行</li>
 *   <li>{@code embedding} —— EmbeddingClient 接口及其实现</li>
 *   <li>{@code vector} —— pgvector 读写，向量表的唯一入口</li>
 * </ul>
 *
 * <h2>两条必须遵守的纪律</h2>
 * <ol>
 *   <li><strong>事务边界只允许出现在 service 层，且事务内不得有任何外部 IO</strong>
 *       （对象存储读写、Embedding HTTP 调用、远程文件下载）。批量 Embedding 必须在
 *       开事务之前算完。repository / vector 层不自己开事务，只参与调用方的事务。</li>
 *   <li><strong>repository 层所有查询方法都必须带 {@code deleted = false}</strong>
 *       ——全系统统一逻辑删除（PRD §7.6）。这是一条靠约定维持的纪律，也是本模块最容易
 *       漏的一类 bug，工单 18 专门为它安排了一组横向用例。</li>
 * </ol>
 *
 * Redis 在本模块<strong>用不到</strong>：上传并发限流用本地信号量，异步任务用数据库
 * 任务表（见 ADR 0002）。这是有意的，不是遗漏。
 */
package com.app.knowledge;
