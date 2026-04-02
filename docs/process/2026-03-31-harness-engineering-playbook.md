# xRag Harness Engineering Playbook

**日期：** 2026-03-31  
**状态：** active  
**适用范围：** `xRag` 正式工程开发、agent 协作、文档治理、CI/CD  
**参考：** OpenAI, [Harness engineering: leveraging Codex in an agent-first world](https://openai.com/index/harness-engineering/)

---

## 1. 这份文档解决什么问题

`Harness Engineering` 在这个项目里，不是一个空概念，而是一套明确的开发方法：

- 人负责定边界、定标准、定验收
- agent 负责实现、验证、补文档、修反馈
- repo 成为唯一事实来源
- 质量门禁、可观测性、执行计划都做成可机检资产

目标不是“让 agent 自动乱写”，而是让 `xRag` 的研发流程变得：

- 更快
- 更稳定
- 更可回放
- 更容易持续交接

---

## 2. xRag 的核心原则

### 2.1 Humans steer, agents execute

- 产品和架构边界由人定
- 代码、测试、CI、文档、脚本尽量由 agent 执行
- agent 卡住时，不是让它“再试试”，而是补环境、补规则、补工具

### 2.2 Repo is the system of record

以下信息必须进 repo：

- 需求边界
- 架构设计
- 数据模型
- API 设计
- 执行计划
- 质量标准
- 变更历史

如果知识只存在于聊天、口头同步、截图里，对 agent 来说就等于不存在。

### 2.3 Agent legibility 优先

选型和结构优先考虑：

- 可读
- 可推理
- 可测试
- 可自动验证

所以 `xRag` 当前推荐的是：

- 结构清晰的 monorepo
- 显式模块边界
- 稳定、主流、行为可预测的技术栈

### 2.4 约束要机械化

文档只讲原则不够，必须变成：

- CI 检查
- 结构测试
- OpenAPI diff
- link check
- smoke test

---

## 3. 在 xRag 里怎么落地

### 3.1 入口资产

- [AGENTS.md](/Users/coderlauu/xRag/AGENTS.md)
- [当前 Handoff](/Users/coderlauu/xRag/docs/handoff/current.md)
- [当前版本状态](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md)
- [Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)
- [Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md)
- [API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- [Project Skill: xrag-iteration-governor](/Users/coderlauu/xRag/.codex/skills/xrag-iteration-governor/SKILL.md)
- [Version Lifecycle](/Users/coderlauu/xRag/docs/process/version-lifecycle.md)

### 3.2 计划资产

新增目录：

```text
docs/status/
docs/exec-plans/active/
docs/exec-plans/completed/
docs/generated/
```

规则：

- 每个活动版本必须有一份 `docs/status/*.md`
- 大于半天的开发项，必须先写执行计划
- 完成后从 `active` 挪到 `completed`
- 自动生成资产统一放 `docs/generated/`

### 3.3 代码结构资产

正式工程开始后，统一按以下结构推进：

```text
apps/web
apps/api
apps/worker
packages/api-client
packages/shared-types
packages/ui
```

约束：

- 前端只能通过 API client 访问后端
- 后端模块按 `controller -> service -> repo` 分层
- worker 不直接承载 HTTP 逻辑
- 上传文件不进数据库，只进对象存储

---

## 4. 清晰流程

### 4.1 阶段 0：任务进入

输入：

- PRD / issue / 用户反馈 / bug

输出：

- 一个可执行的问题定义

标准：

- 明确目标用户
- 明确是否属于当前活跃版本
- 明确 In / Out of Scope

### 4.2 阶段 1：写执行计划

输入：

- 已确认范围的任务

输出：

- `docs/exec-plans/active/*.md`

标准：

- 写清目标、范围、风险、接口影响、测试计划、回滚点
- 复杂任务拆里程碑，不允许一句话直接开干
- 如果任务属于当前版本主链路，同时更新该版本 `status` 中的当前节点与里程碑状态

### 4.3 阶段 2：agent 实现

输入：

- 执行计划
- `current.md`
- 对应 `tech/` 文档

输出：

- 代码
- 测试
- 文档更新

标准：

- 代码改动必须与计划一致
- 如偏离原计划，先更新计划和文档

### 4.3.1 并行实施规则

并行开发不是默认动作，先满足这两个前提：

- 主线程已经冻结 `schema / shared-types / OpenAPI / API contract / 状态机`
- 每个 lane 的写入边界已经明确

推荐执行方式：

1. 主线程先完成上游事实源和抽象接口
2. 再把 `web`、`worker`、`upload/storage`、`testing/ci` 等下游实现交给子 agent 并行
3. 主线程负责集成、冲突处理、最终验证和状态更新

子 agent 默认不允许改动：

- API 路径
- 返回字段命名
- 状态枚举
- 主数据模型字段语义

出现以下情况时，应立即切回主线程收口，而不是继续并行扩散：

- lane 开始修改 contract
- 多个 lane 对同一目录或同一模块产生写冲突
- 本地集成验证失败且责任边界不清
- agent 卡在分析层，无法形成可落地代码变更

### 4.4 阶段 3：agent 自检

输入：

- 本地变更

输出：

- 可读的验证结果

标准：

- 至少跑相关层级测试
- UI 变更必须给截图或录屏证据
- API 变更必须更新 OpenAPI

### 4.5 阶段 4：PR 与评审

输入：

- 一组自洽的变更

输出：

- 可合并 PR

标准：

- PR 只做一件事
- PR 描述必须带：
  - 目标
  - 改动范围
  - 验证方式
  - 风险
  - 回滚方式

### 4.6 阶段 5：CI/CD 与发布

输入：

- 已通过评审的 PR

输出：

- staging 或 production 版本

标准：

- CI 必须全绿
- staging 部署后必须跑 smoke test
- 失败可自动回滚

### 4.7 阶段 6：文档清理与债务回收

输入：

- 已合并变更

输出：

- 更新后的知识库
- 新增技术债记录

标准：

- 行为变化必须同步更新文档
- 发现无效文档要删或修，不能堆积
- 版本进入下一阶段时，要同步更新 `status` 与 `current.md`

---

## 4.8 版本资产规则

每个版本默认维护以下固定资产：

- `docs/handoff/vN.md`
- `docs/status/vN-*.md`
- `docs/exec-plans/active/*.md`
- 受影响的 `tech/*`

职责分工：

- `handoff`：版本入口、边界、阅读顺序
- `status`：当前节点、阻塞项、最近验证
- `exec plan`：具体执行、验证、回滚
- `tech`：架构、数据模型、API 事实来源

状态流转统一遵循 [Version Lifecycle](/Users/coderlauu/xRag/docs/process/version-lifecycle.md)。

---

## 5. 明确标准

### 5.1 需求标准

- 每个任务都必须有清晰输入、输出、验收标准
- 不允许“先写出来看看再说”成为默认模式

### 5.2 架构标准

- 搜索、上传、解析、标签、详情必须模块分离
- 异步任务必须通过 queue 驱动
- 数据库、对象存储、队列的边界必须稳定

### 5.3 API 标准

- 资源命名稳定
- 错误码可机读
- 所有边界输入必须校验
- OpenAPI 必须可生成

### 5.4 测试标准

- 单元测试覆盖纯逻辑
- 集成测试覆盖数据库、队列、对象存储边界
- E2E 覆盖用户闭环
- smoke test 覆盖部署后最小路径

### 5.5 文档标准

- `current.md` 只做入口和摘要
- 详细规范写进对应 `tech/` 或 `docs/process/`
- 任何行为变化都必须更新文档

### 5.6 观测标准

- 结构化日志
- 每个请求有 `request_id`
- 每个任务有 `job_id`
- 文档主对象有 `document_id`

---

## 6. DoD: Definition of Done

一个开发项完成，至少满足：

1. 需求边界清晰且已入库
2. 实现与执行计划一致
3. 测试通过
4. 文档已更新
5. CI 通过
6. staging 已验证
7. 有明确回滚路径

少一个，都不算真正完成。

---

## 7. 适合 xRag 的节奏

### 每次功能开发

- 更新 handoff 或 exec plan
- agent 开发
- agent 自检
- PR
- CI
- staging smoke

### 每周一次

- 文档清理
- 技术债盘点
- 失败任务复盘
- 测试薄弱点补齐

### 每个版本结束

- 归档 `completed` exec plan
- 更新 `current.md`
- 更新质量短评和待办

---

## 8. 这个项目里我会怎么用

如果由我继续推进 `xRag`，我的默认工作方式会是：

1. 用 `current.md` 作为所有任务的统一入口
2. 每个中等以上任务先补一个 exec plan
3. 开发时优先把脚手架、测试、CI、文档一起建，不先堆业务代码
4. 所有接口、表结构、状态流先文档化，再开始大规模实现
5. 每次提交保持单一目标，方便 agent 自检和回滚
6. 定期做文档清理和结构治理，避免 agent 放大坏模式

---

## 9. 结论

对 `xRag` 来说，`Harness Engineering` 最有价值的地方不是“自动写代码”，而是：

- 把需求、架构、接口、流程、验证都做成 repo 内资产
- 让 agent 能看见、能执行、能验证、能修复
- 让项目在后续多人和多 agent 协作时仍然保持可控

这套方法非常适合当前这个从原型走向正式工程的阶段。
