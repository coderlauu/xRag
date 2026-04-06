import { Body, Controller, Param, ParseIntPipe, Post } from "@nestjs/common";
import { ApiBody, ApiCreatedResponse, ApiOperation, ApiParam, ApiTags } from "@nestjs/swagger";
import {
  UploadCompleteRequestDto,
  UploadCompleteResponseDto,
  UploadInitiateRequestDto,
  UploadInitiateResponseDto,
  UploadPartCompleteRequestDto,
  UploadPartCompleteResponseDto,
  UploadPartUrlRequestDto,
  UploadPartUrlResponseDto
} from "./uploads.dto";
import { UploadsService } from "./uploads.service";

@ApiTags("uploads")
@Controller("api/v1/uploads")
export class UploadsController {
  constructor(private readonly uploadsService: UploadsService) {}

  @Post("initiate")
  @ApiOperation({ summary: "Create an upload session" })
  @ApiCreatedResponse({ type: UploadInitiateResponseDto })
  @ApiBody({ type: UploadInitiateRequestDto })
  initiateUpload(@Body() body: UploadInitiateRequestDto) {
    return this.uploadsService.initiateUpload(body);
  }

  @Post(":uploadId/parts")
  @ApiOperation({ summary: "Get multipart upload URLs" })
  @ApiCreatedResponse({ type: UploadPartUrlResponseDto })
  @ApiParam({ name: "uploadId", type: String })
  @ApiBody({ type: UploadPartUrlRequestDto })
  getUploadParts(@Param("uploadId") uploadId: string, @Body() body: UploadPartUrlRequestDto) {
    return this.uploadsService.getUploadParts(uploadId, body);
  }

  @Post(":uploadId/parts/:partNumber/complete")
  @ApiOperation({ summary: "Record multipart part completion" })
  @ApiCreatedResponse({ type: UploadPartCompleteResponseDto })
  @ApiParam({ name: "uploadId", type: String })
  @ApiParam({ name: "partNumber", type: Number })
  @ApiBody({ type: UploadPartCompleteRequestDto })
  completeUploadPart(
    @Param("uploadId") uploadId: string,
    @Param("partNumber", ParseIntPipe) partNumber: number,
    @Body() body: UploadPartCompleteRequestDto
  ) {
    return this.uploadsService.completeUploadPart(uploadId, partNumber, body);
  }

  @Post(":uploadId/complete")
  @ApiOperation({ summary: "Complete an upload and create a document" })
  @ApiCreatedResponse({ type: UploadCompleteResponseDto })
  @ApiParam({ name: "uploadId", type: String })
  @ApiBody({ type: UploadCompleteRequestDto })
  completeUpload(@Param("uploadId") uploadId: string, @Body() body: UploadCompleteRequestDto) {
    return this.uploadsService.completeUpload(uploadId, body);
  }
}
