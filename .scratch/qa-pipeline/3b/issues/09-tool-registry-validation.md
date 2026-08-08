# 09 — 工具白名单、Schema 复核与状态机

**What to build:** 工具注册表、代码侧 Schema 复核与七态状态机；不接 Chat 调用循环。

**Blocked by:** 02
**Status:** complete

## Slice

- 启动期加载白名单；重复 `name` 启动失败。
- 复核五道：字段名白名单 → 类型 → 枚举/取值 → 必填 → 禁止默认值兜底。
- 状态闭集 `PENDING_CONFIRM/RUNNING/SUCCEEDED/FAILED/CANCELLED/UNAVAILABLE/ABORTED`。
- 未注册工具进 `UNAVAILABLE`，不静默跳过。

## Acceptance

- [x] 3B-TOOL-01~08 自动化通过（01 在工单 01 的 `QaToolPropertiesTests` 覆盖）。
- [x] 模型编造的字段被白名单过滤，不进 `sanitized` 也不发给工具。
- [x] 缺必填参数直接 `ABORTED`，`sanitized` 为 null——没有可执行状态被产生。
- [x] 枚举越界的提示带允许值，不含 Schema 原文。
- [x] 本工单不实现确认 API 与工具执行（属工单 10）。

**Evidence:** `ToolAdmissionTests`（10 项）。

**`ToolCallAdmission` 刻意做成纯函数**（不执行、不写库），所以"确认前一律不执行"这条语义
可以在没有任何外部依赖的测试里被证明，不必靠观察日志或数据库反推。

**一个测试前提被实现推翻**：原本想覆盖"非 JSON 参数"，但 `ToolCall` 构造期就用
`JsonTextValidator` 拦住了畸形 JSON——那条路径根本不可达。改为覆盖真正可达的
"合法 JSON 但不是 object"（数组、裸字符串、数字）。

**没有实现 `register()` / `unregister()`**：课程实现暴露了运行期变更能力却按启动期一次性写入
来做（普通 HashMap、无同步）。接口不给这两个方法，就不存在那种不一致。
