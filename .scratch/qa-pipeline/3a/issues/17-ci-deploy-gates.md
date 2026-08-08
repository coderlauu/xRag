# 17 — CI、部署配置与 3A 门禁

**What to build:** 把 3A 的确定性自动化、前后端检查、文档链接和非敏感运行配置纳入现有 CI/部署说明；不引入收费 Key。

**Blocked by:** 01~16
**Status:** blocked

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
