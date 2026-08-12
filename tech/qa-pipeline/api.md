# API 契约：AI 知识问答核心链路（Phase 3A + 3B）

- `status`: 3A approved（2026-08-07）；**3B draft（2026-08-08，§9~§12）**
- `approved_at`: 2026-08-07
- `date`: 2026-08-07
- `base_path`: `/api/qa`
- `architecture`: [技术方案](architecture.md)
- `data_model`: [数据模型](data-model.md)
- `ui`: [问答页规格](conversation/ui-spec.md)、[意图树规格](intent-tree/ui-spec.md)

## 1. 通用约定

- JSON 使用 UTF-8；字段名 camelCase；时间为带时区 ISO-8601。
- 业务 ID 均为 UUID 字符串；知识库 ID 延续 Phase 1 的整数 ID。
- 未声明字段默认拒绝，避免客户端拼错字段后“成功但没生效”。
- 所有写接口校验 `Content-Type: application/json`，正文上限由统一 Servlet 配置限制。
- 业务删除后的资源对普通读取返回 404，不泄露“曾经存在”。
- 列表用不透明 cursor，稳定排序字段后附 ID 作为 tie-break；不暴露数据库 offset。
- 错误采用 `application/problem+json`，`code` 是客户端分支依据，`detail` 只面向人类。
- 3A 没有 SSE。`POST /turns` 返回稳定终态；3C 另行定义问答事件协议，不能原样投影 `ChatStreamEvent`。

ProblemDetail 示例：

```json
{
  "type": "about:blank",
  "title": "意图树版本冲突",
  "status": 409,
  "detail": "配置已被更新，请刷新后比较再保存。",
  "instance": "/api/qa/intent-tree/nodes/6f...",
  "code": "INTENT_TREE_REVISION_CONFLICT",
  "requestId": "req_****7a2c",
  "currentRevisionId": "31f..."
}
```

`requestId` 是脱敏请求关联值，不是 `logicalCallId`。错误不返回栈、SQL、Prompt、Provider body 或模型原始 JSON。

## 2. 对话读取与管理

### 2.1 `GET /api/qa/conversations`

参数：

| 参数 | 规则 |
|---|---|
| `cursor` | 可选，不透明 |
| `limit` | 默认 20，1~100 |

响应 `200`：

```json
{
  "items": [
    {
      "conversationId": "b94...",
      "title": "海外仓的入库时效是什么？",
      "version": 2,
      "lastActiveAt": "2026-08-07T10:11:12+08:00",
      "messagePreview": "那超时了怎么办？",
      "activeTurn": false
    }
  ],
  "nextCursor": "opaque-or-null"
}
```

按 `lastActiveAt desc, conversationId desc`。preview 来自最近有效用户可见消息，最多 120 个 code point；不含
内部子问题、评分或降级详情。

### 2.2 `GET /api/qa/conversations/{conversationId}`

返回对话元数据及最近稳定 turn 摘要，不内嵌无限消息。不存在/已删除返回 404。

```json
{
  "conversationId": "b94...",
  "title": "海外仓的入库时效是什么？",
  "version": 2,
  "lastActiveAt": "2026-08-07T10:11:12+08:00",
  "latestTurn": {
    "turnId": "954...",
    "status": "ACTIONS_READY",
    "completedAt": "2026-08-07T10:11:11+08:00"
  }
}
```

### 2.3 `GET /api/qa/conversations/{conversationId}/messages`

参数 `before`（不透明 cursor）和 `limit`（默认 50，1~100），按新到旧取页，响应 `items` 按时间正序，便于
聊天窗口 prepend。

```json
{
  "items": [
    {
      "messageId": "11a...",
      "turnId": "954...",
      "sequenceNo": 7,
      "role": "USER",
      "type": "USER_QUERY",
      "content": "那超时了怎么办？",
      "createdAt": "2026-08-07T10:11:10+08:00"
    }
  ],
  "previousCursor": "opaque-or-null"
}
```

### 2.4 `PATCH /api/qa/conversations/{conversationId}`

请求：

```json
{"title":"海外仓时效与超时处理","expectedVersion":2}
```

