package com.app;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// Flyway 需要真实可连接的 Postgres 才能执行迁移；这里只验证 Spring 装配是否正确，
// 不要求本机有活的数据库/Redis/对象存储（那是 docs/exec-plans 里 infra 相关脚本和
// 集成测试的职责），所以显式关闭 Flyway 自动迁移。
//
// 同时关掉入库调度器：本类的配置与集成测试不同、注定分属两个 Spring 上下文，而两个
// 上下文的调度器会同时连同一个库抢任务，各自的启动回收还会把对方正在跑的任务判成僵尸
// ——症状是集成测试随机失败在"期望 SUCCESS 实际 FAILED"。见 IngestionDispatcher 注释。
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "app.knowledge.ingestion.enabled=false"
})
class ApplicationTests {

    @Test
    void contextLoads() {
    }
}
