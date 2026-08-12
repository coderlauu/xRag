# ADR 0009：生成任务采用单一结算边界

- `status`: accepted（2026-08-12）
- `date`: 2026-08-12
- `decision_owner`: liuqiang
- `scope`: Phase 3C 的任务状态、SSE 终态、持久化、取消与许可释放
- `related`: [PRD §13](../prd/2026-08-07-qa-pipeline-prd.md)、[架构 §23~§30](../../tech/qa-pipeline/architecture.md)、[ADR 0002](0002-knowledge-base-async-and-concurrency.md)、[ADR 0004](0004-reactive-streaming-runtime.md)

## Context

一次生成同时受模型完成、用户停止、排队超时、流失败、断连、容器超时和应用关停影响。如果这些入口
分别写数据库、发 SSE 终态和释放 Semaphore，就会出现重复终态、部分回答重复保存或许可泄漏。

产品已经冻结：断连不取消、停止保留部分回答、完成但落库失败仍展示并标未保存、每轮只有唯一终态。

## Decision

1. `com.app.qa.generation` 对外只提供 `submit` 与带归属的 `stop`；Controller 不直接接触内部资源。
2. 所有终结原因都提交给 `TaskSettlement.trySettle`。原子 CAS 首胜；只有胜者执行持久化、取消订阅、
   释放许可与发送问答终态，败者只读取已定结果。
3. CAS 按真实到达顺序决定完成/停止/失败/超时竞态，不另设会改写事实的枚举优先级。
4. 断连只解绑观察者；任务继续。JVM 队列和取消 token 不持久化；重启将数据库非终态任务标为失败。
5. 单实例采用 FIFO 队列与公平许可；多实例分布式队列明确留待部署形态变化后另立 ADR。

## Rejected alternatives

- **Controller、Reactor 回调、超时器各自结算**：接口浅，终态规则复制三份，无法证明唯一释放。
- **把队列和取消状态持久化并轮询**：亚秒级信号被数据库轮询放大，仍不能恢复供应商流。
- **断连即取消**：违反已确认的弱网/刷新产品语义。
- **终态固定优先级覆盖先到结果**：模型已完成后再被停止覆盖，或用户已停止后又显示完成，都会改写事实。

## Consequences

- 测试可只跨一个 seam 观察数据库、事件和资源基线，内部实现可替换而不改调用方。
- `TaskSettlement` 承担高价值复杂度，必须用高轮次竞态测试和故障注入验证。
- 单实例重启会终止在途任务；这是显式失效点，不承诺伪恢复。

## Compliance

- 每个任务最多一个数据库终态、一个问答 SSE 终态、一个助手消息。
- 任意终态后许可、订阅、队列节点与 emitter observer 全部释放。
- 停止接口校验 conversation/turn/task 三元归属。
- 1000 轮完成/停止/超时竞态无重复结算。
