import { Controller, Get, NotFoundException, Param } from "@nestjs/common";
import { sampleJob } from "../common/sample-data";

@Controller("api/v1/jobs")
export class JobsController {
  @Get(":jobId")
  getJob(@Param("jobId") jobId: string) {
    if (jobId !== sampleJob.id) {
      throw new NotFoundException("Job not found");
    }

    return sampleJob;
  }
}