成功 `200` 返回新 `version`。版本冲突 `409 CONVERSATION_VERSION_CONFLICT` 并返回 `currentVersion`；非法标题
`422 VALIDATION_FAILED`。

### 2.5 `DELETE /api/qa/conversations/{conversationId}`

请求体：

```json
{"expectedVersion":3}
```

成功 `204`。有活动本轮时返回 `409 CONVERSATION_HAS_ACTIVE_TURN`；3A 不提供“删除并取消模型调用”。重复删除
返回 404，客户端可把它视为目标已不可见。

## 3. 提交本轮

### 3.1 `POST /api/qa/turns`

同一接口兼顾首问自动建对话和已有对话追问：

```json
{
  "clientRequestId": "bc2...",
  "conversationId": null,
  "message": {
    "type": "QUESTION",
    "text": "退货要什么材料，退款多久能到账？"
  }
}
```

澄清选择：

```json
{
  "clientRequestId": "f81...",
  "conversationId": "b94...",
  "message": {
    "type": "CLARIFICATION_CHOICE",
    "sourceTurnId": "954...",
    "selectedNodeId": "6f1...",
    "noneOfTheAbove": false
  }
}
```

`selectedNodeId` 与 `noneOfTheAbove=true` 互斥。客户端不上传显示文案；服务端从原选项生成持久化的用户消息，
防止伪造另一个对话的节点。

成功 `201`（新处理）或 `200`（相同幂等请求重放）：

```json
{
  "conversation": {
    "conversationId": "b94...",
    "title": "退货要什么材料，退款多久能到账？",
    "version": 0,
    "created": true
  },
  "turn": {
    "turnId": "954...",
    "userMessageId": "11a...",
    "status": "ACTIONS_READY",
    "terminalReason": null,
    "startedAt": "2026-08-07T10:11:10+08:00",
    "completedAt": "2026-08-07T10:11:12+08:00",
    "degradations": [],
    "intentRevisionId": "31f..."
  },
  "inspector": {
    "history": {"summaryUsed": true, "recentCompleteTurns": 8},
    "rewrite": {
      "mode": "MODEL",
      "rewrittenQuestion": "退货需要哪些材料，退款通常多久到账？"
    },
    "subQuestions": [
      {
        "subQuestionId": "d31...",
        "ordinal": 0,
        "text": "退货需要哪些材料？",
        "matches": [
          {
            "nodeId": "6f1...",
            "type": "KB",
            "fullPath": "售后服务 > 退货政策",
            "score": 0.91,
            "rank": 1
          }
        ],
        "actions": [
          {
            "actionId": "f30...",
            "type": "KB_TARGETED",
            "knowledgeBaseId": 12,
            "intentNodeId": "6f1...",
            "reasonCode": "SCORE_AT_OR_ABOVE_TARGETED"
          }
        ]
      }
    ]
  }
}
```

`rewrittenQuestion/subQuestions` 是产品批准的“3A 验收信息”，不是模型思维链。响应不含模型 `reason`、Prompt、
`logicalCallId` 或 Provider 信息。

### 3.2 稳定终态变体

`WAITING_CLARIFICATION`：

```json
{
  "turn": {"turnId":"954...","status":"WAITING_CLARIFICATION","intentRevisionId":"31f..."},
  "assistantMessage": {
    "messageId":"a42...",
    "type":"CLARIFICATION_GUIDANCE",
    "content":"这个问题可能涉及以下几个方向，请选择一个："
  },
  "clarification": {
    "options":[
      {"nodeId":"6f1...","label":"商品售后 > 3C 数码 > 退货政策"},
      {"nodeId":"761...","label":"商品售后 > 服装 > 退货政策"}
    ],
    "allowNoneOfTheAbove":true
  }
}
```

`REJECTED_TOO_MANY_TOPICS` 仍返回 `200` 业务终态，包含固定用户文案；这不是 HTTP 参数错误。`FAILED` 若失败发生在
用户消息成功保存后也返回 `200` 稳定业务终态，便于刷新恢复；只有保存前/事务失败使用 5xx。

### 3.3 幂等与错误

