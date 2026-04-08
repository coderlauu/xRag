import type { Pool } from "pg";
import type { Logger } from "../logging/logger";
import type { AnswerProvider, EmbeddingProvider } from "../providers";
import { AnswerRepository } from "../database/answer-repository";
import { createAnswerOrchestrationHandlers } from "./answer-orchestration";

export interface AnswerRuntimeDependencies {
  pool: Pool;
  logger: Logger;
  getEmbeddingProvider: () => EmbeddingProvider;
  getAnswerProvider: () => AnswerProvider;
}

export function createAnswerOrchestrationRuntime(deps: AnswerRuntimeDependencies) {
  const repository = new AnswerRepository(deps.pool);

  return {
    repository,
    handlers: createAnswerOrchestrationHandlers({
      repository,
      getEmbeddingProvider: deps.getEmbeddingProvider,
      getAnswerProvider: deps.getAnswerProvider,
      logger: deps.logger
    })
  };
}
