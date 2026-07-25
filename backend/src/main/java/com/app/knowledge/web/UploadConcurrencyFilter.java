package com.app.knowledge.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 上传并发限流（architecture.md §7）。
 *
 * <p>**这个类的全部技术含量在于它是一个 {@code Filter} 而不是 {@code HandlerInterceptor}**。
 * multipart 请求体由 {@code DispatcherServlet} 解析，时机在 Filter 之后、Interceptor 之前：
 *
 * <pre>
 * Filter（tryAcquire，此时请求体还没读）→ DispatcherServlet 解析 multipart → Controller
 * </pre>
 *
 * <p>放在 Interceptor 里限流，文件已经被完整读进临时目录了，限流要保护的磁盘与 IO 早就被
 * 消耗掉，只剩下"拒绝"这个动作还有意义。测试矩阵 LIM-04 专门验这一点，判据是**被拒绝的
 * 请求没有产生临时文件**——那是拦截位置正确性的直接证据。
 *
 * <p>用本地 {@link Semaphore} 而不是 Redisson 的分布式信号量：单实例下两者能力等价，而本地
 * 版零依赖、无网络往返、没有租约过期要处理（ADR 0002 决策 ④）。多实例部署时这一层会失效
 * ——每个实例各限各的，实际并发是 N×max-concurrent。
 */
@Component
public class UploadConcurrencyFilter extends OncePerRequestFilter {

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadConcurrencyFilter.class);

    /** 只作用于上传接口，其余接口（列表、详情、分块编辑）不受影响。 */
    private static final String UPLOAD_PATH_SUFFIX = "/documents/file";

    private final Semaphore permits;
    private final Duration acquireTimeout;
    private final ObjectMapper json;

    public UploadConcurrencyFilter(
            @Value("${app.upload.max-concurrent:3}") int maxConcurrent,
            @Value("${app.upload.acquire-timeout:5s}") Duration acquireTimeout,
            ObjectMapper json) {
        // 公平模式：先到先得。上传耗时长，非公平信号量下先到的请求可能被后到的反复插队，
        // 一直等到超时被拒——那对用户表现为"我先点的反而失败了"。
        this.permits = new Semaphore(maxConcurrent, true);
        this.acquireTimeout = acquireTimeout;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod())
                && request.getRequestURI().endsWith(UPLOAD_PATH_SUFFIX));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean acquired;
        try {
            // 带超时而不是无限期排队：排队的请求同样占着一个容器线程，无限等只会把线程池
            // 也拖垮，而且用户那边是一个永远不返回的请求。
            acquired = permits.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new ServletException("等待上传许可时被中断", interrupted);
        }

        if (!acquired) {
            LOGGER.info("上传许可耗尽，拒绝请求：{}", request.getRequestURI());
            writeBusy(response);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            // **必须在 finally 里释放**：任何异常路径漏掉一次，信号量就永久少一个许可，
            // 几次之后上传功能彻底不可用，而且症状是"偶尔上传不了"这种最难查的形态。
            permits.release();
        }
    }

    /** 直接写响应体而不是抛 {@link ApiException}：Filter 在 `@RestControllerAdvice` 的作用范围之外。 */
    private void writeBusy(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(new ApiExceptionHandler.ErrorResponse(
                "UPLOAD_BUSY", "当前上传任务较多，请稍后重试。")));
    }
}
