# App

前后端分离的空白工程 scaffold：React 前端 + Java (Spring Boot) 后端，业务逻辑待从 0 开发。

## Repository Layout

```text
.
├── frontend/           # React + Vite + TypeScript, pnpm 管理，最小占位页面
├── backend/            # Spring Boot + Maven，health 接口 + DB/Redis/对象存储连接骨架
├── deploy/             # Docker/Caddy/Nginx/systemd 部署骨架，占位域名与命名待替换
├── docs/               # 文档目录骨架（含通用模板），内容待积累
├── .agents/skills/      # 可复用的 agent 技能包（mattpocock/skills）
├── scripts/             # CI、基础设施起停、部署运维脚本
└── .github/workflows/   # CI（validate/infra）与自动部署流水线
```

## Tech Stack

- Backend：Spring Boot + Maven（Java 17），JDBC 直连 Postgres，Redis、S3 兼容对象存储连接骨架
- Frontend：React + Vite + TypeScript，pnpm 管理
- Storage：S3 兼容对象存储（本地用 RustFS，课程技术栈同款；MinIO 收紧开源协议后的替代方案，仍处 alpha 阶段）
- Deploy：Docker Compose + Caddy（TLS）+ Nginx（前端静态资源）+ systemd（磁盘守护）

## Getting Started

```bash
docker compose up -d          # postgres + redis + rustfs

cd backend && ./mvnw spring-boot:run    # 后端，默认监听 :3001（本机默认 JDK 可能不是 17，必要时显式设置 JAVA_HOME）

cd frontend && pnpm install && pnpm dev   # 前端，默认监听 :5173
```

本地开发不需要配 `VITE_API_BASE_URL`：`vite.config.ts` 已经把 `/api` 同源代理到后端（默认 `http://localhost:3001`，用 `BACKEND_ORIGIN` 覆盖）。没有这个代理时 `:5173` 直连 `:3001` 是跨源请求，后端未配 CORS 会被浏览器拦掉，界面上表现为"无法连接到服务器"，很容易误判成后端没起来。

`VITE_API_BASE_URL` 留给"前端产物直连另一个域名的后端"这种部署形态，本地留空即可。

### 走完整链路需要 Embedding API Key

**没有 Key 应用照常启动**，知识库的上传、分块、浏览、管理都能用；只有"要写向量"的那些操作会返回 `502 EMBEDDING_FAILED`——触发分块、编辑/新增分块、启用文档或分块、批量启用。

```bash
export EMBEDDING_API_KEY=<你的 Key>    # 只经环境变量注入，不要写进任何文件
cd backend && ./mvnw spring-boot:run
```

供应商默认是火山方舟 Ark。若换供应商，同时要改 `EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSIONS`，其中维度必须与数据库 `vector(1024)` 列一致——**不一致时应用会直接启动失败并说明冲突**，这是有意的（放行的话每次写向量才在运行时报错）。详见 [deploy/README.md](deploy/README.md) 的「Embedding API Key」一节。

> **跑 `mvn verify` 或 `scripts/ci-validate.sh` 之前，先停掉本地正在运行的后端**（包括 IDE 里启动的那个）。集成测试与 dev 实例连的是同一个库，两个 `IngestionDispatcher` 会互相抢任务，症状是集成测试成片失败在"期望 SUCCESS 实际 FAILED"，看起来完全不像环境问题。根因见 [ADR 0002](docs/adr/0002-knowledge-base-async-and-concurrency.md) 的单实例假设。

## Validation

```bash
scripts/ci-validate.sh   # docker compose config 检查 + 文档链接检查 + frontend build + backend mvn verify
```

## Publish Markdown to Feishu

飞书发布以仓库 Markdown 为唯一源，范围为 `README.md`、`CONTEXT.md`、`docs/**`、`learning/**`、`tech/**`。仓库路径会按配置映射成 `xRag` 下易读的中文飞书目录（`01-项目文档`、`02-RAG学习专栏`、`03-技术设计`）；本机目标文件夹、目录和文档映射保存在被 Git 忽略的 `.feishu-docs.local.json`，CLI 登录凭证由系统密钥链管理。

```bash
node scripts/publish-feishu-docs.mjs --list
node scripts/publish-feishu-docs.mjs --file README.md
node scripts/publish-feishu-docs.mjs --sync --dry-run
node scripts/publish-feishu-docs.mjs --sync --yes --allow-overwrite
```

同步时，新增文件会创建飞书文档，内容未变化的文件会跳过，内容变化的文件只有在显式增加 `--allow-overwrite` 后才会全文替换。覆盖会清空并重建远端正文，因此不要在飞书副本中继续维护手工内容或评论。

本地删除或改名的文件默认只显示为 `orphan`，不会自动删除飞书文档。先预览，再明确执行清理：

```bash
node scripts/publish-feishu-docs.mjs --sync --dry-run --prune
node scripts/publish-feishu-docs.mjs --sync --yes --allow-overwrite --prune
```

`--prune` 会把对应飞书文档移入回收站；空的镜像文件夹会保留。批量同步和删除均串行执行。

在 Codex 中也可以直接说“使用 `$lark-sync-project-docs` 预览并同步项目文档”。项目级 Skill 会先生成 dry-run 摘要，并在批量覆盖或删除前等待明确确认。

## Notes

- `backend/` 除健康检查（`GET /api/v1/health`、`GET /api/v1/health/ready`）外，已实现知识库模块：知识库管理、文档上传与 URL 来源、异步入库、分块管理、定时同步。`/health/ready` 会分别检查 Postgres/Redis/对象存储并在响应体里逐项列出，而不是折叠成一个笼统的失败。
- `backend/` 已接入 Flyway（`src/main/resources/db/migration/`）；应用启动时会自动执行迁移，需要 Postgres 可连接。迁移失败只记警告不阻塞启动，因此**数据库恢复可达后需要重启应用才会真正补跑迁移**。
- 启动时会尝试自动创建对象存储 Bucket（不存在则创建），失败只记录警告、不会阻止应用启动——应用是否真正就绪以 `/health/ready` 为准。
- `frontend/` 有知识库列表、文档列表、分块管理三个页面，外加一个保留下来的健康检查诊断页。
- `deploy/` 与 `docker-compose.yml` 中的域名、账号密码均为占位值（`app` / `example.com`），上线前需替换为真实配置。
- Java 包名当前为 `com.app`，Maven `groupId`/`artifactId` 为 `com.app:backend`；如需改成正式项目名，替换 `backend/pom.xml` 与 Java 包路径即可。
- 本机全局 `mvn`/`java` 默认可能不是 JDK 17（比如被其他 JDK 版本占用），请用 `backend/mvnw`（已固定 Maven 3.9.9）并在需要时显式设置 `JAVA_HOME` 指向 JDK 17。
