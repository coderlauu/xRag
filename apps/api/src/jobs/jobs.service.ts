import { Injectable, NotFoundException } from "@nestjs/common";
import type { JobStatusResponse } from "@xrag/shared-types";
import { JobsRepository } from "./jobs.repository";

@Injectable()
export class JobsService {
  constructor(private readonly jobsRepository: JobsRepository) {}

  async getJob(jobId: string): Promise<JobStatusResponse> {
    const job = await this.jobsRepository.getJobById(jobId);
    if (!job) {
      throw new NotFoundException("Job not found");
    }

    return {
      id: job.id,
      document_id: job.documentId,
      job_type: job.jobType,
      status: job.status,
      attempt: job.attempt,
      error_message: job.errorMessage,
      diagnosis_code: (job.diagnosisCode as JobStatusResponse["diagnosis_code"]) ?? null,
      incident_ref: job.incidentRef,
      runtime_ms: job.runtimeMs
    };
  }
}
