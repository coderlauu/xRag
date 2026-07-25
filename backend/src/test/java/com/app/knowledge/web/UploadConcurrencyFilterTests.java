package com.app.knowledge.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 上传并发限流（test-matrix LIM-01 ~ LIM-03）。
 *
 * <p>**这组用例是单元测试而不是集成测试，这是有意的。** 要验的是"许可被占满时第 N 个请求
 * 被拒"，而集成测试里没有可靠手段把一个上传请求**精确地卡在持有许可的状态**——只能靠传一个
 * 大文件让它慢一点，再赌它比超时更慢，那是典型的 flaky 测试。这里用一个阻塞在闭锁上的假
 * {@link FilterChain} 把那一刻钉死，时序完全确定。
 *
 * <p>{@link UploadConcurrencyFilter} 只依赖三个构造参数、不碰数据库和对象存储，正好适合这么测。
 */
class UploadConcurrencyFilterTests {

    private final ObjectMapper json = new ObjectMapper();
    private final ExecutorService threads = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        threads.shutdownNow();
    }

    private MockHttpServletRequest uploadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/knowledge-bases/1/documents/file");
        request.setRequestURI("/api/v1/knowledge-bases/1/documents/file");
        return request;
    }

    /**
     * LIM-01，同时也是 LIM-04 的核心断言。
     *
     * <p>**被拒绝的请求根本没有进入 filter chain**——而 multipart 请求体正是由 chain 下游的
     * {@code DispatcherServlet} 解析的。所以"chain 未被调用"直接证明了请求体没被读、没有临时
     * 文件产生，也就证明了拦截位置在 Filter 而不是 Interceptor（后者位于解析之后）。
     */
    @Test
    void 许可耗尽时返回429且请求根本没进入chain() throws Exception {
        UploadConcurrencyFilter filter = new UploadConcurrencyFilter(1, Duration.ofMillis(80), json);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger chainCalls = new AtomicInteger();

        // 第一个请求占住唯一的许可，卡在 chain 里不返回
        threads.submit(() -> {
            FilterChain blocking = (req, res) -> {
                chainCalls.incrementAndGet();
                holding.countDown();
                await(release);
            };
            filter.doFilter(uploadRequest(), new MockHttpServletResponse(), blocking);
            return null;
        });
        assertThat(holding.await(2, TimeUnit.SECONDS)).as("第一个请求应已持有许可").isTrue();

        MockHttpServletResponse rejected = new MockHttpServletResponse();
        filter.doFilter(uploadRequest(), rejected, (req, res) -> chainCalls.incrementAndGet());

        assertThat(rejected.getStatus()).isEqualTo(429);
        assertThat(chainCalls.get()).as("被拒的请求不该进入 chain，因此请求体未被解析").isEqualTo(1);
        // 响应体必须是契约形状：Filter 在 @RestControllerAdvice 的作用范围之外，
        // 不显式写的话前端拿到的是容器默认错误页，只能靠状态码猜
        assertThat(rejected.getContentAsString())
                .contains("\"error\":\"UPLOAD_BUSY\"")
                .contains("当前上传任务较多，请稍后重试。");
        assertThat(rejected.getContentType()).contains("application/json");

        release.countDown();
    }

    /** LIM-02：前一个请求完成后许可要能再次拿到，否则限流会变成"只能上传 N 次"。 */
    @Test
    void 前一个请求完成后许可可以再次获取() throws Exception {
        UploadConcurrencyFilter filter = new UploadConcurrencyFilter(1, Duration.ofMillis(80), json);
        AtomicInteger chainCalls = new AtomicInteger();
        FilterChain ok = (req, res) -> chainCalls.incrementAndGet();

        for (int i = 0; i < 3; i++) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(uploadRequest(), response, ok);
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(chainCalls.get()).isEqualTo(3);
    }

    /**
     * LIM-03，这组里最值钱的一条：**chain 抛异常后许可仍要被释放**。
     *
     * <p>漏掉 {@code finally} 的信号量会在第一次异常后永久少一个许可，几次之后上传彻底不可用，
     * 症状却是"偶尔上传不了"——最难查的那种形态。
     */
    @Test
    void chain抛异常后许可仍被释放() throws Exception {
        UploadConcurrencyFilter filter = new UploadConcurrencyFilter(1, Duration.ofMillis(80), json);
        FilterChain boom = (req, res) -> {
            throw new IllegalStateException("上传中途炸了");
        };

        // 异常原样向上抛（RuntimeException 不会被 OncePerRequestFilter 包装），
        // Filter 不吞异常是对的——吞掉会让上传失败变成静默成功
        assertThatThrownBy(() -> filter.doFilter(uploadRequest(), new MockHttpServletResponse(), boom))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("上传中途炸了");

        MockHttpServletResponse next = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();
        filter.doFilter(uploadRequest(), next, (req, res) -> chainCalls.incrementAndGet());
        assertThat(next.getStatus()).as("许可若没释放，这里会等到超时后返回 429").isEqualTo(200);
        assertThat(chainCalls.get()).isEqualTo(1);
    }

    /** 限流只作用于上传接口：列表、详情、分块编辑都不该被这个信号量卡住。 */
    @Test
    void 非上传请求不受限流影响() throws Exception {
        UploadConcurrencyFilter filter = new UploadConcurrencyFilter(1, Duration.ofMillis(80), json);
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        threads.submit(() -> {
            filter.doFilter(uploadRequest(), new MockHttpServletResponse(), (req, res) -> {
                holding.countDown();
                await(release);
            });
            return null;
        });
        assertThat(holding.await(2, TimeUnit.SECONDS)).isTrue();

        AtomicInteger chainCalls = new AtomicInteger();
        for (MockHttpServletRequest other : new MockHttpServletRequest[] {
                new MockHttpServletRequest("GET", "/api/v1/knowledge-bases/1/documents"),
                new MockHttpServletRequest("POST", "/api/v1/documents/1/chunks"),
                new MockHttpServletRequest("GET", "/api/v1/health")}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(other, response, (req, res) -> chainCalls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(200);
        }
        assertThat(chainCalls.get()).as("三个非上传请求都应正常放行").isEqualTo(3);

        release.countDown();
    }

    /** URL 来源的抓取接口不走这个信号量：它不接收请求体，限的是磁盘与 IO，与上传不是一回事。 */
    @Test
    void URL来源接口不被上传限流拦截() throws Exception {
        UploadConcurrencyFilter filter = new UploadConcurrencyFilter(0, Duration.ofMillis(10), json);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicInteger chainCalls = new AtomicInteger();

        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/knowledge-bases/1/documents/url"),
                response, (req, res) -> chainCalls.incrementAndGet());

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(chainCalls.get()).isEqualTo(1);
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
