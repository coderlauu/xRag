package com.app.qa.web;

import java.util.Map;
import org.springframework.http.HttpStatus;

final class QaRequestException extends RuntimeException {

    private final HttpStatus status;
    private final String title;
    private final String code;
    private final Map<String, Object> properties;

    QaRequestException(HttpStatus status, String title, String code, String detail) {
        this(status, title, code, detail, Map.of());
    }

    QaRequestException(
            HttpStatus status,
            String title,
            String code,
            String detail,
            Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.title = title;
        this.code = code;
        this.properties = Map.copyOf(properties);
    }

    HttpStatus status() {
        return status;
    }

    String title() {
        return title;
    }

    String code() {
        return code;
    }

    Map<String, Object> properties() {
        return properties;
    }
}
