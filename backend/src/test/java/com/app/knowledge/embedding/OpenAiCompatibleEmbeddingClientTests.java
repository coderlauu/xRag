package com.app.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiCompatibleEmbeddingClientTests {

    private static final String BASE_URL = "https://example.invalid/compatible-mode/v1";

    private RestClient.Builder builder;
    private MockRestServiceServer server;
    private EmbeddingProperties properties;

    @BeforeEach
    void setUp() {
        builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        properties = new EmbeddingProperties();
        properties.setBaseUrl(BASE_URL);
        properties.setApiKey("test-key");
        properties.setModel("text-embedding-v3");
        properties.setDimensions(3);
        properties.setBatchSize(2);
    }

    private EmbeddingClient client() {
        return new OpenAiCompatibleEmbeddingClient(builder, properties);
    }

    /**
     * 协议允许供应商乱序返回。不按 index 重排的话，每个分块都会配到别人的向量——
     * 全程没有任何报错，只是检索结果莫名其妙，是本类最值得测的一条。
     */
    @Test
    void reordersResultsByIndex() {
        server.expect(requestTo(BASE_URL + "/embeddings"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("text-embedding-v3"))
                .andExpect(jsonPath("$.dimensions").value(3))
                .andExpect(jsonPath("$.input[0]").value("first"))
                .andRespond(withSuccess("""
                        {"data":[
                          {"index":1,"embedding":[2,2,2]},
                          {"index":0,"embedding":[1,1,1]}
                        ]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client().embed(List.of("first", "second"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(1f, 1f, 1f);
        assertThat(vectors.get(1)).containsExactly(2f, 2f, 2f);
        server.verify();
    }

    /** 5 条 + batchSize 2 → 3 次请求，且拼回来的顺序仍与输入一致。 */
    @Test
    void splitsIntoBatchesAndKeepsGlobalOrder() {
        server.expect(ExpectedCount.times(3), requestTo(BASE_URL + "/embeddings"))
                .andRespond(request -> {
                    String body = new String(
                            ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                                    .getBodyAsBytes());
                    // 请求体里的文本是 "t0".."t4"，回一个能认出是哪几条的向量
                    List<Integer> indexes = IntStream.range(0, 5)
                            .filter(i -> body.contains("\"t" + i + "\""))
                            .boxed()
                            .toList();
                    String data = indexes.stream()
                            .map(i -> "{\"index\":%d,\"embedding\":[%d,%d,%d]}"
                                    .formatted(indexes.indexOf(i), i, i, i))
                            .collect(Collectors.joining(","));
                    return withSuccess("{\"data\":[" + data + "]}", MediaType.APPLICATION_JSON)
                            .createResponse(request);
                });

        List<float[]> vectors = client().embed(List.of("t0", "t1", "t2", "t3", "t4"));

        assertThat(vectors).hasSize(5);
        for (int i = 0; i < 5; i++) {
            assertThat(vectors.get(i)).as("第 %d 条", i).containsExactly(i, i, i);
        }
        server.verify();
    }

    @Test
    void rejectsWrongDimensions() {
        properties.setBatchSize(10);
        server.expect(requestTo(BASE_URL + "/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,1]}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().embed(List.of("only")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("维度不符")
                .hasMessageContaining("配置 3 维");
    }

    @Test
    void rejectsResultCountMismatch() {
        properties.setBatchSize(10);
        server.expect(requestTo(BASE_URL + "/embeddings"))
                .andRespond(withSuccess("{\"data\":[{\"index\":0,\"embedding\":[1,1,1]}]}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client().embed(List.of("a", "b")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("返回条数与请求不符");
    }

    /** HTTP 错误要带上状态码和响应体——供应商把失败原因写在体里（配额、模型名错等）。 */
    @Test
    void surfacesHttpErrorBody() {
        properties.setBatchSize(10);
        server.expect(requestTo(BASE_URL + "/embeddings"))
                .andRespond(withServerError().body("{\"error\":{\"message\":\"quota exceeded\"}}"));

        assertThatThrownBy(() -> client().embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageContaining("500")
                .hasMessageContaining("quota exceeded");
    }

    /** 空输入不该产生一次请求——空文档在入库流程里是正常情况，不是异常。 */
    @Test
    void doesNotCallApiForEmptyInput() {
        assertThat(client().embed(List.of())).isEmpty();
        server.verify();
    }

    /**
     * 429 之后重试，并且**最终成功**。
     *
     * <p>守的是真实事故：700 分块的文档串行发 70 次请求，8.4 秒后撞上
     * {@code AccountRateLimitExceeded}，整个入库任务失败。没有这条重试，大文档根本进不来。
     *
     * <p>退避延迟在测试里设为 1ms——这里要验的是**重试发生了几次、最后成不成功**，
     * 真去等 1 秒会让测试白白慢几秒，而等待时长本身没有任何被验证的价值。
     */
    @Test
    void retriesAfterRateLimit() {
        properties.setBatchSize(10);
        properties.setMaxRetries(3);
        properties.setRetryBaseDelay(Duration.ofMillis(1));
        properties.setBatchInterval(Duration.ZERO);

        server.expect(ExpectedCount.times(2), requestTo(BASE_URL + "/embeddings"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("{\"error\":{\"code\":\"AccountRateLimitExceeded\"}}"));
        server.expect(ExpectedCount.once(), requestTo(BASE_URL + "/embeddings"))
                .andRespond(withSuccess("""
                        {"data":[{"index":0,"embedding":[1,1,1]}]}
                        """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client().embed(List.of("a"));

        assertThat(vectors).hasSize(1);
        server.verify();
    }

    /** 重试次数用尽后仍要如实失败，且消息说清楚"试过了"——否则排查的人会以为压根没重试。 */
    @Test
    void givesUpAfterMaxRetries() {
        properties.setBatchSize(10);
        properties.setMaxRetries(2);
        properties.setRetryBaseDelay(Duration.ofMillis(1));
        properties.setBatchInterval(Duration.ZERO);

        // 首次 + 2 次重试 = 3 次请求，一次都不能多
        server.expect(ExpectedCount.times(3), requestTo(BASE_URL + "/embeddings"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).body("{\"error\":{}}"));

        assertThatThrownBy(() -> client().embed(List.of("a")))
                .isInstanceOf(EmbeddingRateLimitException.class)
                .hasMessageContaining("已重试 2 次仍被限流");
        server.verify();
    }

    /**
     * 非 429 的错误**不重试**。
     *
     * <p>这条才是把 429 单独成类的意义：维度不符、模型不存在、Key 无效这些重试多少次结果
     * 都一样，白白拖慢失败反馈、还多花几次调用的钱。判据取"只发生了一次请求"。
     */
    @Test
    void doesNotRetryNonRateLimitErrors() {
        properties.setBatchSize(10);
        properties.setMaxRetries(3);
        properties.setRetryBaseDelay(Duration.ofMillis(1));

        server.expect(ExpectedCount.once(), requestTo(BASE_URL + "/embeddings"))
                .andRespond(withServerError().body("{\"error\":{\"message\":\"model not found\"}}"));

        assertThatThrownBy(() -> client().embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .isNotInstanceOf(EmbeddingRateLimitException.class);
        server.verify();
    }
}
