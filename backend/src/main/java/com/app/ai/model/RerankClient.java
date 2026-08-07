package com.app.ai.model;

/**
 * Rerank 能力端口。返回结果要么是真实重排，要么是显式 Noop 降级，两者由
 * {@link RerankResult#rerankApplied()} 与 {@link RerankResult#degradationReason()} 区分。
 *
 * <p>非法请求、调用方取消和鉴权/配置错误不降级为 Noop，直接抛 {@link ModelCallException}：
 * 把配置事故伪装成正常质量下降会让人永远查不出为什么排序一直没生效。
 */
public interface RerankClient {

    RerankResult rerank(RerankCommand command);
}
