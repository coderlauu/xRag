# Phase 1A Retrospective

**日期：** 2026-04-02  
**版本：** `v1 / Phase 1A`  
**状态：** archived

---

## 1. 一句话结论

`Phase 1A` 已完成从原型到正式工程基线的第一轮落地，成功打通了 `采集 -> 存储 -> 检索 -> 找回 -> 测试 -> 部署` 的最小生产闭环。

---

## 2. 本轮交付

### 产品与需求

- 明确 `v1 / Phase 1A` 真实边界，只做三页闭环与关键词找回
- 将 `pdf` 真实解析、multipart 上传、OCR、AI 问答、语义搜索移出本阶段
- 建立 `handoff + status + exec plan + tech docs` 的版本化资产体系

### 工程与实现

- 建立 `web + api + worker + db + storage + queue` 正式工程骨架
- 打通文本创建、文件上传、异步解析、状态推进、关键词搜索、详情查看
- 前端从静态 placeholder 演进到真实 API 驱动
- 建立 `Tailwind CSS + shadcn 风格组件` 的 UI 基线

### 质量与交付

- 建立 API integration、worker unit、Playwright smoke、统一 CI 校验脚本
- 打通生产部署链路，最终完成可访问的 production 环境

---

## 3. 关键决策

1. 先冻结版本边界，再进入实现  
2. 主线程先冻结 `schema / shared-types / API contract / state machine`，再让子 agent 并行  
3. `Phase 1A` 只做单对象 presigned upload，不做 multipart  
4. 生产镜像仓库从 `GHCR` 切到阿里云 ACR，解决国内服务器拉取过慢问题  
5. 生产入口引入 `Caddy`，补齐 `80/443` 和自动 TLS

---

## 4. 做对了什么

- 把 repo 变成了真正的 system of record，而不是依赖聊天上下文
- 在编码前完成了技术方案、数据模型、API 设计和版本边界收口
- 及时把“主线程冻结上游，子 agent 并行实现”固化成流程
- 所有真实部署问题都沿着日志逐层定位并收敛，没有靠猜
- 最终生产链路在真实环境里被验证，而不是停在本地脚本层

---

## 5. 暴露的问题

- 初始状态文档没有持续跟到最后一次 production 验证，存在滞后
- 子 agent 在边界不稳时容易停在分析层，说明 lane 规则必须更严格
- 部署脚本最初假设过多：
  - 默认 SSH 一定通
  - 默认远端用户有 Docker 权限
  - 默认远端目录挂载方式稳定
  - 默认 `GHCR` 拉取速度可接受
- 生产 smoke 成功之前，单靠本地 compose 校验无法覆盖真实环境问题

---

## 6. 关键故障与修复链

- CI readiness 竞态：等待 Postgres/Redis 健康状态后再探活
- Node 24 Actions 兼容：升级 action/runtime 组合并去除隐式缓存问题
- lockfile 漂移：启用严格 `--frozen-lockfile` 并刷新锁文件
- SSH 认证失败：补私钥格式校验、SSH preflight 和 clearer error
- 远端 Docker 权限不足：远端脚本兼容 `docker` 与 `sudo -n docker`
- `scp` 端口参数错误：区分 `ssh -p` 与 `scp -P`
- 远端半成品容器冲突：部署前先 `compose down --remove-orphans`
- `GHCR` 拉取过慢：切换到阿里云 ACR
- `api-migrate` 吃掉 SSH stdin：改为 `run --rm -T ... </dev/null`
- `Caddyfile` 远端挂载失败：改用 shared 绝对路径挂载

---

## 7. 验证结果

- 本地：`typecheck / build / integration / e2e / ci-validate / deploy compose` 全部通过
- 远端：production 部署和公网访问已打通
- 最近成功 CI run：`23856744666`

---

## 8. 产出资产

- [v1 status](/Users/coderlauu/xRag/docs/status/v1-phase-1a.md)
- [v1 handoff](/Users/coderlauu/xRag/docs/handoff/v1.md)
- [Phase 1A architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)
- [Phase 1A data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md)
- [Phase 1A api design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- [parallel implementation plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-01-phase-1a-parallel-implementation.md)

---

## 9. 下一阶段建议

下一阶段不再重复搭骨架，重点进入 `ingestion hardening + production hardening`：

- `pdf` 真实解析
- multipart 大文件上传
- 解析失败诊断与重试增强
- observability 与发布回滚能力

