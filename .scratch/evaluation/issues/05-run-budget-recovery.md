# 05 Run 状态机、预算、claim 与恢复

- `status`: blocked
- `depends_on`: 01,02
- 实现三模式、四维预算、幂等、取消、协调 claim、重启恢复和容量限制。
- 验收：EV-S01～S13；Provider 调用不在事务中。
