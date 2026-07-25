# 19 — CI 集成测试与部署配置

**What to build:** 让新增的集成测试在 CI 里真正跑起来，把本模块引入的全部配置项登记到部署说明中。

有一个必须明确记录的决定：**CI 里不调用真实 Embedding API**。真实调用会在每次 CI 中产生费用、需要把密钥放进 Secrets，而收益只是验证一个第三方 HTTP 接口还活着。CI 注入确定性假实现，真实链路只在本地手工验证。这个事实必须写在部署文档里——否则后来者会以为 CI 覆盖了真实链路。

**Blocked by:** 06（Embedding 客户端——需要假实现）、18（专项测试——CI 要跑的是最终完整的测试集）

**Status:** ready-for-agent

- [ ] 把 `tech` 目录纳入 `scripts/check-doc-links.mjs` 的扫描范围（本轮新增的技术方案文档目前不在检查范围内）
- [ ] 两份 env example（staging/production）登记本模块全部新增配置：`EMBEDDING_*` 五项、上传大小三项、`UPLOAD_MAX_CONCURRENT`、`KNOWLEDGE_SYNC_*`
- [ ] `EMBEDDING_API_KEY` 是本项目**第一个真实密钥类配置**：env example 里必须是占位值，`deploy/README.md` 说明它不能进仓库、应通过部署环境注入
- [ ] `deploy/README.md` 明确写出"CI 使用假 Embedding 实现、不覆盖真实 API 调用"这一事实及其原因
- [ ] CI 中注入确定性假 `EmbeddingClient`（返回固定维度伪向量），通过 Spring profile 或测试配置实现，**不能靠环境变量恰好没配这种隐式方式**
- [ ] 确认 CI 的 postgres 服务带 pgvector 扩展、Flyway 迁移能在 CI 中执行、集成测试能连上
- [ ] `README.md` 补充开发者启动前置条件（需要配 Embedding API Key 才能验证完整链路）
- [ ] `scripts/ci-validate.sh` 本地全绿
- [ ] CI 在 GitHub 上实际跑绿一次，且日志中能看到新增的集成测试真的执行了（不是被静默跳过）
