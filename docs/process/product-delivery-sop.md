# xRag Product Delivery SOP

**适用范围：** 本 SOP 适用于 `xRag` 的所有版本迭代，从需求定义到技术方案准备、coding handoff、验证与归档。  
**当前基线：** `v1 / Phase 1A`

---

## 1. 目标

这份 SOP 用来解决两个问题：

1. 每次版本迭代都按同一条主线推进，减少“原型做完但资料散落”的情况。
2. 让后续技术方案和 coding 阶段有稳定输入，而不是只依赖页面截图或口头解释。

标准流程如下：

`PRD -> Scope Decision -> Prototype -> Interaction Spec -> Tech Plan -> Coding Handoff -> Verification -> Retro`

---

## 2. 版本命名规则

每个版本必须先确定一个统一版本标识，例如：

- `v1`
- `v2`
- `phase-1b`
- `search-revamp-v1`

同一轮迭代的文档、原型、技术方案，都应围绕同一个版本标识命名，避免后续难以对应。

推荐命名格式：

- PRD：`docs/prd/YYYY-MM-DD-<topic>-prd.md`
- Scope/Tradeoff：`docs/decisions/YYYY-MM-DD-<version>-scope-and-tradeoffs.md`
- Interaction Spec：`design/spec/YYYY-MM-DD-<version>-interaction-spec.md`
- Tech Plan：`tech/<area>/YYYY-MM-DD-<version>-<topic>.md`
- Prototype：`prototype/<version>/`

---

## 3. 阶段 1：需求定义

### 输入

- 用户目标或版本目标
- 现有产品背景
- 上一版本复盘与决策记录

### 必做动作

1. 明确本轮版本目标和非目标。
2. 列出核心用户场景。
3. 约束 P0 / P1 / 不做范围。
4. 记录成功指标与最小验收标准。

### 输出物

- 一份正式 PRD，落到 `docs/prd/`

### 完成标准

- PRD 中能回答以下问题：
  - 为什么做这个版本
  - 为谁做
  - 本期必须做什么
  - 明确不做什么
  - 如何判断版本是否完成

---

## 4. 阶段 2：Scope Decision

### 输入

- 当前版本 PRD
- 已知资源限制
- 当前实现成熟度

### 必做动作

1. 把“这次真正要交付的范围”单独从 PRD 中抽出来。
2. 记录所有重要取舍，尤其是：
   - 为何不做某些能力
   - 哪些能力延后
   - 哪些地方使用 mock / fake data / prototype-only 行为
3. 记录与既有设计/ADR 的关系。

### 输出物

- 一份 `scope-and-tradeoffs` 文档，落到 `docs/decisions/`

### 完成标准

- 后续任何人只看这份文档，就能知道当前版本到底实现到哪一步。

---

## 5. 阶段 3：原型设计

### 输入

- PRD
- Scope/Tradeoff 文档

### 必做动作

1. 在 `prototype/<version>/` 下创建版本目录。
2. 页面、样式、脚本、mock data 分层组织。
3. 原型必须可直接运行与演示。
4. 页面至少覆盖当前版本的完整主流程。

### 输出物

- 可运行 HTML 原型
- `prototype/README.md` 中的版本说明

### 完成标准

- 原型能完整走通主链路。
- 所有关键状态有可见反馈。
- 目录结构可支撑后续 `v2 / v3` 并行迭代。

---

## 6. 阶段 4：Interaction Spec

### 输入

- 当前版本原型
- PRD 与 Scope/Tradeoff 文档

### 必做动作

1. 为每个页面记录目标、主要模块、交互规则和异常状态。
2. 明确原型中的状态流和字段语义。
3. 标注哪些行为是原型演示行为，哪些应被后续真实实现承接。

### 输出物

- 一份交互说明文档，落到 `design/spec/`

### 完成标准

- 前端、后端、产品、设计都能基于此文档理解页面行为。
- 技术方案阶段可以直接从这份文档提炼数据对象与接口需求。

---

## 7. 阶段 5：技术方案准备

### 输入

- PRD
- Scope/Tradeoff
- Interaction Spec
- Prototype

### 必做动作

1. 在 `tech/architecture/` 中定义系统边界、模块分层和依赖关系。
2. 在 `tech/data-model/` 中定义业务模型、存储模型、检索模型。
3. 在 `tech/api/` 中定义页面需要的接口、输入输出和异常语义。
4. 说明哪些原型能力是 mock，真实实现如何替代。

### 输出物

- 架构设计文档
- 数据模型文档
- API 设计文档

### 完成标准

- 技术方案能明确回答：
  - 页面依赖哪些数据对象
  - 哪些状态需要异步任务
  - 哪些字段是必须字段
  - 哪些接口负责读，哪些负责写

---

## 8. 阶段 6：Coding Handoff

### 输入

- 全部前置文档
- 当前版本原型

### 必做动作

1. 编制开发 handoff 清单。
2. 明确本轮 coding 不得偏离的边界。
3. 对每个模块指定“对应来源文档”。

### 输出物

- 一份 coding handoff 清单，通常附着在技术方案或计划文档中

### 完成标准

- 开发开始前，至少具备以下材料：
  - PRD
  - Scope/Tradeoff
  - Interaction Spec
  - Architecture / API / Data Model
  - 验收标准

---

## 9. 阶段 7：验证与回写

### 输入

- 开发结果
- 验收结果
- 新的设计或技术调整

### 必做动作

1. 验证是否符合当前版本范围。
2. 若实现与原型/文档有偏差，必须回写到对应文档。
3. 新的产品取舍或技术取舍需补 ADR 或决策记录。

### 输出物

- 更新后的决策记录
- 更新后的交互说明或技术文档
- 验证记录

### 完成标准

- 代码、原型、文档三者不互相冲突。

---

## 10. 阶段 8：复盘与归档

### 输入

- 当前版本交付结果
- 验证与上线反馈

### 必做动作

1. 总结哪些做得对，哪些需要改。
2. 记录本版本遗留问题和下轮建议。
3. 归档到 `docs/retro/`。

### 输出物

- 一份复盘文档

### 完成标准

- 下一轮版本能直接从复盘中继承经验，而不是重复踩坑。

---

## 11. 每次版本迭代的最小交付清单

每个版本至少应补齐以下材料：

- [ ] `docs/prd/...-prd.md`
- [ ] `docs/decisions/...-scope-and-tradeoffs.md`
- [ ] `design/spec/...-interaction-spec.md`
- [ ] `prototype/<version>/`
- [ ] `tech/architecture/...`
- [ ] `tech/data-model/...`
- [ ] `tech/api/...`
- [ ] 验收与回写记录
- [ ] `docs/retro/...`

---

## 12. v1 基线说明

从 `v1 / Phase 1A` 开始，后续版本统一按本 SOP 执行。

`v1` 的对应基线文档：

- [Phase 1A PRD](/Users/christina/Documents/xRag/docs/prd/2026-03-31-xrag-phase-1a-prd.md)
- [v1 Scope 与取舍](/Users/christina/Documents/xRag/docs/decisions/2026-03-31-v1-scope-and-tradeoffs.md)
- [v1 交互说明](/Users/christina/Documents/xRag/design/spec/2026-03-31-v1-interaction-spec.md)

