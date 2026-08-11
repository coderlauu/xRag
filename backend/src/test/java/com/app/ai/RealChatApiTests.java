package com.app.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.CallOptions;
import com.app.ai.model.ChatClient;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatResult;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.model.UserMessage;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 打真实 Chat / 流式 Chat API 的准入验证（工单 23，architecture §13.3 准入清单第 3 条：
 * 能力必须实际调用通过，不能由“OpenAI compatible”推断）。
 *
 * <p>**只在环境变量 {@code DASHSCOPE_API_KEY} 存在时执行**，与
 * {@link com.app.knowledge.embedding.RealEmbeddingApiTests} 同一守门方式：真实调用按次计费，
 * CI 继续用假 Provider（工单 22），真实链路靠本类在本地手工跑。
 *
 * <pre>{@code
 * DASHSCOPE_API_KEY=<你的 Key> ./mvnw -B test -Dtest=RealChatApiTests
 * }</pre>
 *
 * <p>提示词都要求模型只回极短内容，把每次调用的输出 token 压到个位数——工单 23 规定每能力
 * 不超过 20 次短请求。
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "DASHSCOPE_API_KEY", matches = ".+")
class RealChatApiTests {

    private static final String SHORT_PROMPT = "只回答两个字，不要标点：你好";

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private StreamingChatClient streamingChatClient;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void 同步调用返回内容并登记实际执行目标() {
        ChatResult result = chatClient.complete(new ChatCommand(
                List.of(new UserMessage(SHORT_PROMPT)), List.of(), null, CallOptions.defaults()));

        assertThat(result.message().text()).isNotBlank();
        assertThat(result.finishReason()).isNotNull();
        // 准入要看的是"谁真的执行了"，而不只是"有没有返回"
        assertThat(result.execution().actualExecution().providerId().value())
                .isEqualTo("aliyun-model-studio");
        assertThat(result.execution().actualExecution().modelIdentity().value())
                .isEqualTo("qwen-plus-2025-12-01");
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().totalTokens()).isGreaterThan(0);

        awaitDiagnostic(result.execution().logicalCallId(), "CHAT", "SUCCEEDED");
    }

    /**
     * 流式的验证点与同步不同：必须真的分多次到达，且以 {@code Completed} 正常收尾。
     *
     * <p>只断言“拼起来非空”是不够的——一次性返回整段的伪流式也能满足它，而
     * {@link ChatStreamEvent.StreamStarted} + 至少一个 {@link ChatStreamEvent.TextDelta} +
     * {@link ChatStreamEvent.Completed} 的事件序列才是流式协议真正跑通的证据。
     */
    @Test
    void 流式调用产生事件序列并正常收尾() {
        List<ChatStreamEvent> events = streamingChatClient
                .stream(new ChatCommand(List.of(new UserMessage(SHORT_PROMPT)),
                        List.of(), null, CallOptions.defaults()))
                .collectList()
                .block(Duration.ofSeconds(120));

        assertThat(events).isNotNull();
        assertThat(events).noneMatch(event -> event instanceof ChatStreamEvent.StreamFailed);

        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.StreamStarted.class);
        ChatStreamEvent.StreamStarted started = (ChatStreamEvent.StreamStarted) events.get(0);
        assertThat(started.execution().actualExecution().providerId().value())
                .isEqualTo("aliyun-model-studio");

        String text = events.stream()
                .filter(ChatStreamEvent.TextDelta.class::isInstance)
                .map(event -> ((ChatStreamEvent.TextDelta) event).text())
                .reduce("", String::concat);
        assertThat(text).isNotBlank();

        assertThat(events.get(events.size() - 1)).isInstanceOf(ChatStreamEvent.Completed.class);
        ChatStreamEvent.Completed completed = (ChatStreamEvent.Completed) events.get(events.size() - 1);
        assertThat(completed.finishReason()).isNotNull();

        awaitDiagnostic(started.execution().logicalCallId(), "STREAM_CHAT", "SUCCEEDED");
    }

    /**
     * 断言这一次调用在诊断表里留下了记录。
     *
     * <p>**必须等**：{@code AsyncDiagnosticWriter} 把记录放进队列就返回，写线程 250ms 刷一次，
     * 调用返回的瞬间去查必然查不到。按 logicalCallId 精确定位，避免与其它调用混淆。
     *
     * <p>**记录落在 `xrag_test` schema**，不是 `public`——surefire 在 pom.xml 里把
     * `spring.datasource.url` 指到了 `currentSchema=xrag_test`。手工核对真实调用证据时
     * 要写 `SELECT ... FROM xrag_test.ai_logical_call`，查 public 会得到"一条都没写"的
     * 假象（2026-08-07 就这样误判过一轮）。
     */
    private void awaitDiagnostic(UUID logicalCallId, String capability, String outcome) {
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(250, TimeUnit.MILLISECONDS)
                .untilAsserted(() -> {
                    var rows = jdbc.queryForList(
                            "SELECT capability, outcome, http_status FROM ai_logical_call WHERE id = ?",
                            logicalCallId);
                    assertThat(rows).as("诊断记录应已落库").hasSize(1);
                    assertThat(rows.get(0).get("capability")).isEqualTo(capability);
                    assertThat(rows.get(0).get("outcome")).isEqualTo(outcome);
                    assertThat(rows.get(0).get("http_status")).isEqualTo(200);
                });
    }
}
