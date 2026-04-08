import { Body, Controller, Get, HttpCode, Param, Patch, Post, Query } from "@nestjs/common";
import {
  ApiAcceptedResponse,
  ApiBody,
  ApiCreatedResponse,
  ApiOkResponse,
  ApiOperation,
  ApiParam,
  ApiQuery,
  ApiTags
} from "@nestjs/swagger";
import {
  CreateLinkDocumentRequestDto,
  CreateLinkDocumentResponseDto,
  CreateTextDocumentRequestDto,
  CreateTextDocumentResponseDto,
  DocumentDetailDto,
  DocumentEvidenceResponseDto,
  DocumentListResponseDto,
  DocumentTimelineResponseDto,
  ListDocumentsQueryDto,
  ReindexDocumentResponseDto,
  RetryDocumentResponseDto,
  UpdateDocumentTagsRequestDto
} from "./documents.dto";
import { DocumentsService } from "./documents.service";

@ApiTags("documents")
@Controller("api/v1/documents")
export class DocumentsController {
  constructor(private readonly documentsService: DocumentsService) {}

  @Post("text")
  @ApiOperation({ summary: "Create a text document" })
  @ApiCreatedResponse({ type: CreateTextDocumentResponseDto })
  @ApiBody({ type: CreateTextDocumentRequestDto })
  createTextDocument(@Body() body: CreateTextDocumentRequestDto) {
    return this.documentsService.createTextDocument(body);
  }

  @Post("link")
  @ApiOperation({ summary: "Create a link document and enqueue fetch job" })
  @ApiCreatedResponse({ type: CreateLinkDocumentResponseDto })
  @ApiBody({ type: CreateLinkDocumentRequestDto })
  createLinkDocument(@Body() body: CreateLinkDocumentRequestDto) {
    return this.documentsService.createLinkDocument(body);
  }

  @Get()
  @ApiOperation({ summary: "List documents with search and filters" })
  @ApiOkResponse({ type: DocumentListResponseDto })
  @ApiQuery({ name: "q", required: false, type: String })
  @ApiQuery({ name: "source_type", required: false, enum: ["text", "file", "pdf", "link"] })
  @ApiQuery({ name: "ocr_status", required: false, type: String })
  @ApiQuery({ name: "parse_status", required: false, type: String })
  @ApiQuery({ name: "index_status", required: false, enum: ["not_indexed", "queued", "chunking", "embedding", "ready", "failed", "stale"] })
  @ApiQuery({ name: "upload_status", required: false, type: String })
  @ApiQuery({ name: "diagnosis_code", required: false, type: String })
  @ApiQuery({ name: "tags", required: false, type: String })
  @ApiQuery({ name: "date_from", required: false, type: String })
  @ApiQuery({ name: "date_to", required: false, type: String })
  @ApiQuery({ name: "page", required: false, type: Number })
  @ApiQuery({ name: "page_size", required: false, type: Number })
  listDocuments(@Query() query: ListDocumentsQueryDto) {
    return this.documentsService.listDocuments(query);
  }

  @Get(":documentId")
  @ApiOperation({ summary: "Get a document by id" })
  @ApiOkResponse({ type: DocumentDetailDto })
  @ApiParam({ name: "documentId", type: String })
  getDocument(@Param("documentId") documentId: string) {
    return this.documentsService.getDocument(documentId);
  }

  @Get(":documentId/evidence")
  @ApiOperation({ summary: "Get document evidence chunks and citation locators" })
  @ApiOkResponse({ type: DocumentEvidenceResponseDto })
  @ApiParam({ name: "documentId", type: String })
  getDocumentEvidence(@Param("documentId") documentId: string) {
    return this.documentsService.getDocumentEvidence(documentId);
  }

  @Get(":documentId/timeline")
  @ApiOperation({ summary: "Get a document processing timeline" })
  @ApiOkResponse({ type: DocumentTimelineResponseDto })
  @ApiParam({ name: "documentId", type: String })
  getDocumentTimeline(@Param("documentId") documentId: string) {
    return this.documentsService.getDocumentTimeline(documentId);
  }

  @Patch(":documentId/tags")
  @ApiOperation({ summary: "Replace a document tag set" })
  @ApiOkResponse({ type: DocumentDetailDto })
  @ApiParam({ name: "documentId", type: String })
  @ApiBody({ type: UpdateDocumentTagsRequestDto })
  updateDocumentTags(@Param("documentId") documentId: string, @Body() body: UpdateDocumentTagsRequestDto) {
    return this.documentsService.updateDocumentTags(documentId, body);
  }

  @Post(":documentId/retry")
  @ApiOperation({ summary: "Retry document parsing" })
  @ApiCreatedResponse({ type: RetryDocumentResponseDto })
  @ApiParam({ name: "documentId", type: String })
  retryDocument(@Param("documentId") documentId: string) {
    return this.documentsService.retryDocument(documentId);
  }

  @Post(":documentId/reindex")
  @HttpCode(202)
  @ApiOperation({ summary: "Reindex document chunks and embeddings" })
  @ApiAcceptedResponse({ type: ReindexDocumentResponseDto })
  @ApiParam({ name: "documentId", type: String })
  reindexDocument(@Param("documentId") documentId: string) {
    return this.documentsService.reindexDocument(documentId);
  }
}
