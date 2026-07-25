# 19 — CI 集成测试与部署配置

**What to build:** 让新增的集成测试在 CI 里真正跑起来，把本模块引入的全部配置项登记到部署说明中。

有一个必须明确记录的决定：**CI 里不调用真实 Embedding API**。真实调用会在每次 CI 中产生费用、需要把密钥放进 Secrets，而收益只是验证一个第三方 HTTP 接口还活着。CI 注入确定性假实现，真实链路只在本地手工验证。这个事实必须写在部署文档里——否则后来者会以为 CI 覆盖了真实链路。

**Blocked by:** 06（Embedding 客户端——需要假实现）、18（专项测试——CI 要跑的是最终完整的测试集）

**Status:** done `2026-07-26`（最后一条需要推送后才能验，见完成记录）

- [x] 把 `tech` 目录纳入 `scripts/check-doc-links.mjs` 的扫描范围（工单 05 时已完成，`prototype/` 也在其中）
- [x] 两份 env example（staging/production）登记本模块全部新增配置
- [x] `EMBEDDING_API_KEY` 是本项目**第一个真实密钥类配置**：env example 里是占位值，`deploy/README.md` 说明它不能进仓库、应通过部署环境注入
- [x] `deploy/README.md` 明确写出"CI 使用假 Embedding 实现、不覆盖真实 API 调用"这一事实及其原因
- [x] CI 中注入确定性假 `EmbeddingClient`——由测试类 `@Import(FakeEmbeddingConfig.class)` **显式导入**，不依赖"环境变量恰好没配"这种隐式方式
- [x] 确认 CI 的 postgres 服务带 pgvector 扩展、Flyway 迁移能在 CI 中执行、集成测试能连上
- [x] `README.md` 补充开发者启动前置条件
- [x] `scripts/ci-validate.sh` 本地全绿
- [ ] CI 在 GitHub 上实际跑绿一次 —— **需要推送才能验证**，见完成记录

## 完成记录

### 发现并修掉一个真实的 CI 缺口：validate job 没有对象存储

`validate` job 的注释原本写着"只起 Postgres：verify 不触碰 Redis / 对象存储"。**那句话从工单 07 上传接口落地那天起就不成立了**——上传、URL 抓取、异步入库这几组用例会真的 `putObject` / `getObject`。

它一直没被发现，是因为本地开发机上 RustFS 常年开着，谁也没在"没有对象存储"的环境下跑过 verify。**验证方式是把它复现出来**：`docker compose stop rustfs` 后跑 `DocumentUploadIntegrationTests`，8 条里挂 5 条。

修法是加一个 `docker compose up -d rustfs` 的步骤 + 健康等待，而不是 service container——**GitHub Actions 的 service container 不支持覆盖 `command`，而 rustfs 镜像需要 `/data` 作为启动参数**。走 compose 顺带保证镜像、凭据、启动参数与本地逐字一致。

### CI 不打真实 Embedding，这个取舍写进了 deploy/README

代价必须说清楚：**CI 的绿灯不代表真实 API 链路是通的**。真实调用只由 `RealEmbeddingApiTests` 覆盖，它用 `@EnabledIfEnvironmentVariable` 守门——**没有 Key 时自动跳过，而跳过在 CI 日志里看起来和通过很像**。供应商换了、Key 过期了、模型下线了，都要靠本地带 Key 跑一次才能发现。

顺带记下另一个坑：那组真实 API 测试断言的是**余弦相似度**而非向量相等——实测同一段文本两次调用有 1e-3 量级抖动（大概率低精度推理），拿"相等"做断言必然 flaky。

### 顺手修正了 README 里已经过时的描述

`## Notes` 里还写着"backend 目前只有健康检查接口"、"frontend 只有一个占位页面"。这在本阶段之前是对的，现在是**误导**——新人照着读会以为业务代码还没开始。改成了当前实际状态。同时按工单要求补了「走完整链路需要 Embedding API Key」一节，并把"跑 verify 前先停掉本地后端（含 IDE 里那个）"这条踩过两次的坑写了进去。

### 最后一条为什么没勾

"CI 在 GitHub 上实际跑绿一次"需要把分支推上去。**推送是对外动作，等你确认**。本地 `scripts/ci-validate.sh` 已 exit 0，且对象存储缺口是本地复现后修的，但 CI 环境与本地终究不同（尤其是新加的 compose 步骤在 runner 上的行为），这一条在真跑绿之前不能勾。
