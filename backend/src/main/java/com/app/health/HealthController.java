package com.app.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
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
    public Map<String, Object> getReadiness() {
        Map<String, String> checks = new LinkedHashMap<>();

        try {
            jdbcTemplate.queryForObject("select 1", Integer.class);
            checks.put("postgres", "ok");

            redisTemplate.getConnectionFactory().getConnection().ping();
            checks.put("redis", "ok");

            s3Client.headBucket(HeadBucketRequest.builder().bucket(storageBucket).build());
            checks.put("objectStorage", "ok");
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Dependencies are unavailable");
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "ready");
        response.put("checks", checks);
        return response;
    }
}
