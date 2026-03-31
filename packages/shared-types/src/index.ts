export type ParseStatus = "pending" | "processing" | "success" | "failed";

export interface HealthResponse {
  status: string;
}
