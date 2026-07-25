package com.app.knowledge.web;

import org.springframework.http.HttpStatus;

/**
 * 业务异常。{@code error} 码与 HTTP 状态一起构成 api.md §1 的错误契约。
 *
 * <p>{@code message} 会**原样出现在响应体里**，因此必须是可以直接展示给用户的完整
 * 句子，不能写 {@code "invalid state"} 这类开发者向的字符串——`INVALID_STATE` 一个码
 * 对应至少三种情况，前端只能靠 message 区分（api.md §1、ui-spec §7）。
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException invalidRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message);
    }
}
