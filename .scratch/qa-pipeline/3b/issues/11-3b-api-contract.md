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

- ~~**七个错误码里四个没有抛出点**~~ → **已裁决（`2026-08-11`）**：其中三个
  （`TOOL_ARGUMENTS_INVALID`、`TOOL_EXECUTION_FAILED`、`TOOL_EXECUTION_TIMEOUT`）**本来就不该有
  抛出点**——它们是 `ToolFailureCode` 的枚举值，落在 `qa_tool_call.failure_code` 上，不是 HTTP
  错误码。工具失败按 200 + `tools[].status=FAILED` 返回，与 `NO_RESULT` 同源。契约 §12 已按裁决
  重写（新增 §12.1 记录理由），实现自工单 10 起就是对的，本次是文档跟上实现。
  "没有抛出点"这个观察是对的，但当时的结论（缺实现）搞错了方向——是契约把状态机的终态
  搬到了 HTTP 层。
- **`RETRIEVAL_FAILED`（502）仍待裁决**，它是上面那条的同源问题：检索全失败现在落成
  `qa_turn_retrieval.status = RETRIEVAL_FAILED`（200 + 状态），而 §12 原先把它列为 502。
  按同一条裁决精神它也该留在状态里，但**产品尚未明确表态**，所以 §12 的表格暂时保留原状之外
  没有替它下结论。见[工单 08b 的遗留](08b-retrieval-wiring.md)。
- ~~**`RetrievalOrchestrator` 仍然没有业务调用方**~~ → **已补** [工单 08b](08b-retrieval-wiring.md)
  （`2026-08-11`）。本工单发现依赖图在 08 与 11 之间缺一环，按推进规则"发现隐藏依赖先改本图"
  补成 08b 而不是塞进本工单。本工单用 seed 落库结果验契约的做法不受影响——那本就是"落库的
  东西怎么变成响应"的正确测法。
- **工具端点的响应外壳仍是 `ToolCallsResponse`**（只有 tools），契约 §10 要求整个 turn。
  tools 块的**形态**已在本工单统一到契约，外壳形态属于工单 10 的产出，未动。