| 场景 | 状态 | code |
|---|---:|---|
| 同一 key、同一语义请求 | 200 | 返回原 turn |
| 同一 key、正文/选择不同 | 409 | `IDEMPOTENCY_KEY_REUSED` |
| 对话已有活动 turn | 409 | `CONVERSATION_BUSY` |
| 对话不存在/已删除 | 404 | `CONVERSATION_NOT_FOUND` |
| 澄清来源不属于对话 | 422 | `INVALID_CLARIFICATION_SOURCE` |
| 选项不在来源 turn | 422 | `INVALID_CLARIFICATION_OPTION` |
| 消息空白/过长 | 422 | `VALIDATION_FAILED` |
| 消息/turn 初始事务失败 | 503 | `MESSAGE_PERSISTENCE_FAILED` |

### 3.4 `GET /api/qa/conversations/{conversationId}/turns/{turnId}`

返回与 `POST /turns` 同形的稳定 turn/inspector。活动状态只可能在客户端请求中断后短暂看到；响应可为
`SAVED/PROCESSING` 并带 `retryAfterMs`，但不虚构百分比。跨对话 turn 返回 404。

## 4. 意图树读取

### 4.1 `GET /api/qa/intent-tree`

一次返回 Route Canvas 所需完整当前快照。第一版写入端限制总节点和 64 KiB 启用叶子 Prompt，因此不做“只加载
半棵树再假装完整画布”。

```json
{
  "revision": {
    "revisionId":"31f...",
    "revisionNo":8,
    "updatedAt":"2026-08-07T11:00:00+08:00"
  },
  "root": {
    "nodeId":"00000000-0000-0000-0000-000000000001",
    "name":"全部意图",
    "virtual":true,
    "editable":false
  },
  "nodes":[
    {
      "nodeId":"cc1...",
      "parentId":"00000000-0000-0000-0000-000000000001",
      "depth":1,
      "sortOrder":0,
      "type":"GROUP",
      "name":"售后服务",
      "description":"运营说明",
      "enabled":true,
      "effectiveEnabled":true,
      "fullPath":"售后服务",
      "childCount":2,
      "enabledLeafCount":2,
      "versionState":"CURRENT"
    }
  ],
  "summary":{"nodeCount":12,"enabledLeafCount":7,"incompleteLeafCount":1}
}
```

`effectiveEnabled` 是服务端按祖先计算的展示字段；它不替代快照中的 `enabled`。默认不返回已删除节点。

### 4.2 `GET /api/qa/intent-tree/bindable-knowledge-bases`

参数 `query`、`cursor`、`limit`，只返回未删除且当前可用的知识库选择项：

```json
{"items":[{"knowledgeBaseId":12,"name":"售后政策","enabled":true,"chunkCount":83}],"nextCursor":null}
```

它是 UI 选择器便利端口，事实仍来自知识库模块；意图树不会复制知识库名称为绑定主键。

## 5. 意图树命令通用规则

所有命令请求必须含 `expectedRevisionId`。成功返回 `200/201` 和同一形态：

```json
{
  "revision":{"revisionId":"9a2...","revisionNo":9,"updatedAt":"..."},
  "changedNodeId":"6f1...",
  "treeSummary":{"nodeCount":13,"enabledLeafCount":8,"incompleteLeafCount":1}
}
```

客户端成功后重新 GET 完整快照或用返回的可选 `nodes` 原子替换；不得把本地单节点 patch 当成服务端已接受的整树。

通用错误：

| 状态 | code | 含义 |
|---:|---|---|
| 409 | `INTENT_TREE_REVISION_CONFLICT` | expected 不是 current；返回 current revision |
| 409 | `INTENT_TREE_CONSTRAINT_VIOLATION` | 父子/启停/目标不变量失败；含字段化 violations |
| 422 | `VALIDATION_FAILED` | 单字段格式/长度错误 |
| 422 | `INTENT_PROMPT_BUDGET_EXCEEDED` | 启用后完整评分 Prompt 超预算 |
| 404 | `INTENT_NODE_NOT_FOUND` | 当前 revision 无该有效节点 |
| 503 | `INTENT_TREE_PERSISTENCE_FAILED` | 新 revision 未提交，不得部分成功 |

