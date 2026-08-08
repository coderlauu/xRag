# 06 — 历史快照、摘要与水位线

**What to build:** 实现 8/12 记忆策略、完整轮次筛选、摘要生成与 CAS 安装；摘要失败保留旧成果并继续。

**Blocked by:** 01, 03
**Status:** complete

## Slice

- 加载旧摘要、水位后最多 8 完整轮和当前消息，无重叠/缺口。
- 排除半轮、失败轮和未解决澄清。
- 达 12 后事务外调用 `ChatClient(BALANCED)`，只压缩滑出窗口的历史。
- 安装新摘要前比较旧 revision/watermark；记录无正文 stage/logicalCallId。

## Acceptance

- [x] 3A-MEM-01~12 自动化通过。
- [x] 摘要调用期间无业务事务；失败/超时/并发冲突均保留旧摘要。
- [x] 水位只前移且 UUID/sequence 一致，最近 8 轮不被重复摘要。
- [x] 假 Chat 能断言 sampling 来自 BALANCED profile 而非业务参数。

## Evidence

- `ConversationMemoryServiceTests` 以隔离 PostgreSQL schema 覆盖无摘要/旧摘要、8/12 边界、增量水位、FAILED
  半轮排除、已解决与未解决澄清、模型失败、历史读取失败和双生成 CAS 竞争。
- 假 `ChatClient` 断言 summary 命令使用 `BALANCED` 且调用时没有活动 Spring 事务；Prompt 只含滑出窗口的完整轮次，
  最近 8 轮不重复进入摘要。
- `qa_turn_stage` 只记录枚举、稳定原因、耗时和 logicalCallId；摘要或历史正文只存在业务表与模型输入，不写阶段表。
- `./mvnw test`：471 tests，0 failures，0 errors，5 skipped（真实收费 API 按预期跳过）。

**Design:** [architecture §6](../../../../tech/qa-pipeline/architecture.md)、[data model §5](../../../../tech/qa-pipeline/data-model.md)
