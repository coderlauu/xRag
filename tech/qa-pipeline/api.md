# API 契约：AI 知识问答核心链路（Phase 3A）

- `status`: approved
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
