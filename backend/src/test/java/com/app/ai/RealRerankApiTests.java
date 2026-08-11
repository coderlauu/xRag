package com.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.RerankCandidate;
import com.app.ai.model.RerankClient;
import com.app.ai.model.RerankCommand;
import com.app.ai.model.RerankResult;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 打真实 Rerank API 的准入验证（工单 23）。守门方式同 {@link RealChatApiTests}，另加一道
 * 目录守门：`ai-models.yml` 里没有启用的 RERANK deployment 时跳过，而不是红。
 *
 * <p>**2026-08-07 实测该能力尚未通过准入，因此目录里 `aliyun-qwen3-rerank-cn` 维持
 * `enabled: false`，本类当前会跳过。** 根因不是凭据：`OpenAiCompatibleRerankProvider`
 * 打的是 OpenAI 风格的 `{base}/reranks`，而百炼 compatible-mode 下该路径（连同单数
 * `/rerank`）返回 404 空响应体；同一把 Key 打 DashScope 原生
 * `/api/v1/services/rerank/text-rerank/text-rerank` 返回 200 与真实 relevance_score。
 * 走 xrag 链路时表现为 `ModelCallException`「模型调用参数无效」——404 被归一化成
 * INVALID_REQUEST，措辞会把人往"请求参数写错了"上带，实际是端点不存在。
 *
 * <p>补上 DashScope 原生 rerank 适配器并把 deployment 打开后，本类无需改动即可生效。
 *
 * <pre>{@code
 * DASHSCOPE_API_KEY=<你的 Key> ./mvnw -B test -Dtest=RealRerankApiTests
 * }</pre>
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class RealRerankApiTests {

    @Autowired
    private RerankClient rerankClient;

    @Autowired
    private ModelCatalogSnapshot catalog;

    @Test
    void 真实重排按相关性重新排序() {
        // deployments() 里保留着 enabled=false 的条目（路由阶段才过滤），所以必须显式判 enabled
        Assumptions.assumeTrue(
                catalog.deployments().values().stream()
                        .anyMatch(deployment -> deployment.enabled()
                                && deployment.capabilities().contains(Capability.RERANK)),
                "目录里没有启用的 RERANK deployment，跳过真实准入");

        RerankResult result = rerankClient.rerank(new RerankCommand(
                "向量检索是怎么做的",
                List.of(
                        new RerankCandidate("irrelevant", "今天天气不错，适合出门散步。"),
                        new RerankCandidate("relevant", "向量检索把文本转成向量，再用近邻搜索找出最相似的片段。")),
                2,
                null,
                CallOptions.defaults()));

        assertThat(result.rerankApplied())
                .as("目录里声明了 RERANK 且密钥已注入，此时降级即为配置故障（architecture §11）")
                .isTrue();
        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).id())
                .as("相关文档应排在前面")
                .isEqualTo("relevant");
        assertThat(result.items().get(0).relevanceScore()).isNotNull();
        assertThat(result.execution().actualExecution().providerId().value())
                .isEqualTo("aliyun-model-studio");
    }
}