### 5.1 `POST /api/qa/intent-tree/nodes`

```json
{
  "expectedRevisionId":"31f...",
  "parentId":"cc1...",
  "type":"KB",
  "name":"清关流程",
  "description":"关于跨境包裹清关材料、申报与异常的问题；区别于国际运费和海外仓入库。",
  "examples":["清关需要哪些材料？"],
  "knowledgeBaseId":12,
  "systemAction":null,
  "promptSnippet":"回答时提醒规则可能因目的地变化。",
  "sortOrder":0
}
```

成功 `201`。新节点强制 `enabled=false`，请求不能绕过配置完整性检查直接启用。根下只允许 GROUP；KB/SYSTEM
父节点不能新增子节点。

### 5.2 `PUT /api/qa/intent-tree/nodes/{nodeId}`

完整替换普通可编辑字段，避免 PATCH 中“null 是清空还是未提供”歧义：

```json
{
  "expectedRevisionId":"31f...",
  "name":"清关流程",
  "description":"...",
  "examples":["..."],
  "knowledgeBaseId":12,
  "systemAction":null,
  "promptSnippet":"...",
  "sortOrder":0
}
```

不能改 `nodeId/parentId/type/enabled`。3A 不支持换父节点或角色转换；要转换必须新建目标节点并删除旧节点。

### 5.3 影响预览

`POST /api/qa/intent-tree/nodes/{nodeId}/impact`：

```json
{"expectedRevisionId":"31f...","operation":"DISABLE_SUBTREE"}
```

响应：

```json
{
  "revisionId":"31f...",
  "operation":"DISABLE_SUBTREE",
  "nodeCount":4,
  "enabledLeafCount":3,
  "boundKnowledgeBaseCount":2,
  "blockingNodes":[]
}
```

预览不锁住 revision；真正命令仍带同一 expected。树已变化则命令 409，让 UI 重新预览。

### 5.4 启停

- `POST /api/qa/intent-tree/nodes/{nodeId}/disable`

```json
{"expectedRevisionId":"31f...","scope":"SUBTREE"}
```

只有 `SUBTREE`，不接受只停父节点。叶子视作大小为 1 的子树。

- `POST /api/qa/intent-tree/nodes/{nodeId}/enable`

```json
{"expectedRevisionId":"31f...","scope":"NODE_ONLY"}
```

GROUP 接受 `NODE_ONLY` 或 `CONFIGURED_SUBTREE`；叶子只接受 `NODE_ONLY`。`CONFIGURED_SUBTREE` 不是“跳过坏节点”，
任何后代不完整则整个操作 409，并在 `violations` 列出 nodeId/path/code。

### 5.5 删除

`DELETE /api/qa/intent-tree/nodes/{nodeId}` 带 JSON body 在部分客户端兼容性差，因此用命令端点：

`POST /api/qa/intent-tree/nodes/{nodeId}/delete`

```json
{"expectedRevisionId":"31f...","scope":"SUBTREE"}
```

成功产生新 revision 并把整个子树标为 deleted+disabled。虚拟根返回 `409 VIRTUAL_ROOT_IMMUTABLE`。

## 6. Inspector 与隐私边界

问答页 Inspector 可以读取：

- 是否使用摘要、近期完整轮次数；
- 模型改写或归一化降级；
- 有序子问题、保留的节点路径与分数；
- 声明式动作与稳定降级码；
- 本轮固定的意图 revision。

它不能读取：

- 摘要全文（聊天页不需要暴露压缩内部状态）；
- Prompt 模板/渲染结果；
- 模型原始 JSON 或评分理由；
- `logicalCallId`、attempt、Provider、token 或采样参数；
- 已删除节点的运营字段。

诊断管理员若要排查模型层，去 `/diagnostics` 使用 Phase 2 的无正文 Trace Console；两页不做正文 join。

## 7. 3C SSE 预留边界（非 3A API）

3A 不创建占位端点或空事件类型，只冻结以下原则：

