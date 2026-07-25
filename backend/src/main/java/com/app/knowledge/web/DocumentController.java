package com.app.knowledge.web;

import com.app.knowledge.model.ChunkStrategy;
import com.app.knowledge.model.DocumentDetail;
import com.app.knowledge.model.DocumentStatus;
import com.app.knowledge.model.DocumentUpdateResult;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** api.md §3 的文档上传与列表。 */
@RestController
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/knowledge-bases/{kbId}/documents/file")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceDocument uploadFile(
            @PathVariable long kbId,
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) ChunkStrategy chunkStrategy,
            @RequestParam(required = false) Integer chunkSize,
            @RequestParam(required = false) Integer overlap) {
        return service.uploadFile(kbId, file, name, chunkStrategy, chunkSize, overlap);
    }

    public record AddUrlRequest(
            String sourceUri, String name, ChunkStrategy chunkStrategy, Integer chunkSize,
            Integer overlap, Boolean syncEnabled, String syncCron) {}

    @PostMapping("/api/v1/knowledge-bases/{kbId}/documents/url")
    @ResponseStatus(HttpStatus.CREATED)
    public SourceDocument addUrl(@PathVariable long kbId, @RequestBody AddUrlRequest request) {
        return service.addUrl(kbId, request.sourceUri(), request.name(), request.chunkStrategy(),
                request.chunkSize(), request.overlap(), request.syncEnabled(), request.syncCron());
    }

    @GetMapping("/api/v1/knowledge-bases/{kbId}/documents")
    public PageResponse<SourceDocument> list(
            @PathVariable long kbId,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return service.list(kbId, status, enabled, page, size);
    }

    @GetMapping("/api/v1/documents/{docId}")
    public DocumentDetail get(@PathVariable long docId) {
        return service.get(docId);
    }

    /** 全字段可选，只更新传了的字段（api.md §3）。 */
    public record UpdateDocumentRequest(
            String name, ChunkStrategy chunkStrategy, Integer chunkSize, Integer overlap,
            String sourceUri, Boolean syncEnabled, String syncCron) {}

    public record SetEnabledRequest(Boolean enabled) {}

    @PutMapping("/api/v1/documents/{docId}")
    public DocumentUpdateResult update(@PathVariable long docId,
            @RequestBody UpdateDocumentRequest request) {
        return service.update(docId, request.name(), request.chunkStrategy(), request.chunkSize(),
                request.overlap(), request.sourceUri(), request.syncEnabled(), request.syncCron());
    }

    @PatchMapping("/api/v1/documents/{docId}/enabled")
    public SourceDocument setEnabled(@PathVariable long docId, @RequestBody SetEnabledRequest request) {
        if (request.enabled() == null) {
            throw ApiException.invalidRequest("缺少 enabled 字段。");
        }
        return service.setEnabled(docId, request.enabled());
    }

    @DeleteMapping("/api/v1/documents/{docId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long docId) {
        service.delete(docId);
    }
}
