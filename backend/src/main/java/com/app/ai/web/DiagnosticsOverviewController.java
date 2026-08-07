package com.app.ai.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §5 的只读调度总览。依赖健康由现有 {@code GET /api/v1/health/ready} 负责，
 * 本端点不重复代理数据库、Redis、对象存储的健康状态。
 */
@RestController
@RequestMapping("/api/v1/ai/diagnostics")
public class DiagnosticsOverviewController {

    private final DiagnosticsOverviewService service;

    public DiagnosticsOverviewController(DiagnosticsOverviewService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public AiDiagnosticsOverview overview() {
        return service.overview();
    }
}