- `taskId` 到 3C 才创建，不能复用 `turnId` 或 `logicalCallId`。
- SSE 将表达排队、开始、内容、停止、失败、完成等问答业务事件，而非 `ChatStreamEvent` 序列化。
- 3A 的 `ACTIONS_READY/WAITING_CLARIFICATION` 仍是稳定业务终态；3C migration 只能扩展状态机，不能改写历史含义。

## 8. 契约验收

- OpenAPI/DTO 测试证明未知字段拒绝、UUID/长度/枚举边界一致。
- 两次相同幂等提交只产生一套 turn/message/model calls；正文不同返回 409。
- cursor 在相同时间戳下仍无重复/遗漏。
- 所有意图写命令用 expected revision，冲突不产生 revision 或部分节点。
- 影响预览与实际命令在同 revision 下计数一致。
- `WAITING_CLARIFICATION` 只有选项无动作；`ACTIONS_READY` 只有动作无澄清。
- API 响应扫描不出现 Prompt、原始 Provider 错误、secret、完整 request ID、模型 reason 或内部 `ExecutionInfo`。

---

# 3B — 检索与工具（draft，2026-08-08）

> 编排见[架构 §18~§22](architecture.md)，表见[数据模型 §13~§17](data-model.md)，
> 界面表达见 [ui-spec §13](conversation/ui-spec.md)。

## 9. 3B 对既有响应的扩展

`POST /api/qa/turns` 与 `GET .../turns/{turnId}` 的响应体**新增两个可空块**，不改任何既有字段：

```jsonc
{
  "turn": { "...": "3A 字段不变" },
  "inspector": { "...": "3A 字段不变" },

  "retrieval": {                      // turn 未进过 3B 时为 null
    "status": "RETRIEVED",            // RETRIEVING | RETRIEVED | NO_RESULT | RETRIEVAL_FAILED
    "partial": true,
    "rerankApplied": false,
    "degradation": "RERANK_UNAVAILABLE",
    "sources": [
      {
        "ordinal": 0,
        "chunkId": 88123,
        "knowledgeBaseId": 12,
        "knowledgeBaseName": "售后服务知识库",
        "documentId": 517,
        "documentName": "退货与退款操作手册 v3.docx",
        "chunkIndex": 12,
        "documentDeleted": false
      }
    ],
    "failedTargets": [
      { "knowledgeBaseId": 31, "knowledgeBaseName": "海外仓运营手册", "failureCode": "RETRIEVAL_TIMEOUT" }
    ]
  },

  "tools": [
    {
      "toolCallId": "…",
      "ordinal": 0,
      "toolName": "submit-return-ticket",
      "displayName": "提交退货工单",
      "description": "在售后工单系统中新建一张退货工单，并把工单号回写到本次对话。",
      "sideEffect": true,
      "status": "PENDING_CONFIRM",
      "parameters": [
        { "name": "订单号", "value": "ORD-20260806-1183", "required": true }
      ],
      "resultText": null,
      "failureCode": null,
      "latencyMs": null
    }
  ]
}
```

契约要点，逐条对应已冻结的产品语义：

- **没有任何分数字段。** 不返回余弦距离、`ts_rank_cd`、RRF 分数或 `relevanceScore`。
  顺序由 `ordinal` 表达（PRD §12.2）。数据库本来就不存（数据模型 §14.2），这里是第二道闸。
- **不返回 chunk 正文。** 正文另经知识库既有接口按 `chunkId` 取，天然跟随逻辑删除。
  `documentDeleted=true` 时界面显示 `文档已删除` 并禁用跳转（ui-spec §13.3）。
- **不返回命中通道或命中次数。** `origin_channel` 只进处理详情，不进这里——避免"命中两次更可信"
  的错误暗示。
- **`partial` 是服务端算好的显式字段**，客户端不得用 `sources.length` 推断（PRD §12.4）。
  它表达的是"用户拿到的结果不完整"，包含两种情形，见 §9.1。
- **`rerankApplied=false` 时 `degradation` 非空**，反之亦然；与 `com.app.ai` 的 `RerankResult`
  不变量同形。客户端据此显示 `未精排`，**不区分成因**。
