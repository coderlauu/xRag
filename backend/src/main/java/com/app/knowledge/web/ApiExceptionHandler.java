package com.app.knowledge.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 把 {@link ApiException} 翻译成 api.md §1 的错误响应体。
 *
 * <p>只处理本模块显式抛出的业务异常。未预期的异常交给 Spring 默认处理（500），
 * 不在这里兜底成一个笼统的错误码——那会把真正的 bug 藏起来。
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

    public record ErrorResponse(String error, String message) {}

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handle(ApiException exception) {
        LOGGER.debug("{} {}: {}", exception.status().value(), exception.code(), exception.getMessage());
        return ResponseEntity.status(exception.status())
                .body(new ErrorResponse(exception.code(), exception.getMessage()));
    }

    /**
     * 超出 {@code spring.servlet.multipart.max-file-size} 时由 multipart 解析器抛出，
     * 时机在控制器方法之前。**不处理的话返回的是容器默认错误页而不是契约里的
     * {@code {error, message}}**，前端只能靠状态码猜。
     *
     * <p>本类因此不能限定 {@code basePackages}——异常发生时 handler 还没解析出来，
     * 限定包的 advice 不一定会被应用。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException exception) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "文件超过 50MB 上限，无法上传。请压缩或拆分后重试。"));
    }
}
