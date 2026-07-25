# Phase 0 对齐检查点

日期：2026-07-25

状态：学习与文档产出完成，进入 Phase 1 前等待对齐；容器运行时验证受本机缺少 Docker 阻塞。

## 1. 覆盖率

| 范围 | 完成 | 结果 |
|---|---:|---|
| 00-overview | 5/5 | 完成 |
| 01-rag-fundamentals | 25/25 | 完成 |
| 02-bootstrap-and-deployment | 10/10 | 完成 |
| Phase 0 合计 | 40/40 | 完成 |
| 专栏正文总计 | 40/91 | 后续阶段继续 |

每篇均包含原文链接、一句话概括、核心提炼、术语、与 xrag 的关系、设计取舍与风险。索引以 [INDEX.md](INDEX.md) 为唯一进度基线。

## 2. 已形成的综合产出

- 项目统一语言：[CONTEXT.md](../../CONTEXT.md)。
- 已接受决策：[ADR 0001：在当前 xrag 基线上独立建设系统](../../docs/adr/0001-build-xrag-independently.md)。
- Phase 0 未形成真实开发工单，因此没有创建 `.scratch/` 空骨架。
- 未创建其他 ADR：数据库 Schema、向量模型、分块策略、Provider 和本地模型部署尚无充分业务证据。

## 3. 关键技术结论

1. **xrag 与 Ragent 是两套基线**：Ragent 提供问题清单和实现参考，不能被描述成 xrag 已有能力。
2. **参考结论必须绑定版本**：课程文章与参考仓库会独立演进。例如 Ollama 文章使用原生 `/api/*`，而 Ragent `60e49e1` 已改用 OpenAI 兼容 `/v1/*`。
3. **知识入库是可追踪的状态过程**：解析、清洗、分块、向量化和写入均可能失败，文档版本、入库任务和分块必须保持来源关系。
4. **同维向量不等于同一空间**：切换 Embedding 模型、版本、量化或输出配置时，默认必须重建文档与查询向量，不能仅因维度相同而混用。
5. **OpenAI 兼容不等于行为等价**：工具调用、流式分片、usage、错误、Embedding 和 Rerank 均需按目标 Provider 验证。
6. **RAG 质量需要分阶段度量**：检索召回、重排、引用、生成和工具调用应能分别评测，不能只用一次主观问答判断效果。
7. **本地模型不是 Phase 1 前置条件**：是否本地部署取决于数据边界、负载、成本和运维能力；Ollama 只能作为候选的可选开发依赖。
8. **工具调用首先是权限和副作用问题**：参数校验、授权、超时、幂等、审计和输出过滤比“模型能否选中工具”更早进入设计。
9. **SSE 是传输机制，不是完整业务协议**：后续仍需定义事件类型、顺序、断开、取消、重连、背压和错误语义。

## 4. xrag 当前基线验证

检查基线：`main` 分支，开始验证时 HEAD 为 `758afb39bf5561c7e312a04b67f9d5c2f9837082`；文档工作区存在本次未提交修改。

### 已通过

| 检查 | 结果 |
|---|---|
| Java 版本 | 使用 JDK 17.0.12 验证 |
| 后端构建与测试 | `mvn -q -B verify` 通过 |
| 前端构建 | `corepack pnpm run build` 通过 |
| 文档链接 | `node scripts/check-doc-links.mjs` 通过，已包含 `learning/` |
| 后端进程启动 | Spring Boot 3.4.1 在 `3001` 端口启动成功 |
| 存活端点 | `GET /api/v1/health` 返回 HTTP 200 和 `{"status":"ok"}` |

### 未通过或未能执行

| 检查 | 结果 | 原因 |
|---|---|---|
| `scripts/compose-config.sh` | 未执行成功 | 本机没有 `docker` CLI |
| PostgreSQL / Redis / MinIO 启动与端口连通 | 未执行 | 本机没有 Docker 容器运行时 |
| pgvector 扩展能力 | 未执行 | PostgreSQL 未能启动 |
| MinIO `app` Bucket | 未执行 | MinIO 未能启动，仓库也没有 Bucket 初始化步骤 |
| 后端就绪端点 | HTTP 503 | `GET /api/v1/health/ready` 首先连接 PostgreSQL，依赖未启动 |
| `scripts/ci-validate.sh` | 阻塞 | 第一项 `docker compose config` 无法运行；其余文档、前端、后端步骤已分别通过 |

