package com.app.knowledge.web;

import com.app.knowledge.model.IngestionRun;
import com.app.knowledge.model.IngestionTriggerSource;
import com.app.knowledge.service.IngestionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/documents/{docId}/ingestion-runs")
public class IngestionController {

    public record TriggerResponse(long runId) {}

    private final IngestionService service;

    public IngestionController(IngestionService service) {
        this.service = service;
    }

    /** {@code 202} 而不是 {@code 201}：请求已受理但还没做完，语义上正是 Accepted。 */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TriggerResponse trigger(@PathVariable long docId) {
        return new TriggerResponse(service.trigger(docId, IngestionTriggerSource.MANUAL));
    }

    @GetMapping
    public PageResponse<IngestionRun> list(
            @PathVariable long docId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(docId, page, size);
    }
}
