# 复盘：Phase 3A 记忆 + 改写 + 意图

- `date`: 2026-08-08
- `status`: **已确认**（2026-08-08）
- `milestone`: [3A 里程碑状态](../status/2026-08-08-phase-3a-qa-understanding.md)

只记真正改变了判断的事。顺利的部分不重复叙述。

## 1. 诊断差点反过来决定业务结果

四个编排组件都直接调 `TurnStageSink.record()`，而 `QuestionUnderstandingService.process()` 用一个
catch-all 兜住 `RuntimeException` 并写 `FAILED`。于是**一次阶段诊断写失败，会把一个已经算完的
turn 判成理解失败**——诊断反过来决定了业务结果。

`JdbcTurnStageSink` 自己吞了 `DataAccessException`，所以生产路径上没暴露过；但换个实现
（异步 writer、被装饰的 sink）就不成立了。

- **是怎么发现的**：工单 15 的故障注入，不是代码评审。评审看这段代码时它"显然是对的"。
- **修法**：把 best-effort 保证上移到端口（`TurnStageSink.recordBestEffort()`），四个调用点全改走它。
  放端口而不是每个调用点各写一遍 try/catch——漏掉任何一个的后果都一样严重。
- **可推广的判断**：凡是"失败不应影响主流程"的旁路写入，保证要落在**端口**上，不是落在调用约定上。

## 2. 一刀切的隐私审计会永远绿

隐私审计的第一版差点写成"七类标记串在任何地方都不许出现"。那样会得到一个**永远通过的假审计**：
用户问题正文本来就该出现在 `/messages` 和 Inspector 的规范化问题里，意图 `description` 本来就该
出现在意图树 API 里。

正确做法是**每类标记串各有自己的禁区表**，再配一例正向断言（正文确实还在它该在的地方）和一例
反向断言（人为往禁区种标记串，扫描必须命中，且报告不复制原文）。

这条与 Phase 2 的 `DiagnosticPrivacyAuditIntegrationTests` 是同一课，但那次是"哪儿都不许出现"
恰好成立，所以没暴露出这个陷阱。**审计的价值等于它的禁区表的精确度**，不等于它扫了多少列。

## 3. 无 Key 时系统不报错，它安静地不工作

实测（`QaNoApiKeyBehaviourTests`）：没有任何供应商密钥时，应用照常启动，用户发问照常返回 201
并走到 `ACTIONS_READY`——与有 Key 时**终态完全相同**。区别只在 `rewrite.mode`、`degradations`
和动作类型里。

按"没配 Key 就该报错"的直觉去看监控，会把一个完全没在路由的系统当成正常的。已写进
`deploy/README` 第一节，并附有/无 Key 的逐项对照表。

## 4. 我自己踩的：`backend/.env` 让本地测试产生真实计费

写上面那个测试时，守卫用的是 `System.getenv("DASHSCOPE_API_KEY") == null`。它放行了，测试连上
真实供应商，**产生了约 4 次计费调用**，还给出一个完全相反的结论（`rewrite.mode=MODEL`）。

根因：密钥不在进程环境里，它在 `backend/.env`，经 `application.properties` 的
`spring.config.import` 进入 Spring `Environment`。`@EnabledIfEnvironmentVariable` 守不住这条路径。

两个衍生结论：

- **守卫要守在事实来源上**：密钥有没有被解析出来，只有 catalog 的 `UNCONFIGURED` 状态说了算。
- **开发机上任何不注入假 `ChatClient` 的 `@SpringBootTest` 都会真实计费**。CI 上没有 `.env`
  所以不受影响，这个坑只在本地成立。已写进 `deploy/README` 第五节。

顺带发现覆盖 `spring.config.import` 是无效的——它写在 `application.properties` 文档内部，
外部再声明只是追加；有效的做法是用内联测试属性把密钥值覆盖成空串。

## 5. 矩阵落点核对不是走过场

工单 16 的 130 条 `A` 项核对，一度看起来像是"跑一遍 verify 绿了就行"的形式工作。实际逐条对下来
揪出 `3A-API-05`「跨归属 turn 返回 404」**完全没有断言**——控制器里有这个检查，但没人验证过它。

补的断言要求响应与"根本不存在"完全一致：用 403 或带 detail 的 409 都会让调用方推断出这个
`turnId` 是有效的。

**教训**：每张工单都声称覆盖了自己那几条，但没人横向核对过全表。这类跨工单的完整性缺口，
只有专门的核对步骤能发现。

## 6. 门禁自己没有门禁

`scripts/ci-validate.sh` 从建立起就漏了 `pnpm lint`（只跑 build 和 test），而 CLAUDE.md 里对这条
流水线的描述也一直写着"frontend build"，两处互相印证着同一个缺失，谁也没发现。

本阶段前端改了几十个文件，lint 全靠手动跑。已补上。

## 7. 共享工作区：`git commit -- <path>` 提交的是工作区，不是暂存区

提交 3A 时按路径分组，误把并行会话未暂存的 `.scratch/model-scheduling/issues/23-*.md` 卷进了第一个
提交。我专门隔离了 6 个 `MM` 文件，却漏查了纯 ` M`（只有工作区改动）的文件。

内容无损，但归属错了。**规则**：共享工作区里按路径提交前，要枚举的是"该路径下所有非纯暂存态的
文件"，不只是 `MM`。更稳的做法是只用不带路径的 `git commit` 提交 index，按需分次暂存。

## 8. 有效的做法（继续保持）

- **原型作为硬闸门**：两页界面各出一份可点击原型并经确认后才写前端代码。规格里最容易出错的部分
  （处理中该灰哪些按钮、两种"停用"的区别）在文档里评审成本极高，看一眼页面是秒判。
- **UI 测试 mock 全局 `fetch` 而不是 api 层**：本阶段要证的正是"翻页带了 `before`/`cursor`、命令带了
  `expectedRevisionId`、重试复用了同一个 `clientRequestId`"，mock 掉 api 层等于把被测逻辑一起 mock 掉。
- **服务端是唯一事实来源**：意图树命令成功后一律用返回的整套 `nodes` 原子替换，不做本地单节点 patch。
  父子一致性和 effective enabled 都是服务端算的，本地 patch 只会造出一棵看起来对、实际与运行配置
  不一致的树。
