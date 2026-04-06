# CI 自动修复闭环方案

**日期：** 2026-04-06  
**状态：** implemented-v1  
**适用范围：** `xRag` 的 GitHub Actions 失败归档、低风险自动修复、修复 PR 生成

---

## 1. 目标

在现有 `CI Failure Loop` 的基础上，补一层“自动修复调度器”，把闭环从：

- 失败发现
- 上下文归档

推进到：

- 失败发现
- 上下文归档
- 失败类型分类
- 对低风险错误自动修复
- 自动验证
- 自动开修复 PR

本方案明确不直接自动合并到 `main`，也不直接自动发布 production。

---

## 2. 分层架构

### 2.1 Failure Collector

职责：

- 监听 `CI` 失败
- 抓取 failed run / job / step / logs / sha
- 生成 `incident.json`、`incident.md`、`failed.log`
- 创建 `ci-failure` issue

当前现状：

- 已实现
- workflow: [ci-failure-loop.yml](/Users/coderlauu/xRag/.github/workflows/ci-failure-loop.yml)

### 2.2 Repair Dispatcher

职责：

- 读取 failure artifact 或重新拉取失败 run 上下文
- 判断失败是否属于可自动修复类型
- 为支持的失败类型执行标准修复 playbook
- 运行最小验证
- 推出修复分支并创建 PR

当前版本实现目标：

- 已实现第一版 `repair-dispatch`
- 当前只支持低风险、规则型修复

### 2.3 Merge Gate

职责：

- 对自动修复 PR 保持人工确认
- 审核后再合并到 `main`
- 合并后由原有 `CI/CD` 负责构建与部署

说明：

- 当前版本不做无确认自动 merge
- 当前版本不做自动 production 发布

当前实现入口：

- workflow: [ci-auto-repair.yml](/Users/coderlauu/xRag/.github/workflows/ci-auto-repair.yml)
- dispatcher: [run-ci-auto-repair.sh](/Users/coderlauu/xRag/scripts/run-ci-auto-repair.sh)
- classifier: [classify-ci-repair.sh](/Users/coderlauu/xRag/scripts/classify-ci-repair.sh)
- rule fix: [fix-search-e2e-selector-drift.mjs](/Users/coderlauu/xRag/scripts/fix-search-e2e-selector-drift.mjs)

---

## 3. 支持的自动修复类型

第一版只支持确定性高、可机械修复的错误：

1. `openapi_contract_out_of_date`
- 特征：失败日志包含 `Generated OpenAPI artifact is out of date`
- 修复动作：执行 `pnpm contract:generate`
- 验证：执行 `pnpm contract:check`

2. `outdated_lockfile`
- 特征：失败日志包含 `ERR_PNPM_OUTDATED_LOCKFILE`
- 修复动作：执行 `pnpm install --no-frozen-lockfile`
- 验证：执行 `pnpm install --frozen-lockfile`

3. `search_page_e2e_selector_drift`
- 特征：失败日志包含 `getByLabel('Search documents')` 或 `name: "Search"`
- 修复动作：
  - 给搜索页补稳定 selector
  - 把 `Playwright` 用例从文案选择器切到稳定 selector
- 验证：执行 `pnpm --filter @xrag/web typecheck`

---

## 4. 暂不自动修复的类型

以下类型仅归档，不自动修复：

- registry timeout
- SSH / Docker 权限
- deploy / smoke-production 失败
- DNS / TLS / 反向代理错误
- 需要产品判断的行为变化
- 超出单文件或单类规则修复范围的 E2E 失败

原因：

- 这类问题要么不在 repo 内
- 要么修复需要上下文判断
- 要么风险过高，不适合由 GitHub Actions 直接自治

---

## 5. 运行流程

1. `CI` 在 `main` 上失败
2. `CI Failure Loop` 收集上下文并创建 incident
3. `CI Auto Repair` 监听同一个失败 run
4. 调度脚本按日志分类失败类型
5. 若属于支持类型：
   - checkout 失败 sha
   - 执行标准修复
   - 跑最小验证
   - 推送 `auto-repair/*` 分支
   - 创建 PR
6. 若不属于支持类型：
   - 输出 “unsupported repair type”
   - 结束，不改代码

---

## 6. 安全边界

### 6.1 允许

- 创建新分支
- 提交修复
- 创建 PR
- 上传修复摘要 artifact

### 6.2 不允许

- 直接 push 到 `main`
- 自动 merge
- 自动触发 production deploy
- 自动修改部署 secrets 或远端环境

---

## 7. 产物

自动修复 workflow 应产出：

- `repair-summary.json`
- `repair-summary.md`
- 自动修复分支
- 自动修复 PR

建议 PR 标题格式：

- `[Auto Repair] <repair_type> for CI run <run_id>`

---

## 8. 验证方式

### 本地

- 用一组已知失败日志跑分类脚本
- 验证支持类型能落到正确 repair type

### GitHub

- 制造一次规则型失败
- 确认：
  - `CI Failure Loop` 生成 incident
  - `CI Auto Repair` 生成修复分支和 PR

---

## 9. 下一阶段扩展

后续可扩展为：

- 调用外部常驻 agent service
- 支持更多 repo-aware 修复类型
- 自动给 incident issue 回填 repair PR 链接
- 对极少数低风险类型启用自动 merge
