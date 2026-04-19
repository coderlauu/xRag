import { Controller, Get, Param, ParseUUIDPipe } from "@nestjs/common";
import { ApiOkResponse, ApiOperation, ApiParam, ApiTags } from "@nestjs/swagger";
import { JobStatusResponseDto } from "./jobs.dto";
import { JobsService } from "./jobs.service";

@ApiTags("jobs")
@Controller("api/v1/jobs")
export class JobsController {
  constructor(private readonly jobsService: JobsService) {}

  @Get(":jobId")
  @ApiOperation({ summary: "Get background job status" })
  @ApiOkResponse({ type: JobStatusResponseDto })
  @ApiParam({ name: "jobId", type: String })
  getJob(@Param("jobId", ParseUUIDPipe) jobId: string) {
    return this.jobsService.getJob(jobId);
  }
}
