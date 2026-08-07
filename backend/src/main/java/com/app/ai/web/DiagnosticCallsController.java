package com.app.ai.web;

import com.app.web.PageResponse;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** api.md §6~§7 的两个只读接口。 */
@RestController
@RequestMapping("/api/v1/ai/diagnostics/calls")
public class DiagnosticCallsController {

    private final DiagnosticCallQueryService service;

    public DiagnosticCallsController(DiagnosticCallQueryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<AiDiagnosticCall> list(
            @RequestParam(required = false) String capability,
            @RequestParam(name = "outcome", required = false) List<String> outcomes,
            @RequestParam(required = false) String tier,
            @RequestParam(required = false) String providerId,
            @RequestParam(required = false) Boolean failoverOccurred,
            @RequestParam(required = false) String requestId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(new DiagnosticCallQuery(
                capability, outcomes, tier, providerId, failoverOccurred, requestId,
                from, to, page, size));
    }

    @GetMapping("/{logicalCallId}")
    public AiDiagnosticCallDetail detail(@PathVariable UUID logicalCallId) {
        return service.detail(logicalCallId);
    }
}
