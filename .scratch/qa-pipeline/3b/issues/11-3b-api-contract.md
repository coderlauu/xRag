# 11 — 3B API 契约与隐私边界

**What to build:** `retrieval` / `tools` 两个响应块、重试端点与 3B 错误码；含隐私扫描。

**Blocked by:** 08, 10
**Status:** complete（`2026-08-11`）——七个错误码里四个无抛出点，见「遗留」

## Slice

- 扩展 turn 响应，3A 字段逐字段不变；未进 3B 时 `retrieval=null`、`tools=[]`。
- `POST .../retrieval/retry`；无失败目标返回 409 `NOTHING_TO_RETRY`。
- 七个 3B 错误码；`NO_RESULT` 是 200 不是错误。

## Acceptance

- [x] 3B-API-01~04 自动化通过。`Qa3bApiContractIntegrationTests` 7 条全绿；矩阵状态列的
      `passed` 回写留给 Step E（工单 15）。
- [x] 响应扫描不出现分数、chunk 正文、通道名、Schema 原文、异常 message。
- [x] 同一 turn 的 `sources` 内 `chunkId` 不重复。
- [x] `documentDeleted=true` 时条目仍返回，正文不返回。

## 执行记录（`2026-08-11`）

新增两个跨模块端口与一层视图，落点都是"什么能出去"这一个问题：

| 件 | 位置 | 为什么在这 |
|---|---|---|
| `ChunkDisplayLookup` | 知识库侧 | 来源要库名/文档名/`chunkIndex`，而问答模块不拼 chunk 表 SQL（架构 §19.1）。与 `ChunkTextLookup` 分开是因为**对逻辑删除的态度相反**：取正文时已删就返回 null，取展示信息时已删仍要返回 |
| `RetrievalResultRepository.findView` | 问答侧 | 只读出 id 与序号。`origin_channel` 存了但刻意不读——它只进处理详情 |
| `TurnResultView` + `TurnResultViewAssembler` | `qa.web` | 不复用领域类型：`FusedCandidate` 带分数、`ToolCallRow` 带参数原文，直接序列化会把不该出去的一起带出去。一处组装让隐私扫描有唯一靶子 |

`QaToolProperties.Tool` 启动时多解析一份参数元信息（Schema 的 `title` 与 `required`），与
`inputSchemaJson` 同理只解析一次。**Schema 原文不进 API**，只取名字与必填标记两项——够渲染参数表，
不够重建 Schema。

`api02` 的隐私断言一开始是**假绿**：白名单没配 → `definition` 为 null → 参数表为空 → 「`orderNo`
不出现」自然成立。补上白名单后正向断言（`contains("订单号")`）才让它成为真断言。这类"因为功能没
生效所以隐私断言通过"的陷阱，是隐私测试特有的失效模式。

## 遗留

- **七个错误码里四个没有抛出点**：`TOOL_ARGUMENTS_INVALID`、`TOOL_EXECUTION_FAILED`、
  `TOOL_EXECUTION_TIMEOUT`、`RETRIEVAL_FAILED`。前三个的触发路径在工单 10 的工具执行链路里，
  第四个依赖"检索接进 turn 处理流程"（见下）。没有硬造抛出点——为不可达的场景写错误处理，
  下次读代码的人会以为那条路走得通。
- **契约 §10 与 §12 有张力**：§10 说 CONFIRM「响应 200：返回整个 turn 结果，调用方直接替换本地
  状态」，§12 又把工具执行失败列为 502 `TOOL_EXECUTION_FAILED` / 504 超时。502 时调用方拿不到
  turn，两条不能同时成立。对照 `NO_RESULT` 那条明确的裁决（「不是错误，是 200 + status」），
  工具失败大概也该是 200 + `status=FAILED`，但这需要产品裁决，不由实现决定。
- **`RetrievalOrchestrator` 仍然没有业务调用方**：工单 08 建了编排组件，但没有任何工单负责把它
  接进 turn 处理流程（`TurnProcessor`）。依赖图里 08 → 11 之间缺这一环。本工单因此用 seed 落库
  结果验契约——那本就是"落库的东西怎么变成响应"的正确测法——但真实链路上 `retrieval` 至今只会
  是 null（除非显式调 retry 端点）。**这是依赖图的缺口，不是某张工单的疏漏**，按推进规则"发现
  隐藏依赖先改本图"应补一张工单。
- **工具端点的响应外壳仍是 `ToolCallsResponse`**（只有 tools），契约 §10 要求整个 turn。
  tools 块的**形态**已在本工单统一到契约，外壳形态属于工单 10 的产出，未动。
