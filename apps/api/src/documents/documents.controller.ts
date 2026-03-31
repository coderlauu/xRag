import { Body, Controller, Get, Param, Patch, Post, Query } from "@nestjs/common";
import { ApiCreatedResponse, ApiOkResponse, ApiOperation, ApiTags } from "@nestjs/swagger";
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
  createTextDocument(@Body() body: CreateTextDocumentRequestDto) {
    return this.documentsService.createTextDocument(body);
  }

  @Get()
  @ApiOperation({ summary: "List documents with search and filters" })
  @ApiOkResponse({ type: DocumentListResponseDto })
  listDocuments(@Query() query: ListDocumentsQueryDto) {
    return this.documentsService.listDocuments(query);
  }

  @Get(":documentId")
  @ApiOperation({ summary: "Get a document by id" })
  @ApiOkResponse({ type: DocumentDetailDto })
  getDocument(@Param("documentId") documentId: string) {
    return this.documentsService.getDocument(documentId);
  }

  @Patch(":documentId/tags")
  @ApiOperation({ summary: "Replace a document tag set" })
  @ApiOkResponse({ type: DocumentDetailDto })
  updateDocumentTags(@Param("documentId") documentId: string, @Body() body: UpdateDocumentTagsRequestDto) {
    return this.documentsService.updateDocumentTags(documentId, body);
  }

  @Post(":documentId/retry")
  @ApiOperation({ summary: "Retry document parsing" })
  @ApiCreatedResponse({ type: RetryDocumentResponseDto })
  retryDocument(@Param("documentId") documentId: string) {
    return this.documentsService.retryDocument(documentId);
  }
}
