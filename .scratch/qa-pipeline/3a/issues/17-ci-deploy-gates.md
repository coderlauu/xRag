# 17 — CI、部署配置与 3A 门禁

**What to build:** 把 3A 的确定性自动化、前后端检查、文档链接和非敏感运行配置纳入现有 CI/部署说明；不引入收费 Key。

**Blocked by:** 01~16
**Status:** complete

## Slice

- `scripts/ci-validate.sh` 覆盖 backend verify、frontend test/build/lint、doc links。
- CI workflow 运行全部 3A `A` 项，使用可编程假 Chat/隔离 PostgreSQL schema。
- env/deploy 文档登记 3/3、8/12、65536、stale timeout 和阈值；Prompt 仍在 classpath。
- 记录单实例缓存/许可边界、无 Key 行为和 rollback，不提交秘密。

## Acceptance

- [ ] 3A-E2E-08 通过；本地与 CI 命令一致且可在无公网/无收费 Key 环境运行。
- [ ] env example 无 secret 值、Prompt 正文或供应商专属采样参数。
- [ ] `node scripts/check-doc-links.mjs`、`git diff --check` 和现有模块回归进入完成证据。
- [ ] 工单只在 16 的实际矩阵结果已回写后关闭，不替 Step D/E 用户确认。

**Design:** [implementation plan §8](../../../../docs/exec-plans/active/2026-08-07-qa-pipeline-3a-implementation-lanes.md)

## Evidence（2026-08-08）

实质工作已完成，**但按 Acceptance 第四条不能关闭**：16 条 `M` 项尚未走查，不能替 Step D/E 用户确认。

| Slice | 结果 |
|---|---|
| `ci-validate.sh` 覆盖 backend verify / frontend test·build·lint / doc links | 已补 `pnpm lint`——此前只跑 build 与 test，是真实缺口；CLAUDE.md 的流水线描述一并修正 |
| CI workflow 跑全部 3A `A` 项、隔离 schema、假 Chat | **无需改动**：`.github/workflows/ci.yml:97` 直接调用 `ci-validate.sh`，lint 自动生效；集成测试各自建独立 schema；CI 无任何密钥，5 项真实 API 用例自动跳过 |
| env/deploy 登记 3/3、8/12、65536、stale timeout 与阈值 | `deploy/README` 新增「问答核心链路（Phase 3A）运维须知」五节，含 11 个 `QA_*` 变量的默认值、含义与启动校验偏序 |
| 记录单实例缓存/许可边界、无 Key 行为与 rollback | 同上第 1、3、4 节 |
| 3A-E2E-08 | passed，见测试矩阵 §15 |

两个刻意的判断：

- **`QA_*` 不进 `backend/.env.example`。** 那是密钥模板（只列两个变量名、无值），无密级且带默认值的
  运行参数登记在 `deploy/README` 才对；混进密钥模板会让人以为它们也是要保密的。
- **Prompt 正文不进环境变量。** 它们是需要评审的产物，复制成环境变量既丢 review，也会在部署面板里
  泄露给不该看到的人。改 Prompt 走发版。
