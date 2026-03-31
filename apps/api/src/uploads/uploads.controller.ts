import { Body, Controller, Param, Post } from "@nestjs/common";
import { ApiCreatedResponse, ApiOperation, ApiTags } from "@nestjs/swagger";
import {
  UploadCompleteRequestDto,
  UploadCompleteResponseDto,
  UploadInitiateRequestDto,
  UploadInitiateResponseDto
} from "./uploads.dto";
import { UploadsService } from "./uploads.service";

@ApiTags("uploads")
@Controller("api/v1/uploads")
export class UploadsController {
  constructor(private readonly uploadsService: UploadsService) {}

  @Post("initiate")
  @ApiOperation({ summary: "Create an upload session" })
  @ApiCreatedResponse({ type: UploadInitiateResponseDto })
  initiateUpload(@Body() body: UploadInitiateRequestDto) {
    return this.uploadsService.initiateUpload(body);
  }

  @Post(":uploadId/complete")
  @ApiOperation({ summary: "Complete an upload and create a document" })
  @ApiCreatedResponse({ type: UploadCompleteResponseDto })
  completeUpload(@Param("uploadId") uploadId: string, @Body() body: UploadCompleteRequestDto) {
    return this.uploadsService.completeUpload(uploadId, body);
  }
}
