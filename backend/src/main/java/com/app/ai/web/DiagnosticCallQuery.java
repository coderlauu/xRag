package com.app.ai.web;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * api.md §6 的筛选条件。不同字段取 AND，{@code outcomes} 内部取 OR。
 *
 * <p>全部字段可空，空即"不筛"。时间窗口的默认值与上限由
 * {@link DiagnosticCallQueryService} 统一决定，不在这里散落默认值。
 */
public record DiagnosticCallQuery(
        String capability,
        List<String> outcomes,
        String tier,
        String providerId,
        Boolean failoverOccurred,
        String requestId,
        OffsetDateTime from,
        OffsetDateTime to,
        Integer page,
        Integer size) {

    public DiagnosticCallQuery {
        outcomes = outcomes == null ? List.of() : List.copyOf(outcomes);
    }
}
