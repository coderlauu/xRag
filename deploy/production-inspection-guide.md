# Production Inspection Guide

本文件用于回答两个问题：

1. 当前 production 到底跑了哪些服务
2. 数据库和对象存储里到底存了哪些数据与文件

如果你正在处理 `2026-04-09` 这次数据库空库与 deploy 失败事故，可同时阅读：

- [Production Data Loss And Deploy Incident Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-09-production-data-loss-and-deploy-incident-retrospective.md)

如果仓库已经在目标机上，可优先用 repo 内脚本而不是临时拼命令：

- `./scripts/ops-inspect-upload.sh <upload-id-or-file-name-fragment>`
- `./scripts/ops-inspect-document.sh <document-id-or-title-fragment>`
- `./scripts/ops-inspect-ci-run.sh <github-actions-run-id>`

脚本默认读取：

- `XRAG_ENV_FILE=/srv/xrag/shared/production.env`
- `XRAG_POSTGRES_CONTAINER=xrag-production-postgres-1`

如果生产环境变量或容器名不同，可在执行前覆盖这两个环境变量。

默认基于当前生产部署：

- 服务器：`8.134.122.242`
- 部署根目录：`/srv/xrag`
- project name：`xrag-production`

---

## 1. 先看服务是否健康

登录服务器后先执行：

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}' | grep xrag-production
```

正常情况下至少应看到：

- `xrag-production-postgres-1`
- `xrag-production-redis-1`
- `xrag-production-minio-1`
- `xrag-production-api-1`
- `xrag-production-worker-1`
- `xrag-production-web-1`
- `xrag-production-caddy-1`

如果想看端口监听：

```bash
ss -lntp | grep -E ':80|:443'
```

如果想先用产品内建视角看一眼当前运行态，可直接请求：

```bash
curl -sS https://xrag.coderlau.cn/api/v1/ops/health-summary | jq '.'
```

其中 `worker` 项现在不再只表示 `Redis ping` 成功；它会同时检查：

- 是否还能连到 queue
- 是否存在超过 10 分钟仍未收口的 active answer session
- 是否存在超过 10 分钟仍停留在 `queued / running` 的 document job

因此：

- `worker = healthy` 只能说明当前没看到明显滞留信号
- `worker = warning` 时，优先继续看 `docker logs xrag-production-worker-1`
- `worker` 若持续 `Restarting`，优先核对启动入口是否指向 `apps/worker/dist/apps/worker/src/main.js`；旧的 `apps/worker/dist/main.js` 只会在脏工作区残留，在干净 checkout / CI 镜像里不会生成

---

## 1.1 先看磁盘是否接近打满

deploy 失败且日志出现 `No space left on device` 时，先看根分区：

```bash
df -h
```

生产现在提供了安全清理脚本：

```bash
/srv/xrag/shared/bin/xrag-disk-guard.sh /srv/xrag
```

它只会清理：

- `/srv/xrag/shared/tmp`
- 旧 release
- Docker stopped container / image / builder cache
- 过大的 Docker JSON 日志

它不会触碰 PostgreSQL / MinIO 数据卷。

如果需要确认定时器是否已启用：

```bash
systemctl status xrag-disk-guard.timer
journalctl -u xrag-disk-guard.service -n 100 --no-pager
```

---

## 2. 看最近日志

先看 API、worker、MinIO：

```bash
docker logs --tail=200 xrag-production-api-1
docker logs --tail=200 xrag-production-worker-1
docker logs --tail=200 xrag-production-minio-1
```

如果要持续观察：

```bash
docker logs -f xrag-production-api-1
docker logs -f xrag-production-worker-1
```

---

## 3. 读取 production 环境变量

当前生产环境变量文件在：

```bash
/srv/xrag/shared/production.env
```

查看与存储相关的配置：

```bash
grep -E 'POSTGRES_|MINIO_|STORAGE_|APP_' /srv/xrag/shared/production.env
```

注意：

- `MINIO_ROOT_USER`
- `MINIO_ROOT_PASSWORD`
- `STORAGE_BUCKET`
- `POSTGRES_DB`
- `POSTGRES_USER`

这些值决定了你后面进入数据库和对象存储时该用什么账号、什么 bucket。

---

## 4. 观察数据库里存了什么

### 4.1 推荐方式：数据库 Web 管理台

如果你已经接通数据库管理台，可直接访问：

```text
https://db.xrag.coderlau.cn
```

访问流程：

1. 先通过浏览器 `basic auth`
2. 认证通过后直接进入数据库 Web 管理台

这个入口适合：

- 浏览表结构
- 搜索最近文档、上传、任务、事件
- 快速做只读排查

如果你还没接通这个域名，或者需要执行更细的 SQL，再使用下面的 `psql` 方式。

### 4.2 桌面客户端

如果你使用 `Navicat`、`TablePlus` 或 `DBeaver`，推荐使用 `SSH Tunnel`。

当前生产 PostgreSQL 只绑定在服务器本机：

```text
127.0.0.1:5432
```

所以：

- 不应直接把客户端连到 `8.134.122.242:5432`
- 应通过 `SSH` 进入服务器后再转发到 `127.0.0.1:5432`

先载入环境变量，再进入 Postgres：

```bash
set -a
source /srv/xrag/shared/production.env
set +a
docker exec -it xrag-production-postgres-1 psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

