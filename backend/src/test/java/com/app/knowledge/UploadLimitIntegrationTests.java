package com.app.knowledge;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 限流 Filter **确实挂进了容器的过滤器链**，并且只作用于上传路径。
 *
 * <p>{@link com.app.knowledge.web.UploadConcurrencyFilterTests} 覆盖的是 Filter 自身的逻辑
 * （超时、429 响应体、finally 释放、路径判断），那是纯单元测试、不经过 Spring。**它证明不了
 * 这个 Bean 真的被注册进了 servlet 过滤器链**——`@Component` 的 `OncePerRequestFilter` 靠
 * 自动注册生效，一旦哪天被 `FilterRegistrationBean` 关掉或改了顺序，单元测试照样全绿。
 *
 * <p>把许可数设成 **0** 就不需要制造并发：每个上传请求都必然拿不到许可。判据是确定性的。
 *
 * <p>{@code app.knowledge.ingestion.enabled=false} 是必须的——本类的属性与其他集成测试不同，
 * 注定另起一个 Spring 上下文，而**每个上下文都有自己的 `IngestionDispatcher`**，它的启动回收
 * 会把别的上下文正在跑的任务判成僵尸（见 {@link FakeEmbeddingConfig} 的注释）。这里用不到
 * 入库，直接关掉。
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "app.upload.max-concurrent=0",
        "app.upload.acquire-timeout=10ms",
        "app.knowledge.ingestion.enabled=false"})
class UploadLimitIntegrationTests {

    @Autowired
    private MockMvc mvc;

    @Test
    void 许可为0时上传请求被过滤器拒绝并返回契约错误体() throws Exception {
        mvc.perform(multipart("/api/v1/knowledge-bases/{kbId}/documents/file", 1L)
                        .file(new MockMultipartFile("file", "限流.txt", "text/plain",
                                "内容".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("UPLOAD_BUSY"))
                .andExpect(jsonPath("$.message").value("当前上传任务较多，请稍后重试。"));
    }

    /**
     * 同一个上下文里非上传接口照常工作。
     *
     * <p>这条断言的分量在于：许可数是 0，如果 Filter 的路径判断写错（比如漏了
     * {@code shouldNotFilter}），**整个应用的所有接口都会返回 429**——那是一个会瞬间瘫痪
     * 全站的错误，而它在只测上传路径的用例里完全看不出来。
     */
    @Test
    void 许可为0也不影响非上传接口() throws Exception {
        mvc.perform(get("/api/v1/knowledge-bases")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
    }
}
