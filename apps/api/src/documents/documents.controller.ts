import { Body, Controller, Get, Param, Patch, Post, Query } from "@nestjs/common";
import { ApiBody, ApiCreatedResponse, ApiOkResponse, ApiOperation, ApiParam, ApiQuery, ApiTags } from "@nestjs/swagger";
import {
  CreateTextDocumentRequestDto,
  CreateTextDocumentResponseDto,
  DocumentDetailDto,
  DocumentListResponseDto,
  ListDocumentsQueryDto,
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

  @Get()
  @ApiOperation({ summary: "List documents with search and filters" })
  @ApiOkResponse({ type: DocumentListResponseDto })
  @ApiQuery({ name: "q", required: false, type: String })
  @ApiQuery({ name: "source_type", required: false, enum: ["text", "file", "link"] })
  @ApiQuery({ name: "parse_status", required: false, type: String })
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
}
