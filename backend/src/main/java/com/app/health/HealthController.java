package com.app.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redisTemplate;
    private final S3Client s3Client;
    private final String storageBucket;

    public HealthController(
            JdbcTemplate jdbcTemplate,
            StringRedisTemplate redisTemplate,
            S3Client s3Client,
            @org.springframework.beans.factory.annotation.Value("${app.storage.bucket}") String storageBucket) {
        this.jdbcTemplate = jdbcTemplate;
        this.redisTemplate = redisTemplate;
        this.s3Client = s3Client;
        this.storageBucket = storageBucket;
    }

    @GetMapping
    public Map<String, String> getHealth() {
        return Map.of("status", "ok");
    }

    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> getReadiness() {
        Map<String, String> checks = new LinkedHashMap<>();
        boolean allHealthy = true;

        allHealthy &= checkDependency(checks, "postgres", () -> jdbcTemplate.queryForObject("select 1", Integer.class));
        allHealthy &= checkDependency(checks, "redis", () -> redisTemplate.getConnectionFactory().getConnection().ping());
        allHealthy &= checkDependency(checks, "objectStorage",
                () -> s3Client.headBucket(HeadBucketRequest.builder().bucket(storageBucket).build()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", allHealthy ? "ready" : "not_ready");
        response.put("checks", checks);

        HttpStatus status = allHealthy ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE;
        return ResponseEntity.status(status).body(response);
    }

    private boolean checkDependency(Map<String, String> checks, String name, Runnable check) {
        try {
            check.run();
            checks.put(name, "ok");
            return true;
        } catch (Exception exception) {
            checks.put(name, "fail: " + exception.getClass().getSimpleName());
            return false;
        }
    }
}
