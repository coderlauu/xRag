# Production Disk Guard And Incident Retro

## 1. Metadata

- `plan_id`: `production-disk-guard-and-incident-retro`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v4 status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md), [deploy README](/Users/coderlauu/xRag/deploy/README.md), [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)

## 2. Objective

把本次 production 事故里的两个缺口落成仓库事实：

1. 增加磁盘阈值守卫、可重复的安全清理脚本和定时运行资产，避免后续部署再次因为磁盘写满而假失败或硬失败。
2. 把数据库空卷、部署假成功、远端磁盘打满、恢复边界等经验归档为正式复盘，避免后续版本复盘时遗忘。

## 3. Scope

### In Scope

- `deploy-production` 前的远端磁盘预检
- 仅清理可再生资产的磁盘回收脚本
- 定时运行建议资产，如 `systemd service/timer`
- README 与排查手册更新
- 事故复盘文档归档
- `v4` 当前状态与 handoff 的事实回写

### Out Of Scope

- 业务数据恢复脚本的真正导入执行
- 自动处理 PostgreSQL / MinIO 数据卷
- 改动 `Phase 2A` 的产品范围和运行时 contract

## 4. Implementation

1. 在 `deploy/scripts/` 下新增独立磁盘守护脚本，支持：
   - 根分区使用率读取
   - `shared/tmp` 清理
   - 旧 `release` 目录保留最近 `N` 个
   - Docker stopped container / image / builder cache 清理
   - 可选大日志截断
   - 高阈值 fail-fast
2. 在 `scripts/gh-deploy-ssh.sh` 中接入远端预检：
   - 上传 deploy bundle 前先运行守护脚本
   - 如果清理后仍超过 fail 阈值，则直接失败，阻止继续写入
3. 在 `deploy/scripts/remote-deploy.sh` 中：
   - 同步磁盘守护脚本到 `shared/bin`
   - 同步 `systemd` 资产到 `shared/systemd`
   - 在真正 deploy 前再执行一次守护脚本
4. 更新运维文档：
   - 配置项
   - 手工执行方式
   - 定时启用方式
   - deploy 前失败时的排查提示
5. 新增一份 production 事故复盘文档：
   - 影响
   - 事件链
   - 已确认根因
   - 恢复边界
   - 永久改进项

## 5. Validation

- `bash -n` 校验所有新增/修改 shell 脚本
- `pnpm validate`
- 用本地临时目录运行一次磁盘守护脚本，验证不会触碰数据卷相关逻辑
- 文档链接检查通过

## 6. Done Definition

- deploy 前磁盘不足会被明确阻止，并提示清理结果
- 服务器侧有可重复执行的磁盘守护脚本和定时器资产
- 运维文档可指导后续启用与手工排障
- 本次事故已有正式 retrospective 可供后续版本复盘引用
