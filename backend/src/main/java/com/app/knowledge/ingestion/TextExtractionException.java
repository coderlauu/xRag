package com.app.knowledge.ingestion;

/** 文本提取失败。入库流程捕获它并把任务标记为 {@code FAILED}（phase = EXTRACT）。 */
public class TextExtractionException extends RuntimeException {

    public TextExtractionException(String message, Throwable cause) {
        super(message, cause);
    }
}
