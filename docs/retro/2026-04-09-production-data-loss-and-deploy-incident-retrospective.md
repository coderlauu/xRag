# Production Data Loss And Deploy Incident Retrospective

**日期：** 2026-04-09  
**版本上下文：** `v4 / Phase 2A` 进行中  
**状态：** archived

---

## 1. 一句话结论

本次事故不是单一 bug，而是一条连续故障链：production PostgreSQL 在无快照前提下暴露为空库事实，后续 deploy 又因“假成功”、远端 `/tmp` 写入失败与根分区写满而多次延迟修复；最终确认只能基于仍保留的 MinIO 原始对象做部分恢复，并补上了数据库自愈、deploy 真实校验与磁盘守护基线。

---

## 2. 影响范围

### 直接影响

- production PostgreSQL 中历史业务数据未能保留
- 桌面客户端通过 `SSH Tunnel` 进入数据库时只能看到空库或空 schema
- 新版本修复代码虽然已构建，但一度无法真正滚动到运行中的 `api / worker / web` 容器

### 间接影响

- 排障阶段出现“GitHub Actions 全绿但服务器仍跑旧镜像”的误导
- `db.xrag.coderlau.cn` 与桌面客户端入口短期内无法作为可靠事实源
- 数据恢复窗口从“数据库完整恢复”收缩为“仅文件类对象部分恢复”

---

## 3. 事故时间线

1. 用户在生产环境里发现历史文档、标签、时间线等记录消失。
2. 排查 PostgreSQL 后确认：
   - `xrag` 库最初不存在
   - `postgres` 库中也没有业务表
   - API 运行时仍指向 `postgresql://xrag:***@postgres:5432/xrag`
3. 通过 hotfix 补建 `xrag` 库并手工跑 migration 后，schema 恢复，但数据仍为空。
4. 继续排查发现服务器本地没有旧 PostgreSQL volume、没有旧容器，也没有可用 SQL 备份文件。
5. 检查 MinIO volume 后确认原始上传对象仍存在，说明可尝试“文件类部分恢复”。
6. 同时又发现 deploy 链存在两类问题：
   - 旧 deploy 脚本可能 release 解包成功，但运行中的容器并未切到新镜像
   - 后续修复 deploy 时，远端 `/tmp` 上传失败和根分区写满导致 deploy 继续失败
7. 最终确认根分区 `/dev/vda3` 被 Docker 镜像层、build cache、旧 release 和临时 bundle 占满，达到 `100%`。
8. 手工清理磁盘后，继续推进 deploy 自愈、template1 bootstrap、恢复扫描脚本与磁盘守护基线。

---

## 4. 已确认事实

1. 当前服务器没有云平台快照可用。
2. 当前服务器本地未找到 xRag 数据库 dump/backup 文件。
3. 当前 Docker 上只有一个 PostgreSQL volume：`xrag-production_postgres_data`。
4. 该 volume 对应的 PostgreSQL 实例内：
   - 先前只有 `template0 / template1 / postgres` 或后续补建出的空 `xrag`
   - 没有历史业务表与数据
5. `xrag-production_minio_data` 中仍存在上传对象底层文件，因此原始文件仍可扫描。

---

## 5. 根因分析

### A. 数据侧根因

- PostgreSQL 数据卷中的历史业务数据已不在当前现场。
- `POSTGRES_DB=xrag` 只会在空数据目录首次初始化时生效；当 volume 已存在且不含目标库时，仅修改环境变量不会自动补建数据库。
- 本次发现时，production 已处于“空 volume / 空库”事实状态，migration 只能恢复结构，不能恢复数据。

### B. 部署侧根因

- 旧 deploy 脚本缺少“运行中容器实际镜像 tag 校验”，导致可能出现“release 解包成功但容器没切换”的假成功。
- deploy bundle 先写远端 `/tmp`，在资源紧张时容易失败。
- 服务器没有磁盘守护与阈值告警，旧 release、Docker 镜像、build cache、deploy 临时文件长期累积，把根分区打满。

### C. 运维流程根因

- 没有数据库快照 / dump 的最小备份基线。
- 没有 deploy 前磁盘 preflight。
- 没有主机级定时清理策略。
- 没有把“数据恢复边界”在第一时间落成正式文档。

---

## 6. 已采取的修复

### 代码与脚本

- `8d33174`
  - `deploy` 自愈支持目标数据库不存在时自动补建
- `61fb5de`
  - 建库和 migration 自愈统一改用 `template1`
- `2d4a291`
  - 新增 MinIO 只读恢复扫描脚本
- `34a0db7`
  - deploy 后强制校验 `api / worker / web` 真实镜像 tag，消除假成功
- `52154dd`
  - deploy bundle 不再写远端 `/tmp`，改写 `${DEPLOY_PATH}/shared/tmp`
- 本轮新增
  - 磁盘守护脚本
  - deploy 前磁盘 preflight
  - 定时清理资产与文档

### 现场处置

- 暂停写入，防止继续覆盖数据库现场
- 保留当前 PostgreSQL / MinIO volume 元信息
- 确认 MinIO 中仍有原始文件对象
- 清理服务器磁盘，把根分区从 `100%` 降到可继续 deploy 的安全区间

---

## 7. 恢复边界

### 仍可恢复的

- 上传类文件的原始对象
- 基于原始对象重新解析得到的 PDF/OCR 文本
- 重新建立后的文件类文档元数据

### 大概率不可恢复的

- 手动文本输入文档
- 链接抓取后的正文缓存
- 标签关系
- 历史任务记录
- 历史处理时间线
- 历史诊断结果

---

## 8. 做错了什么

- 把 `POSTGRES_DB` 当成长期生效配置，而不是“首次初始化参数”
- 过晚发现 deploy 假成功问题
- 缺少 host-level 磁盘守护，直到 `100%` 才暴露
- 在没有快照和备份的前提下，production 数据恢复空间极其有限

---

## 9. 永久改进项

1. deploy 前必须执行远端磁盘 preflight；空间不足直接 fail-fast。
2. 服务器必须有定时磁盘守护：
   - 清 `shared/tmp`
   - 裁剪旧 release
   - 清 Docker stopped container / image / builder cache
   - 必要时截断过大容器日志
3. deploy 成功标准必须包含“运行中容器镜像 tag 与目标 tag 一致”。
4. PostgreSQL 新环境必须具备：
   - 目标库不存在时自动补建
   - migration 自愈
5. production 至少需要一种数据库恢复源：
   - 云快照
   - 定期 `pg_dump`
   - 或受控备份任务
6. 发生数据异常时，先保全现场，再做恢复，不再边诊断边覆盖现场。

---

## 10. 后续建议

下一步应分成两条线：

1. `ops hardening`
   - 磁盘守护定时器正式启用
   - deploy 前主机资源检查标准化
   - 数据库备份基线补齐

2. `partial recovery`
   - 基于 MinIO 恢复清单重建文件类文档
   - 重新入队解析和索引构建
   - 对不可恢复数据做明确通告和补救方案
