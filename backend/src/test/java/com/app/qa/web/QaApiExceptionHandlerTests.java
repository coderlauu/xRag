package com.app.qa.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.qa.generation.GenerationQueueFullException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class QaApiExceptionHandlerTests {

    @Test
    void generation队列满在Accepted前返回429与RetryAfter() {
        var request = new MockHttpServletRequest("POST", "/api/qa/turns:stream");

        var response = new QaApiExceptionHandler()
                .handleGenerationQueueFull(new GenerationQueueFullException(), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("1");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getProperties())
                .containsEntry("code", "GENERATION_QUEUE_FULL");
        assertThat(response.getBody().getInstance()).hasToString("/api/qa/turns:stream");
    }
}
