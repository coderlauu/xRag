package com.app.knowledge.web;

import com.app.knowledge.model.ChunkStrategy;
import com.app.knowledge.model.DocumentDetail;
import com.app.knowledge.model.DocumentStatus;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
}
