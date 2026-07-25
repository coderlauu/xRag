package com.app.knowledge.web;

import com.app.knowledge.model.KnowledgeBase;
import com.app.knowledge.service.KnowledgeBaseService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** api.md §2 的 5 个接口。 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    /** {@code embeddingModel} 等字段传了也忽略，因此请求体只声明可接受的两个字段。 */
    public record CreateRequest(String name, String description) {}

    public record UpdateRequest(String name, String description) {}

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeBase create(@RequestBody CreateRequest request) {
        return service.create(request.name(), request.description());
    }

    @GetMapping
    public PageResponse<KnowledgeBase> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(page, size);
    }

    @GetMapping("/{kbId}")
    public KnowledgeBase get(@PathVariable long kbId) {
        return service.get(kbId);
    }

    @PutMapping("/{kbId}")
    public KnowledgeBase update(@PathVariable long kbId, @RequestBody UpdateRequest request) {
        return service.update(kbId, request.name(), request.description());
    }

    @DeleteMapping("/{kbId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long kbId) {
        service.delete(kbId);
    }
}
