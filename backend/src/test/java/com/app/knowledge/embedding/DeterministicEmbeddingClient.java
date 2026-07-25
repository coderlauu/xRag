package com.app.knowledge.embedding;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 确定性假实现：同样的文本永远得到同样的向量，不发生任何网络调用。
 *
 * <p>存在的理由是 CI——真实 Embedding API 按次计费，让 CI 每次跑集成测试都产生真实费用，
 * 还得把密钥放进仓库 Secrets，两件事都不划算。代价是 **CI 并没有覆盖真实 API 链路**，
 * 真实调用只在本地手工验证（工单 19 会把这一点写进 deploy/README.md，否则后来者会以为
 * CI 已经验过了）。
 *
 * <p>放在测试源码而不是主源码：它是测试替身，不该被打进生产 jar。集成测试用
 * {@code @TestConfiguration} 覆盖 {@code embeddingClient} Bean 即可。
 */
public class DeterministicEmbeddingClient implements EmbeddingClient {

    private final int dimensions;

    public DeterministicEmbeddingClient(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(pseudoVector(text));
        }
        return vectors;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    private float[] pseudoVector(String text) {
        // 用内容的完整字节做种子，而不是 String.hashCode()——后者在不同内容上碰撞得
        // 太容易，会让"两个不同分块拿到同一个向量"这种断言失效。
        long seed = 1125899906842597L;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            seed = 31 * seed + b;
        }
        Random random = new Random(seed);
        float[] vector = new float[dimensions];
        double norm = 0;
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) random.nextGaussian();
            norm += (double) vector[i] * vector[i];
        }
        float length = (float) Math.sqrt(norm);
        for (int i = 0; i < dimensions; i++) {
            vector[i] /= length;
        }
        return vector;
    }
}
