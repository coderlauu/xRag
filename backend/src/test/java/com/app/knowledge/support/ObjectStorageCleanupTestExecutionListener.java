package com.app.knowledge.support;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * 真实对象存储集成测试的隔离边界。
 *
 * <p>数据库事务回滚不会回滚 S3 PUT，因此每次 Maven 测试运行在首个测试方法前清空一次
 * 专用测试 Bucket。不能在每条测试之间清空：异步入库任务可能刚跨过测试方法边界，
 * 此时删对象会人为制造“任务下载不到自己的输入”。
 * 安全检查故意只允许操作固定的 {@code app-test}，避免配置漂移时误删业务对象。
 */
public class ObjectStorageCleanupTestExecutionListener extends AbstractTestExecutionListener {

    private static final String TEST_BUCKET = "app-test";
    private static final AtomicBoolean CLEANED_FOR_THIS_TEST_RUN = new AtomicBoolean(false);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!testContext.hasApplicationContext()) {
            return;
        }
        if (CLEANED_FOR_THIS_TEST_RUN.compareAndSet(false, true)) {
            clean(testContext);
        }
    }

    private void clean(TestContext testContext) {
        String bucket = testContext.getApplicationContext().getEnvironment()
                .getRequiredProperty("app.storage.bucket");
        if (!TEST_BUCKET.equals(bucket)) {
            throw new IllegalStateException(
                    "测试对象清理仅允许操作专用 Bucket app-test，当前配置为 " + bucket);
        }

        S3Client s3 = testContext.getApplicationContext().getBean(S3Client.class);
        String token = null;
        do {
            var page = s3.listObjectsV2(ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .continuationToken(token)
                    .build());
            page.contents().forEach(object -> s3.deleteObject(request ->
                    request.bucket(bucket).key(object.key())));
            token = page.isTruncated() ? page.nextContinuationToken() : null;
        } while (token != null);
    }
}
