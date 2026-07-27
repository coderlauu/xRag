package com.app.knowledge.embedding;

/**
 * 供应商因请求过于频繁而拒绝（HTTP 429）。
 *
 * <p>**单独成类是因为它是唯一值得重试的失败**：维度不符、模型不存在、Key 无效这些
 * 重试多少次结果都一样，只有限流是"等一会儿再来就行"。用状态码在调用处 if 判断也能做到，
 * 但那样重试条件会散落在字符串匹配里；这里让类型来承担这个判断。
 *
 * <p>重试用尽后它仍然会向上抛，被入库流程当作普通失败标记为 {@code FAILED}——
 * 那时错误消息里已经写明了重试过几次，用户看到的是"试过了，还是不行"而不是一次性放弃。
 */
public class EmbeddingRateLimitException extends EmbeddingException {

    public EmbeddingRateLimitException(String message) {
        super(message);
    }
}