- **`description` 来自服务端白名单配置，不是模型生成的**（架构 §21.2）。
- **`parameters` 是复核通过后的参数**，模型编造的字段不出现在这里。
- **`failureCode` 是归一化闭集**，永不透传异常 message（PRD §12.6）。

### 9.1 `status` 与 `partial` 怎么算（裁决 `2026-08-11`）

| 条件 | `status` | `partial` |
|---|---|---|
| 有来源 | `RETRIEVED` | 有目标失败过则 `true` |
| 无来源，且有目标失败、无目标成功 | `RETRIEVAL_FAILED` | `false` |
| 无来源，其余情况 | `NO_RESULT` | `false` |

**有来源就是 `RETRIEVED`，不看目标状态。** 目标只由向量通道产生（[架构 §19.6](architecture.md)），
而关键词通道不产生目标却能召回——无密钥部署里向量通道必然全失败，于是"全部目标失败"与
"用户手上有结果"会同时成立。按旧算法那是 `RETRIEVAL_FAILED`，界面照 ui-spec §13.6 说
"没查成、可重试"，而来源区里明明躺着关键词召回的内容。**这不是边缘情况，是无 Key 部署的常态。**

没有来源时才回到目标状态上分辨"没查成"（可重试）与"查了但没有"（该改问法）——PRD §12.3
要区分的这两件事在无来源这一侧仍然完整。

`partial` 因此扩了一条：**有来源但目标全失败**也算结果不完整。原本它只表达"部分目标失败"。
扩展后它统一表达"用户拿到的结果不完整"：向量全挂、关键词兜住的那一轮，用户确实拿到了结果，
但确实少了一块。目标全失败且没有来源时**不算** `partial`——那是 `RETRIEVAL_FAILED`，
"什么都没有"不是"不完整"。

## 10. `POST /api/qa/turns/{turnId}/tool-calls/{toolCallId}/decision`

有副作用的工具在用户裁决前不执行。确认与取消是同一个端点的两个取值——它们是同一个决定的两面，
拆成两个端点会让"只允许决定一次"这条约束散落在两处。

```jsonc
// 请求
{ "clientRequestId": "…uuid…", "decision": "CONFIRM" }   // CONFIRM | CANCEL

// 响应 200：返回整个 turn 结果（与 §9 同形），调用方直接替换本地状态
```

- 只有 `status=PENDING_CONFIRM` 的工具可被裁决；其余返回 `409 TOOL_CALL_NOT_PENDING`。
  这条同时挡住了重复确认与"取消后又确认"。
- `CONFIRM` 同步执行工具并等待终态返回，与 3A 的 `POST /turns` 传输形态一致；
  3B 不引入 SSE（属 3C）。
- **确认不可复用**：工具失败后重试会产生一条新的 `toolCallId`，必须重新裁决（ui-spec §13.7.4）。
- `clientRequestId` 幂等：同 key 重复提交返回同一结果，不会执行两次。**这条对有副作用的工具是
  硬要求**——网络抖动导致的重试不该变成两张工单。

## 11. `POST /api/qa/turns/{turnId}/retrieval/retry`

只重跑失败的检索目标，不重走改写与意图（ui-spec §13.6）。

```jsonc
// 请求
{ "clientRequestId": "…uuid…" }

// 响应 200：整个 turn 结果（与 §9 同形），partial 随之重算
```

- 只有存在 `FAILED` 目标时可调用；否则 `409 NOTHING_TO_RETRY`。
- **不新建 turn、不重新评分、不重问歧义。** 用户已经确认过的方向不能因为一次重试被重问。
- 重放是幂等的：成功的目标不再查询，结果合并进同一 turn 的来源集合。

## 12. 3B 错误码

| HTTP | code | 何时 |
|---|---|---|
| 409 | `TOOL_CALL_NOT_PENDING` | 工具不在 `PENDING_CONFIRM`，含重复确认与取消后确认 |
| 409 | `NOTHING_TO_RETRY` | 没有失败目标可重试 |
| 404 | `TOOL_CALL_NOT_FOUND` | `toolCallId` 不属于该 turn |

