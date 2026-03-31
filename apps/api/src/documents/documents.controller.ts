import { Body, Controller, Get, NotFoundException, Param, Patch, Post, Query } from "@nestjs/common";
import type {
  CreateTextDocumentRequest,
  DocumentListResponse,
  RetryDocumentResponse,
  UpdateDocumentTagsRequest
} from "@xrag/shared-types";
import { findDocumentById, sampleDocuments } from "../common/sample-data";

@Controller("api/v1/documents")
export class DocumentsController {
  @Post("text")
  createTextDocument(@Body() body: CreateTextDocumentRequest) {
    return {
      id: "doc_new",
      parse_status: "success",
      title: body.title
    };
  }

  @Get()
  listDocuments(
    @Query("q") query = "",
    @Query("page") page = "1",
    @Query("page_size") pageSize = "20"
  ): DocumentListResponse {
    return {
      ...sampleDocuments,
      page: Number(page),
      page_size: Number(pageSize),
      items: sampleDocuments.items.filter((item) => {
        if (!query) {
          return true;
        }

        return [item.title, item.content_preview, item.tags.join(" ")]
          .join(" ")
          .toLowerCase()
          .includes(query.toLowerCase());
      })
    };
  }

  @Get(":documentId")
  getDocument(@Param("documentId") documentId: string) {
    const document = findDocumentById(documentId);
    if (!document) {
      throw new NotFoundException("Document not found");
    }

    return document;
  }

  @Patch(":documentId/tags")
  updateDocumentTags(
    @Param("documentId") documentId: string,
    @Body() body: UpdateDocumentTagsRequest
  ) {
    const document = findDocumentById(documentId);
    if (!document) {
      throw new NotFoundException("Document not found");
    }

    return {
      ...document,
      tags: body.tags
    };
  }

  @Post(":documentId/retry")
  retryDocument(@Param("documentId") documentId: string): RetryDocumentResponse {
    const document = findDocumentById(documentId);
    if (!document) {
      throw new NotFoundException("Document not found");
    }

    return {
      document_id: documentId,
      job_id: "job_retry_001",
      parse_status: "pending"
    };
  }
}
