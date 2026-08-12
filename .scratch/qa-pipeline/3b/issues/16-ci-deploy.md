# 16 — CI、部署配置与 3B 门禁

**What to build:** 把 3B 接入 `ci-validate.sh` 与部署配置登记。

**Blocked by:** 01~15
**Status:** complete（`2026-08-11`）——本工单四条 Acceptance 全部满足；
名义前置 15 仍是 in-progress（9 条手工走查），但它与本工单的产出无关

## Slice

- CI 默认假检索、假 Rerank、假工具；真实链路由显式环境变量守门。
- 登记 Hikari 池、检索并行度、topK、rerank 输入上限、工具白名单来源与超时。
- 部署 README 写明回填开关与"换词典 = 全表重建"。

## Acceptance

- [x] `scripts/ci-validate.sh` 全绿。exit=0，后端 638 tests、前端 69 tests。
- [x] 收费 API 不成为 CI 通过条件。**本就成立，本次给它加了检查**，见下。
- [x] `check-deploy-contract.mjs` 覆盖 3B 新增配置项。
- [x] 部署文档含换词典的重建步骤，不只写在代码注释里。**早已存在**（deploy/README 的
      "换分词词典 = 重建 `document_chunk_keyword` 全表"一节）。

## 执行记录（`2026-08-11`）

开工时先核对三条 Slice 的真实状态，结果**两条早就做完了**：

| Slice | 实际状态 |
|---|---|
| CI 默认假实现、真实链路由环境变量守门 | 已成立。`Real*ApiTests` 带 `@EnabledIfEnvironmentVariable` |
| 登记池/并行度/topK/rerank 上限/工具白名单 | 已登记（deploy/README 的问答配置表与工具白名单一节） |
| 部署文档写明回填开关与"换词典 = 全表重建" | 已写 |

所以本工单的实质产出只有一件：**把这些已经成立的约定变成脚本会拦的门禁**。约定靠人记得，
门禁靠 CI 记得——前者会随着人员和时间流失。

### 新增两项检查

**① 问答配置必须在部署文档有登记条目。** 漏配的症状是运维照默认值跑，而默认值恰恰是给
开发机定的（检索并行度 4 与连接池 10 的关系就是个例子）。

只覆盖 `QA_` 前缀**不是偷懒**：`application.properties` 里 49 个环境变量目前有 32 个未登记
（`STORAGE_*`、`UPLOAD_*`、`KNOWLEDGE_*`、`AI_DIAGNOSTICS_*` 等），一次性全量强制会让 CI
立刻红一片，那是另一件事的范围。`QA_*` 当前全部登记过，所以这条检查现在就是绿的。
**扩大覆盖前要先补齐那 32 条文档，不要反过来把前缀放宽**——脚本注释里写了这句。

**② 会真实计费的测试必须由环境变量守门。** `@EnabledIfEnvironmentVariable` 看的是
`System.getenv` 而不是 Spring 的 `Environment`，所以本机 `backend/.env` 里的密钥不会让它们跑
起来，CI 没有对应 secret 也不会。这条不变量一旦被删掉（或有人给 CI 加了密钥 secret），
CI 就会开始按次计费，而且是在没人注意的情况下。检查的是"守门存在"，不是"跑没跑"——
后者取决于环境，前者是代码里的承诺。

### 两项都反证过

抹掉 README 里 `QA_RETRIEVAL_MAX_PARALLELISM` 那条登记 → 检查报错点名该变量；
拿掉 `RealRerankApiTests` 的 `@EnabledIfEnvironmentVariable` → 检查报错点名该类。还原后通过。
新增门禁如果不反证，它和没有是一样的。

## 遗留

- **32 个环境变量未在 deploy/README 登记**（`STORAGE_*`、`UPLOAD_*`、`KNOWLEDGE_*`、
  `AI_DIAGNOSTICS_*`、`DATABASE_URL` 等）。它们不属于 3B，本工单没有扩大范围去补，
  但检查一旦扩到全前缀就会全部暴露。补齐它们值得单开一张工单。
