import { Body, Controller, Param, Post } from "@nestjs/common";
import type {
  UploadCompleteRequest,
  UploadCompleteResponse,
  UploadInitiateRequest,
  UploadInitiateResponse
} from "@xrag/shared-types";

@Controller("api/v1/uploads")
export class UploadsController {
  @Post("initiate")
  initiateUpload(@Body() body: UploadInitiateRequest): UploadInitiateResponse {
    return {
      upload_id: "upl_123",
      object_key: `uploads/2026/03/31/upl_123/${body.file_name}`,
      upload_method: "presigned_put",
      upload_url: "https://storage.example.com/upload-placeholder",
      headers: {
        "content-type": body.mime_type
      },
      expires_in: 900
    };
  }

  @Post(":uploadId/complete")
  completeUpload(
    @Param("uploadId") uploadId: string,
    @Body() _body: UploadCompleteRequest
  ): UploadCompleteResponse {
    return {
      document_id: `doc_for_${uploadId}`,
      job_id: "job_456",
      parse_status: "pending"
    };
  }
}
