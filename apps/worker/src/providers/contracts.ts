export type ProviderRole = "system" | "user" | "assistant";

export interface ProviderMessage {
  role: ProviderRole;
  content: string;
}

export interface ProviderUsage {
  promptTokens: number | null;
  completionTokens: number | null;
  totalTokens: number | null;
}

export interface OpenAICompatibleProviderConfig {
  baseUrl: string;
  apiKey?: string;
  model: string;
  timeoutMs: number;
  maxRetries: number;
  expectedDimensions?: number;
  fetchImpl?: typeof fetch;
}

export interface EmbeddingProviderResult {
  providerName: string;
  model: string;
  vectors: number[][];
  dimensions: number;
  inputCount: number;
  usage: ProviderUsage | null;
  attempts: number;
  latencyMs: number;
  raw: unknown;
}

export interface EmbeddingProvider {
  embed(input: string | readonly string[]): Promise<EmbeddingProviderResult>;
}

export interface AnswerProviderRequest {
  messages: ProviderMessage[];
  temperature?: number;
  topP?: number;
  maxOutputTokens?: number;
  responseFormat?: "text" | "json_object";
}

export interface AnswerProviderResult {
  providerName: string;
  model: string;
  text: string;
  finishReason: string | null;
  usage: ProviderUsage | null;
  attempts: number;
  latencyMs: number;
  raw: unknown;
}

export interface AnswerProvider {
  generate(request: AnswerProviderRequest): Promise<AnswerProviderResult>;
}

export class ProviderConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "ProviderConfigurationError";
  }
}

export class ProviderRequestError extends Error {
  constructor(
    message: string,
    readonly statusCode: number | null,
    readonly retryable: boolean,
    readonly responseBody: string | null = null
  ) {
    super(message);
    this.name = "ProviderRequestError";
  }
}
