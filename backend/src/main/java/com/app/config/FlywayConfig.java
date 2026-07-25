package com.app.config;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Postgres 不可达时，默认的 Flyway 自动配置会让整个应用启动失败——这与本项目
 * "应用始终能启动，依赖是否健康由 /api/v1/health/ready 反映" 的既定模式冲突
 * （见 HealthController）。这里改成只记录警告，让迁移失败不阻塞启动；数据库
 * 可用后需要手动重启应用，或后续为 readiness 补充"迁移是否已执行"的检查。
 */
@Configuration
public class FlywayConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(FlywayConfig.class);

    @Bean
    public FlywayMigrationStrategy flywayMigrationStrategy() {
        return (Flyway flyway) -> {
            try {
                flyway.migrate();
            } catch (Exception exception) {
                LOGGER.warn("Flyway migration did not run at startup: {}", exception.toString());
            }
        };
    }
}
