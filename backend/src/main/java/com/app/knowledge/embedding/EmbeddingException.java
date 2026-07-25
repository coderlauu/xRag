package com.app.knowledge.embedding;

/** Embedding 调用失败。入库流程捕获它并把任务标记为 {@code FAILED}（phase = EMBED）。 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
}
