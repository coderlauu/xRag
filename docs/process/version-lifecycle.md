# xRag Version Lifecycle

**日期：** 2026-04-01  
**状态：** active  
**适用范围：** 每个版本 handoff、状态跟踪、执行计划、归档与恢复开发

---

## 1. 目的

定义 `xRag` 每个版本从启动到归档的固定流程，避免每次新版本都重新发明一套方法。

本文件回答四个问题：

- 一个版本何时算开始
- 一个版本必须具备哪些仓库内资产
- 版本状态如何流转
- 以后如何快速 resume 开发

---

## 2. 版本最小资产集

每个版本默认至少维护以下资产：

1. `docs/handoff/vN.md`
   - 版本入口、边界、阅读顺序
2. `docs/status/vN-*.md`
   - 当前做到哪、卡在哪、下一步是什么
3. `docs/exec-plans/active/*.md`
   - 具体实现计划
4. 受影响的 `tech/*`
   - 架构、数据模型、API 等事实来源

规则：

- `handoff` 负责入口与边界
- `status` 负责进度与当前节点
- `exec plan` 负责执行方案、验证与回滚
- `tech` 负责真实设计事实

---

## 3. 版本状态枚举

### 3.1 版本级状态

- `not-started`
- `in-progress`
- `blocked`
- `completed`
- `archived`

### 3.2 里程碑状态

- `not-started`
- `in-progress`
- `blocked`
- `completed`

### 3.3 验证状态

- `not-run`
- `passed`
- `failed`

不要在版本状态文件里发明新词。统一使用以上枚举，便于人和 agent 一起维护。

---

## 4. 生命周期阶段

### 4.1 New

触发条件：

- 新需求进入，且确认需要独立版本或明确版本范围

必须动作：

- 创建或确认 `docs/handoff/vN.md`
- 创建 `docs/status/vN-*.md`
- 必要时创建首个 `docs/exec-plans/active/*.md`
- 更新 `docs/handoff/current.md`

### 4.2 Active

触发条件：

- 已有明确边界，开发开始推进

必须动作：

- 维护 `status` 里的当前节点、阻塞项、最近验证
- 每个中型及以上任务维护 `exec plan`
- 行为、架构、API 变化同步更新 `tech/` 或对应行为文档

### 4.3 Blocked

触发条件：

- 关键依赖未满足，继续编码只会返工

必须动作：

- 在 `status` 中写清 blocker、impact、owner
- 停止盲目实现，先解决边界、环境或设计问题

### 4.4 Completed

触发条件：

- 版本目标已完成，验证与发布都结束

必须动作：

- 更新 `status` 为 `completed`
- 把相关 `exec plan` 从 `active` 移到 `completed`
- 清理过期说明和临时约束

### 4.5 Archived

触发条件：

- 项目已进入下一个版本，当前版本不再是主推进对象

必须动作：

- 保留 `handoff`、`status`、`exec plan`
- `current.md` 指向新版本
- 历史版本资产不覆盖，只归档

---

## 5. Resume 最小阅读集

以后恢复开发时，默认先读这三类文件：

1. `docs/handoff/current.md`
2. 当前版本 `docs/status/*.md`
3. 当前活跃或最近完成的 `docs/exec-plans/*.md`

如果需要再深入：

4. 受影响的 `tech/*`
5. 最近关键 commit / CI run

---

## 6. 与 Skill 的关系

项目级 skill `xrag-iteration-governor` 应遵循本生命周期：

- 判断需求分类
- 生成 handoff / status / exec plan
- 更新 `current.md`
- 在版本结束时推动归档

也就是说，skill 不保存项目事实，只负责编排这套标准化流程。