没有擅自安装 Docker，也没有修改业务 API、数据库 Schema 或前后端功能。

## 5. 参考 Ragent 验证

参考仓库不是 xrag 的运行结果。本次从官方仓库读取 `main` 提交：

- Commit：`60e49e11929c0468576472f6ea4c20f357994011`
- Commit 日期：2026-07-23
- 后端：Java 17、Spring Boot 3.5.7，四个 Maven 模块 `bootstrap`、`framework`、`infra-ai`、`mcp-server`
- 默认外部依赖：PostgreSQL/pgvector、Redis、RocketMQ、S3 兼容存储；另有可选 Milvus、图谱和模型服务
- 数据库初始化：仓库提供 `resources/database/schema_pg.sql` 和初始数据脚本
- 应用上下文：`/api/ragent`，端口 `9090`

验证结果：

- 前端 `npm ci && npm run build` 通过。
- 安装依赖时 npm 报告 19 个依赖漏洞（1 low、8 moderate、10 high）；构建产物同时出现大 Chunk 警告。这是参考项目风险，不是 xrag 当前依赖结果。
- Maven Wrapper 下载 Maven 3.9.11 时失败；改用本机 Maven 3.9.16 + JDK 17 执行 `mvn -DskipTests package` 后，四个后端模块全部构建成功。构建提示 compiler/surefire 插件未固定版本，并有少量 deprecated/unchecked 编译警告。
- 打包应用可以进入 Spring/Tomcat 初始化，但因 `127.0.0.1:6379` 没有 Redis 而终止，未达到可接收请求状态；这证明参考应用的 Redis 是启动期强依赖。
- 由于本机缺少 Docker，且参考应用依赖数据库、Redis、RocketMQ、对象存储及部分外部模型凭据，未宣称完成其端到端启动或连通性验证。
- 源码中未发现独立的 Actuator/健康端点，不能把 xrag 的 `/api/v1/health` 验证结果套用到 Ragent。

## 6. 当前脚手架缺口

进入 Phase 1 前已识别但本批次未修复的缺口：

- 仓库没有 Maven Wrapper，机器默认 Maven 进程实际使用 JDK 26；验证时必须显式指定 JDK 17。
- Compose 使用 pgvector 镜像，但没有迁移脚本创建 `vector` 扩展和业务表。
- MinIO 没有 Compose healthcheck，也没有创建 `app` Bucket 的初始化步骤；后端 readiness 却要求该 Bucket 已存在。
- `infra-check.sh` 只验证服务/端口，不验证 pgvector、目标 Bucket 或后端 readiness。
- readiness 把所有依赖失败折叠为同一个 503，且遇到第一个失败即停止，无法同时展示各依赖状态。
- `infra-down.sh` 默认带 `-v` 删除数据卷，作为普通停止命令存在数据丢失风险。
- `ci-validate.sh` 强依赖本机 Docker CLI 做 Compose 静态检查，当前没有替代路径。
- 当前测试只验证 Spring Context，尚未覆盖健康端点成功/失败语义。

这些问题属于 Phase 1 方案或 Bootstrap 整改输入，不能在缺少产品和数据边界的情况下顺手扩展为业务实现。

## 7. 进入 Phase 1 前的待决策事项

1. 首个知识库业务场景、目标用户、租户和权限边界。
2. 源文档类型、大小、更新/删除语义与数据保留要求。
3. 文档版本、入库任务、分块和对象存储键的业务标识规则。
4. 同步/异步入库边界、失败恢复、幂等和可观测要求。
5. 分块策略与首批可重复评测样本。
6. Embedding Provider、向量配置版本、距离度量和迁移策略。
7. pgvector 是否由当前脚手架选择升级为正式架构决策。
8. 云模型、本地模型或混合路由的数据边界、凭据和回退规则。
9. Phase 1 是否先补 Maven Wrapper、迁移工具、Bucket 初始化和可诊断 readiness。

## 8. 对齐建议

Phase 1 应先产出“AI 知识库建设”的 PRD 与技术方案，并把上述 1～6 项落实为可验收规则；在用户确认前，不进入业务代码、Schema 或前端功能开发。容器级健康验证需要可用的 Docker 环境后补跑。
