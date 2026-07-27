package com.app.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 打真实 Embedding API 的验证（测试矩阵 M-06）。
 *
 * <p>**只在环境变量 {@code EMBEDDING_API_KEY} 存在时执行**，其余情况自动跳过。这不是
 * 偷偷降级：真实调用按次计费，让 CI 每次都产生费用、还要把密钥放进仓库 Secrets，两件事
 * 都不划算——CI 用 {@link DeterministicEmbeddingClient} 覆盖，真实链路靠本类在本地手工跑。
 * 这个取舍要写进 deploy/README.md（工单 19），否则后来者会以为 CI 已经验过真实 API。
 *
 * <pre>{@code
 * EMBEDDING_API_KEY=<你的 Key> ./mvnw -B test -Dtest=RealEmbeddingApiTests
 * }</pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "EMBEDDING_API_KEY", matches = ".+")
class RealEmbeddingApiTests {

    /** 同一段文本"算出来是不是同一个向量"的判据。取值依据见下方用例的表格。 */
    private static final double SAME_TEXT_THRESHOLD = 0.95;

    @Autowired
    private EmbeddingClient client;

    @Autowired
    private EmbeddingProperties properties;

    @Test
    void 真实调用返回的维度与配置一致() {
        List<float[]> vectors = client.embed(List.of("知识库分块的向量化测试"));

        assertThat(vectors).hasSize(1);
        assertThat(vectors.get(0)).hasSize(properties.getDimensions());
        // 不是一串 0：真返回了内容，而不是某种占位响应
        assertThat(norm(vectors.get(0))).isGreaterThan(0.1);
    }

    /**
     * 超过 batch-size 时必须自动分批，且顺序与输入严格对应。
     *
     * <p>**不能用向量相等来断言**：实测同一段文本两次调用返回的向量并不逐位相同
     * （量级 1e-3 的抖动，大概率是低精度推理所致）。改用余弦相似度——单独算出来的向量
     * 与它在批次里的那一条必须几乎重合，而与相邻文本的向量明显不同。后者才是这条用例
     * 真正要防的：分批拼接时错位。
     *
     * <p>**阈值取 0.95，依据是 `2026-07-28` 实测的两端分布**，不是拍脑袋的"接近 1"：
     *
     * <table><tr><th>测量</th><th>实测值</th></tr>
     * <tr><td>同一文本三次独立调用，两两余弦</td><td>0.9977 / 0.9979 / 0.9982</td></tr>
     * <tr><td>相邻文本（只差一个数字，极相似）</td><td>0.886</td></tr>
     * <tr><td>相邻文本（语义不同）</td><td>0.214</td></tr></table>
     *
     * <p>原先两条断言都用 0.999，两个问题：抖动下界 0.9977 就低于它，"重合"那条**随机变红**
     * （本次就是 0.998986 差 1.4e-5 没过）；而"不重合"那条用 0.999 又几乎拦不住任何东西——
     * 真错位到一段相似文本时余弦 0.886，照样满足 &lt; 0.999。**两个阈值挤在一起，一条 flaky
     * 一条没区分力。** 现在用同一个 0.95 两边夹住，距离两端各留约 0.05 与 0.06 的余量。
     */
    @Test
    void 超过单批上限时自动分批且顺序正确() {
        List<String> texts = java.util.stream.IntStream.range(0, 23)
                .mapToObj(i -> "第 " + i + " 段内容，用于验证分批后的顺序对应关系。")
                .toList();

        List<float[]> vectors = client.embed(texts);

        assertThat(vectors).hasSize(23);
        assertThat(vectors).allSatisfy(v -> assertThat(v).hasSize(properties.getDimensions()));

        float[] alone = client.embed(List.of(texts.get(17))).get(0);
        assertThat(cosine(vectors.get(17), alone))
                .as("第 17 条应与单独计算的结果重合（只允许推理抖动，实测下界 0.9977）")
                .isGreaterThan(SAME_TEXT_THRESHOLD);
        assertThat(cosine(vectors.get(16), alone))
                .as("第 16 条不应与第 17 条的向量重合（实测相邻文本 0.886，错位就会顶到这里）")
                .isLessThan(SAME_TEXT_THRESHOLD);
    }

    private static double norm(float[] vector) {
        double sum = 0;
        for (float value : vector) {
            sum += (double) value * value;
        }
        return Math.sqrt(sum);
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
        }
        return dot / (norm(a) * norm(b));
    }
}