进入 `psql` 后，先看表：

```sql
\dt
```

当前核心表是：

- `documents`
- `uploads`
- `document_parse_jobs`
- `tags`
- `document_tags`

常用查询：

最近上传记录：

```sql
select id, file_name, mime_type, file_size, object_key, status, created_at, completed_at
from uploads
order by created_at desc
limit 20;
```

最近文件文档：

```sql
select id, title, file_name, mime_type, object_key, parse_status, parse_error_message, created_at
from documents
where source_type = 'file'
order by created_at desc
limit 20;
```

最近解析任务：

```sql
select id, document_id, job_type, status, attempt, error_code, error_message, created_at, started_at, finished_at
from document_parse_jobs
order by created_at desc
limit 20;
```

根据某个上传文件名追踪：

```sql
select id, file_name, object_key, status, created_at, completed_at
from uploads
where file_name ilike '%你的文件名%'
order by created_at desc;
```

退出数据库：

```sql
\q
```

---

## 5. 观察 MinIO 里存了什么

### 5.1 最直观的方式：打开 MinIO Console

正式生产建议通过独立域名访问：

```text
https://console.xrag.coderlau.cn
```

这个入口通过 `Caddy` 反代到 `minio:9001`。

如果 Console 域名尚未接通，才临时使用 SSH 隧道：

```bash
ssh -L 9001:127.0.0.1:9001 deploy@8.134.122.242
```

然后本机浏览器访问：

```text
http://127.0.0.1:9001
```

或如果你已做额外反向代理，也可以用对应域名。

MinIO 登录账号密码来自：

- `MINIO_ROOT_USER`
- `MINIO_ROOT_PASSWORD`

登录后查看 bucket：

- bucket 名默认是 `xrag`

对象路径大致会长这样：

```text
uploads/YYYY/MM/DD/<upload_id>/<file_name>
```

### 5.2 用公网存储域名快速确认 MinIO API 活着

```bash
curl -fsS https://storage.xrag.coderlau.cn/minio/health/live
curl -fsS https://storage.xrag.coderlau.cn/minio/health/ready
```

---

## 6. 上传链路怎么排查

当前上传分成 3 段：

1. `POST /api/v1/uploads/initiate`
   - API 创建 `uploads` 记录
   - API 调用 `StorageService.createPresignedUpload()`
   - 若此处失败，通常是 `MinIO` 连通性、bucket 或签名配置问题

2. 前端直接 `PUT upload_url`
   - 浏览器直接把文件传到 MinIO
   - 若此处失败，通常是 presigned URL、`content-type`、对象存储可访问性问题

3. `POST /api/v1/uploads/:uploadId/complete`
   - API 调用 `StorageService.assertObjectExists()`
   - 成功后创建 `documents` 与 `document_parse_jobs`
   - 若此处失败，通常是对象实际没上传成功，或 `HeadObject` 失败

对应代码：

- [uploads.service.ts](/Users/coderlauu/xRag/apps/api/src/uploads/uploads.service.ts)
- [storage.service.ts](/Users/coderlauu/xRag/apps/api/src/storage/storage.service.ts)

---

## 7. 排查当前这个上传 bug 的最小路径

先做这四步：

1. 看 API 日志

```bash
docker logs --tail=200 xrag-production-api-1
```

2. 看 MinIO 日志

```bash
docker logs --tail=200 xrag-production-minio-1
```

3. 看数据库里是否已有 `uploads` 记录

```bash
set -a
source /srv/xrag/shared/production.env
set +a
docker exec -it xrag-production-postgres-1 psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select id, file_name, status, object_key, created_at, completed_at from uploads order by created_at desc limit 20;"
```

4. 去 MinIO Console 看对应 `object_key` 是否真的存在

判断逻辑：

- 如果 `uploads` 记录都没创建，问题更靠前，在 API `initiate`
- 如果 `uploads` 有记录但 MinIO 里没有对象，问题在浏览器直传或 presigned URL
- 如果对象已存在但 `complete` 失败，问题在 `HeadObject`、checksum、事务或入队

---

## 8. 建议你日常先看哪三个面板

如果你不想每次都手工查很多命令，先固定看这三个地方：

1. `docker ps`
   - 看服务是不是都活着
2. Postgres 最近三张表
   - `uploads`
   - `documents`
   - `document_parse_jobs`
3. MinIO Console
   - 看对象是否真的传上去了

这三处组合起来，基本就能判断问题是在：

- API
- 对象存储
- worker
- 还是前端直传
