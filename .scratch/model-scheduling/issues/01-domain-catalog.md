# 01 — 领域词汇与不可变模型目录

**What to build:** 定义调度领域身份与文件配置目录，完成不可变快照、启动校验、secret 解析边界和脱敏 fingerprint；不在本工单定义 Provider SPI。

**Blocked by:** 无

**Status:** done（2026-08-02）

## Slice

- 定义 `Capability`、`ServiceTier`、`ProviderId`、`DeploymentId`、`ModelIdentity`、`QuotaDomain` 等不可混用的 value type。
- 新增 `ai-models.yml` 绑定、不可变 catalog 快照和结构校验；文件只保存 secret property 名。
- 校验 identity、能力、tier、默认候选、Embedding 维度/预处理/批量上限和数值边界。
- 生成不含密钥值的稳定 config fingerprint；缺 secret 时保留 `UNCONFIGURED` deployment。
- 新增 `com.app.ai.package-info`，写清模块边界与事务纪律。

## Acceptance

- [x] CFG-01~CFG-09、CFG-11~CFG-17 自动化；CFG-15~CFG-17 分别锁定 artifact/version、legacy 单 deployment 和 preprocessing 证据规则。
- [x] 相同配置生成相等快照和 fingerprint；只改 secret 值不改变 fingerprint。
- [x] 生产配置对象没有 secret getter 或可序列化密钥值。
- [x] 本工单不新增 Provider SPI、HTTP adapter、数据库表或诊断 API。
- [x] `./mvnw -B verify` 通过。

**Design:** [architecture §3~5](../../../tech/model-scheduling/architecture.md)、[test CFG](../../../tech/model-scheduling/test-matrix.md#2-cfg--配置与模型目录)