`NO_RESULT` **不是错误**，它是 200 + `retrieval.status=NO_RESULT`。检索执行成功而没有命中，
与检索没执行成功是两件事，HTTP 状态码上也要分开（PRD §12.3）。

### 12.1 工具执行失败不是 HTTP 错误（裁决 `2026-08-11`）

**工具这一次没成，返回 200 + `tools[].status=FAILED` + `failureCode`**，不返回 4xx/5xx。

本节起草时把 `TOOL_ARGUMENTS_INVALID`(422) / `TOOL_EXECUTION_FAILED`(502) /
`TOOL_EXECUTION_TIMEOUT`(504) 列进了上面那张表，那是错的，两处矛盾：

1. **与 §10 冲突。** §10 承诺"响应 200：返回整个 turn 结果，调用方直接替换本地状态"。
   502 时调用方拿不到 turn，两条不能同时成立。
2. **它们本来就不是 HTTP 错误码，而是 `ToolFailureCode` 的枚举值**
   （`ARGUMENTS_INVALID` / `NOT_WHITELISTED` / `EXECUTION_TIMEOUT` / `EXECUTION_FAILED`），
   落在 `qa_tool_call.failure_code` 上。把状态机里的一个终态搬到 HTTP 层，等于要求调用方
   从状态码反推工具状态。

裁决与 `NO_RESULT` 同源：**"这一次没成"是结果的一部分，不是请求失败**。用户要看到的是那张
确认卡变成失败态、带一句归一化原因、可以重试；而不是一个 502 把整个 turn 的其余内容
（来源、其他工具）一起吞掉。

HTTP 错误只保留"请求本身不该被处理"的情况——裁决时机不对（409）、资源不属于该 turn（404）。

实现自工单 10 起就是这样（`ToolCallService.executeOutsideTransaction` 把异常包成
`ToolExecutionResult.failure`，controller 返回 `ok`）。本次是**文档跟上实现**，不是改行为。

### 12.2 检索全失败同样不是 HTTP 错误（裁决 `2026-08-11`）

**全部检索目标失败，返回 200 + `retrieval.status=RETRIEVAL_FAILED`**，不返回 502。

本节起草时把它列为 502 `RETRIEVAL_FAILED`，与 §12.1 的三条同源同错：`RETRIEVAL_FAILED` 是
`qa_turn_retrieval.status` 的取值（与 `RETRIEVING` / `RETRIEVED` / `NO_RESULT` 同一个闭集），
不是 HTTP 码。

用 502 表达它会立刻撞上一个自相矛盾：`NO_RESULT` 是 200（本节开头那条早就定了），而
`RETRIEVAL_FAILED` 与它是**同一个状态字段的两个取值**、也是同一条产品语义要求区分的两件事
（PRD §12.3：一个"查了但没有"该改问法，一个"没查成"可重试）。让其中一个走状态、另一个走
HTTP 码，客户端就得从两个地方拼出同一个字段。

还有一层与工具那条相同的代价：`RETRIEVAL_FAILED` 时这一轮的**工具结果、理解结果都还在**，
502 会把它们一起吞掉。而 `partial=true` 那种"部分知识库失败"本来就是 200 + 状态——
"全都没查成"只是同一根轴上更极端的一点，不该在某个阈值上突然换成另一种传输语义。

实现已经是这样（`RetrievalOrchestrator` 把目标全失败算成 `RETRIEVAL_FAILED` 写进
`qa_turn_retrieval`，工单 08b 的接入点不把它上升成 turn 的 FAILED）。同样是文档跟上实现。

## 13. 3B 契约验收

- 响应体扫描不出现任何分数字段、chunk 正文、通道名、Schema 原文与异常 message。
- `partial`、`rerankApplied`、`status` 三者与数据库状态一致，且都不可由条数推断。
- 同一 turn 的 `sources` 里 `chunkId` 不重复。
- 有副作用的工具在未裁决时，任何路径都不产生外部调用；重复 `CONFIRM` 只执行一次。
- 重试端点不改变 turn 的理解结果字段（子问题、意图、动作、歧义选择）。
- 3A 既有响应在 turn 未进过 3B 时逐字段不变，`retrieval` 与 `tools` 为 null / 空数组。
