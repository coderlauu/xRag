package com.app.ai.model;

import java.util.List;
import java.util.Objects;

/**
 * Rerank 请求。候选用稳定 ID + 可发送文本表示，不接收裸字符串列表——Provider 返回的是
 * index，没有稳定 ID 就只能靠下标对齐，调用方一旦重排或过滤过输入就会静默错位。
 *
 * <p>这里只做结构校验（非空）。ID 唯一性与 {@code topN} 范围属于**语义**校验，由
 * {@code RoutedRerankClient} 在调用前完成，好让它们和其他失败一样产出
 * {@code INVALID_REQUEST} 的 {@link ModelCallException} 与一条完整诊断，
 * 而不是在构造期抛一个没有 requestId、没有诊断记录的 {@code IllegalArgumentException}。
 */
public record RerankCommand(
        String query,
        List<RerankCandidate> candidates,
        int topN,
        ServiceTier tier,
        CallOptions options) {

    public RerankCommand {
        Objects.requireNonNull(query, "rerank query must not be null");
        candidates = List.copyOf(candidates);
        tier = tier == null ? ServiceTier.BALANCED : tier;
        options = options == null ? CallOptions.defaults() : options;
    }
}
