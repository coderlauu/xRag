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

前端通过 `VITE_API_BASE_URL` 环境变量指向后端地址（本地开发默认同源代理需自行配置，或直接设为 `http://localhost:3001`）。

## Validation

```bash
scripts/ci-validate.sh   # docker compose config 检查 + 文档链接检查 + frontend build + backend mvn verify
```

## Notes

- `backend/` 目前只有健康检查接口（`GET /api/v1/health`、`GET /api/v1/health/ready`），业务模块、数据库表待添加。`/health/ready` 会分别检查 Postgres/Redis/对象存储并在响应体里逐项列出，而不是折叠成一个笼统的失败。
- `backend/` 已接入 Flyway（`src/main/resources/db/migration/`），首个迁移只创建 pgvector 扩展；应用启动时会自动执行迁移，需要 Postgres 可连接。
- 启动时会尝试自动创建对象存储 Bucket（不存在则创建），失败只记录警告、不会阻止应用启动——应用是否真正就绪以 `/health/ready` 为准。
- `frontend/` 目前只有一个调用后端健康检查接口的占位页面。
- `deploy/` 与 `docker-compose.yml` 中的域名、账号密码均为占位值（`app` / `example.com`），上线前需替换为真实配置。
- Java 包名当前为 `com.app`，Maven `groupId`/`artifactId` 为 `com.app:backend`；如需改成正式项目名，替换 `backend/pom.xml` 与 Java 包路径即可。
- 本机全局 `mvn`/`java` 默认可能不是 JDK 17（比如被其他 JDK 版本占用），请用 `backend/mvnw`（已固定 Maven 3.9.9）并在需要时显式设置 `JAVA_HOME` 指向 JDK 17。
