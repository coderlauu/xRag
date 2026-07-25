package com.app.knowledge.web;

import com.app.knowledge.model.DocumentChunk;
import com.app.knowledge.service.ChunkService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * api.md §4 的分块查看与编辑。
 *
 * <p>路径风格见 api.md §1：从属集合挂在父资源下（{@code /documents/{docId}/chunks}），
 * 单个资源用扁平路径（{@code /chunks/{chunkId}}）——单资源操作只需要一个全局唯一 id，
 * 强行带上父路径会引入"父子 id 不匹配"这种本可以不存在的错误情况。
 */
@RestController
public class ChunkController {

    private final ChunkService service;

    public ChunkController(ChunkService service) {
        this.service = service;
    }

    /** 客户端传的 {@code charCount} 等派生字段一律忽略，服务端重算（api.md §4）。 */
    public record UpdateChunkRequest(String content) {}

    /** {@code chunkIndex} 可缺省，缺省时追加到末尾（当前最大序号 + 1）。 */
    public record CreateChunkRequest(String content, Integer chunkIndex) {}

    public record SetEnabledRequest(Boolean enabled) {}

    public record BatchEnabledRequest(List<Long> chunkIds, Boolean enabled) {}

    @GetMapping("/api/v1/documents/{docId}/chunks")
    public PageResponse<DocumentChunk> list(
            @PathVariable long docId,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(docId, enabled, page, size);
    }

    @PostMapping("/api/v1/documents/{docId}/chunks")
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentChunk create(@PathVariable long docId, @RequestBody CreateChunkRequest request) {
        return service.create(docId, request.content(), request.chunkIndex());
    }

    @PutMapping("/api/v1/chunks/{chunkId}")
    public DocumentChunk update(@PathVariable long chunkId, @RequestBody UpdateChunkRequest request) {
        return service.updateContent(chunkId, request.content());
    }

    @PatchMapping("/api/v1/chunks/{chunkId}/enabled")
    public DocumentChunk setEnabled(@PathVariable long chunkId, @RequestBody SetEnabledRequest request) {
        if (request.enabled() == null) {
            throw ApiException.invalidRequest("缺少 enabled 字段。");
        }
        return service.setEnabled(chunkId, request.enabled());
    }

    @PatchMapping("/api/v1/documents/{docId}/chunks/enabled")
    public ChunkService.BatchToggleResult setEnabledBatch(
            @PathVariable long docId, @RequestBody BatchEnabledRequest request) {
        if (request.enabled() == null) {
            throw ApiException.invalidRequest("缺少 enabled 字段。");
        }
        return service.setEnabledBatch(docId, request.chunkIds(), request.enabled());
    }

    @DeleteMapping("/api/v1/chunks/{chunkId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long chunkId) {
        service.delete(chunkId);
    }
}
