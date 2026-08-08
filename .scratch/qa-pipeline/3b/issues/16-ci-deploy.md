# 16 — CI、部署配置与 3B 门禁

**What to build:** 把 3B 接入 `ci-validate.sh` 与部署配置登记。

**Blocked by:** 01~15
**Status:** blocked

## Slice

- CI 默认假检索、假 Rerank、假工具；真实链路由显式环境变量守门。
- 登记 Hikari 池、检索并行度、topK、rerank 输入上限、工具白名单来源与超时。
- 部署 README 写明回填开关与"换词典 = 全表重建"。

## Acceptance

- [ ] `scripts/ci-validate.sh` 全绿。
- [ ] 收费 API 不成为 CI 通过条件。
- [ ] `check-deploy-contract.mjs` 覆盖 3B 新增配置项。
- [ ] 部署文档含换词典的重建步骤，不只写在代码注释里。
