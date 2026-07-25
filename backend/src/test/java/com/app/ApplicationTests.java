package com.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Flyway 需要真实可连接的 Postgres 才能执行迁移；这里只验证 Spring 装配是否正确，
// 不要求本机有活的数据库/Redis/对象存储（那是 docs/exec-plans 里 infra 相关脚本和
// 未来集成测试的职责），所以显式关闭 Flyway 自动迁移。
@SpringBootTest(properties = "spring.flyway.enabled=false")
class ApplicationTests {

    @Test
    void contextLoads() {
    }
}
