import type { HealthResponse } from "@xrag/shared-types";

export async function fetchHealth(baseUrl = "http://localhost:3001"): Promise<HealthResponse> {
  const response = await fetch(`${baseUrl}/api/v1/health`);
  if (!response.ok) {
    throw new Error(`Health request failed: ${response.status}`);
  }

  return response.json() as Promise<HealthResponse>;
}
