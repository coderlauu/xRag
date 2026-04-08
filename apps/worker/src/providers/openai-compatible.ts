import {
  type AnswerProvider,
  type AnswerProviderRequest,
  type AnswerProviderResult,
  type EmbeddingProvider,
  type EmbeddingProviderResult,
  type OpenAICompatibleProviderConfig,
  ProviderConfigurationError,
  ProviderRequestError,
  type ProviderUsage
} from "./contracts";

export { ProviderConfigurationError, ProviderRequestError } from "./contracts";

const DEFAULT_PROVIDER_NAME = "openai-compatible";
const DEFAULT_RETRY_DELAY_MS = 250;

export class OpenAICompatibleHttpClient {
  readonly providerName = DEFAULT_PROVIDER_NAME;
  private readonly baseUrl: string;
  private readonly apiKey?: string;
  private readonly model: string;
  private readonly timeoutMs: number;
  private readonly maxRetries: number;
  private readonly expectedDimensions?: number;
  private readonly fetchImpl: typeof fetch;

  constructor(config: OpenAICompatibleProviderConfig) {
    this.baseUrl = normalizeBaseUrl(config.baseUrl);
    this.apiKey = config.apiKey;
    this.model = normalizeRequiredString(config.model, "model");
    this.timeoutMs = normalizePositiveInteger(config.timeoutMs, "timeoutMs");
    this.maxRetries = normalizeNonNegativeInteger(config.maxRetries, "maxRetries");
    this.expectedDimensions = config.expectedDimensions;
    this.fetchImpl = config.fetchImpl ?? fetch;
  }

  async embed(input: string | readonly string[]): Promise<EmbeddingProviderResult> {
    const inputs = normalizeEmbeddingInputs(input);
    const response = await this.postJson("/embeddings", {
      model: this.model,
      input: inputs
    });

    const parsed = parseEmbeddingResponse(response.body);
    if (parsed.vectors.length === 0) {
      throw new ProviderRequestError("embedding provider returned no vectors", null, false);
    }

    if (this.expectedDimensions && parsed.dimensions !== this.expectedDimensions) {
      throw new ProviderRequestError(
        `embedding vector dimension mismatch: expected ${this.expectedDimensions}, got ${parsed.dimensions}`,
        null,
        false
      );
    }

    return {
      providerName: this.providerName,
      model: parsed.model ?? this.model,
      vectors: parsed.vectors,
      dimensions: parsed.dimensions,
      inputCount: inputs.length,
      usage: parsed.usage,
      attempts: response.attempts,
      latencyMs: response.latencyMs,
      raw: response.body
    };
  }

  async generate(request: AnswerProviderRequest): Promise<AnswerProviderResult> {
    const normalizedMessages = normalizeMessages(request.messages);
    const response = await this.postJson("/chat/completions", {
      model: this.model,
      messages: normalizedMessages,
      temperature: request.temperature,
      top_p: request.topP,
      max_tokens: request.maxOutputTokens,
      response_format:
        request.responseFormat === "json_object"
          ? {
              type: "json_object"
            }
          : undefined
    });

    const parsed = parseChatResponse(response.body);
    return {
      providerName: this.providerName,
      model: parsed.model ?? this.model,
      text: parsed.text,
      finishReason: parsed.finishReason,
      usage: parsed.usage,
      attempts: response.attempts,
      latencyMs: response.latencyMs,
      raw: response.body
    };
  }

  private async postJson(path: string, body: Record<string, unknown>): Promise<{ body: unknown; attempts: number; latencyMs: number }> {
    const url = joinUrl(this.baseUrl, path);
    const maxAttempts = this.maxRetries + 1;
    let lastError: unknown = null;

    for (let attempt = 1; attempt <= maxAttempts; attempt += 1) {
      const controller = new AbortController();
      const startedAt = Date.now();
      const timeout = setTimeout(() => controller.abort(), this.timeoutMs);

      try {
        const response = await this.fetchImpl(url, {
          method: "POST",
          headers: this.buildHeaders(),
          signal: controller.signal,
          body: JSON.stringify(body)
        });

        if (!response.ok) {
          const responseBody = await readResponseText(response);
          const retryable = isRetryableStatus(response.status);
          const error = new ProviderRequestError(
            `provider request failed with status ${response.status}`,
            response.status,
            retryable,
            responseBody
          );

          if (retryable && attempt < maxAttempts) {
            await delay(getRetryDelayMs(response, attempt));
            continue;
          }

          throw error;
        }

        const responseBody = await parseResponseBody(response);
        return {
          body: responseBody,
          attempts: attempt,
          latencyMs: Date.now() - startedAt
        };
      } catch (error) {
        const normalized = normalizeProviderError(error);
        lastError = normalized;
        if (normalized.retryable && attempt < maxAttempts) {
          await delay(DEFAULT_RETRY_DELAY_MS * attempt);
          continue;
        }

        throw normalized;
      } finally {
        clearTimeout(timeout);
      }
    }

    throw normalizeProviderError(lastError);
  }

  private buildHeaders(): Record<string, string> {
    const headers: Record<string, string> = {
      accept: "application/json",
      "content-type": "application/json"
    };

    if (this.apiKey) {
      headers.authorization = `Bearer ${this.apiKey}`;
    }

    return headers;
  }
}

export class OpenAICompatibleEmbeddingProvider implements EmbeddingProvider {
  constructor(private readonly client: OpenAICompatibleHttpClient) {}

  embed(input: string | readonly string[]): Promise<EmbeddingProviderResult> {
    return this.client.embed(input);
  }
}

export class OpenAICompatibleAnswerProvider implements AnswerProvider {
  constructor(private readonly client: OpenAICompatibleHttpClient) {}

  generate(request: AnswerProviderRequest): Promise<AnswerProviderResult> {
    return this.client.generate(request);
  }
}

export function createOpenAICompatibleEmbeddingProvider(
  config: OpenAICompatibleProviderConfig
): EmbeddingProvider {
  return new OpenAICompatibleEmbeddingProvider(new OpenAICompatibleHttpClient(config));
}

export function createOpenAICompatibleAnswerProvider(config: OpenAICompatibleProviderConfig): AnswerProvider {
  return new OpenAICompatibleAnswerProvider(new OpenAICompatibleHttpClient(config));
}

function normalizeBaseUrl(value: string): string {
  const normalized = normalizeRequiredString(value, "baseUrl");
  return normalized.endsWith("/") ? normalized : `${normalized}/`;
}

function normalizeRequiredString(value: string | undefined, fieldName: string): string {
  if (!value) {
    throw new ProviderConfigurationError(`${fieldName} is required`);
  }

  const normalized = value.trim();
  if (!normalized) {
    throw new ProviderConfigurationError(`${fieldName} is required`);
  }

  return normalized;
}

function normalizePositiveInteger(value: number, fieldName: string): number {
  if (!Number.isInteger(value) || value <= 0) {
    throw new ProviderConfigurationError(`${fieldName} must be a positive integer`);
  }

  return value;
}

function normalizeNonNegativeInteger(value: number, fieldName: string): number {
  if (!Number.isInteger(value) || value < 0) {
    throw new ProviderConfigurationError(`${fieldName} must be a non-negative integer`);
  }

  return value;
}

function normalizeEmbeddingInputs(input: string | readonly string[]): string[] {
  const inputs = Array.isArray(input) ? [...input] : [input];
  if (inputs.length === 0) {
    throw new ProviderConfigurationError("embedding input cannot be empty");
  }

  for (const value of inputs) {
    if (typeof value !== "string" || value.trim().length === 0) {
      throw new ProviderConfigurationError("embedding input cannot contain empty text");
    }
  }

  return inputs;
}

function normalizeMessages(messages: AnswerProviderRequest["messages"]) {
  if (!Array.isArray(messages) || messages.length === 0) {
    throw new ProviderConfigurationError("answer messages cannot be empty");
  }

  return messages.map((message) => {
    if (!message || typeof message.content !== "string") {
      throw new ProviderConfigurationError("answer messages must contain string content");
    }

    if (message.content.trim().length === 0) {
      throw new ProviderConfigurationError("answer messages cannot contain empty content");
    }

    return {
      role: message.role,
      content: message.content
    };
  });
}

async function parseResponseBody(response: Response): Promise<unknown> {
  const contentType = response.headers.get("content-type") ?? "";
  const text = await response.text();

  if (!text.trim()) {
    throw new ProviderRequestError("provider returned an empty response body", response.status, false, null);
  }

  if (!contentType.includes("application/json") && !text.trim().startsWith("{") && !text.trim().startsWith("[")) {
    throw new ProviderRequestError("provider returned a non-JSON response body", response.status, false, text);
  }

  try {
    return JSON.parse(text) as unknown;
  } catch {
    throw new ProviderRequestError("provider returned invalid JSON", response.status, false, text);
  }
}

async function readResponseText(response: Response): Promise<string | null> {
  const text = await response.text();
  return text.trim() ? text.trim().slice(0, 2000) : null;
}

function parseEmbeddingResponse(body: unknown): {
  model: string | null;
  vectors: number[][];
  dimensions: number;
  usage: ProviderUsage | null;
} {
  const payload = body as {
    data?: Array<{ index?: number; embedding?: unknown }>;
    model?: unknown;
    usage?: {
      prompt_tokens?: unknown;
      completion_tokens?: unknown;
      total_tokens?: unknown;
    };
  };

  if (!payload || !Array.isArray(payload.data)) {
    throw new ProviderRequestError("embedding provider response is missing data", null, false, JSON.stringify(body));
  }

  const vectors = payload.data
    .map((item, index) => {
      if (!item || !Array.isArray(item.embedding)) {
        throw new ProviderRequestError("embedding provider response contains an invalid embedding", null, false);
      }

      const vector = item.embedding.map((value) => {
        const numeric = Number(value);
        if (!Number.isFinite(numeric)) {
          throw new ProviderRequestError("embedding provider returned a non-numeric vector value", null, false);
        }
        return numeric;
      });
      return {
        index: typeof item.index === "number" ? item.index : index,
        vector
      };
    })
    .sort((left, right) => left.index - right.index)
    .map((item) => item.vector);

  if (vectors.length === 0) {
    throw new ProviderRequestError("embedding provider returned no embeddings", null, false);
  }

  const dimensions = vectors[0]?.length ?? 0;
  for (const vector of vectors) {
    if (vector.length !== dimensions) {
      throw new ProviderRequestError("embedding provider returned inconsistent vector dimensions", null, false);
    }
  }

  return {
    model: typeof payload.model === "string" ? payload.model : null,
    vectors,
    dimensions,
    usage: parseUsage(payload.usage)
  };
}

function parseChatResponse(body: unknown): {
  model: string | null;
  text: string;
  finishReason: string | null;
  usage: ProviderUsage | null;
} {
  const payload = body as {
    choices?: Array<{
      finish_reason?: unknown;
      message?: {
        content?: unknown;
      };
    }>;
    model?: unknown;
    usage?: {
      prompt_tokens?: unknown;
      completion_tokens?: unknown;
      total_tokens?: unknown;
    };
  };

  if (!payload || !Array.isArray(payload.choices) || payload.choices.length === 0) {
    throw new ProviderRequestError("answer provider response is missing choices", null, false, JSON.stringify(body));
  }

  const choice = payload.choices[0];
  const content = choice?.message?.content;
  if (typeof content !== "string" || !content.trim()) {
    throw new ProviderRequestError("answer provider response is missing assistant text", null, false);
  }

  return {
    model: typeof payload.model === "string" ? payload.model : null,
    text: content.trim(),
    finishReason: typeof choice.finish_reason === "string" ? choice.finish_reason : null,
    usage: parseUsage(payload.usage)
  };
}

function parseUsage(value: {
  prompt_tokens?: unknown;
  completion_tokens?: unknown;
  total_tokens?: unknown;
} | null | undefined): ProviderUsage | null {
  if (!value) {
    return null;
  }

  const promptTokens = normalizeOptionalInteger(value.prompt_tokens);
  const completionTokens = normalizeOptionalInteger(value.completion_tokens);
  const totalTokens = normalizeOptionalInteger(value.total_tokens);

  if (promptTokens === null && completionTokens === null && totalTokens === null) {
    return null;
  }

  return {
    promptTokens,
    completionTokens,
    totalTokens
  };
}

function normalizeOptionalInteger(value: unknown): number | null {
  if (typeof value !== "number" || !Number.isFinite(value)) {
    return null;
  }

  return Math.trunc(value);
}

function normalizeProviderError(error: unknown): ProviderRequestError {
  if (error instanceof ProviderRequestError) {
    return error;
  }

  if (error instanceof Error && error.name === "AbortError") {
    return new ProviderRequestError("provider request timed out", null, true);
  }

  if (error instanceof Error) {
    const normalized = error.message.toLowerCase();
    if (
      normalized.includes("econnreset") ||
      normalized.includes("enotfound") ||
      normalized.includes("eai_again") ||
      normalized.includes("etimedout") ||
      normalized.includes("fetch failed") ||
      normalized.includes("socket") ||
      normalized.includes("network")
    ) {
      return new ProviderRequestError(`provider network error: ${error.message}`, null, true);
    }

    return new ProviderRequestError(error.message, null, false);
  }

  return new ProviderRequestError("provider request failed", null, false);
}

function isRetryableStatus(status: number): boolean {
  return status === 408 || status === 409 || status === 425 || status === 429 || status >= 500;
}

function getRetryDelayMs(response: Response, attempt: number): number {
  const retryAfter = response.headers.get("retry-after");
  if (retryAfter) {
    const parsedSeconds = Number.parseInt(retryAfter, 10);
    if (!Number.isNaN(parsedSeconds)) {
      return Math.max(parsedSeconds * 1000, DEFAULT_RETRY_DELAY_MS * attempt);
    }

    const parsedDate = Date.parse(retryAfter);
    if (!Number.isNaN(parsedDate)) {
      return Math.max(parsedDate - Date.now(), DEFAULT_RETRY_DELAY_MS * attempt);
    }
  }

  return DEFAULT_RETRY_DELAY_MS * attempt;
}

function joinUrl(baseUrl: string, path: string): string {
  const normalizedPath = path.startsWith("/") ? path.slice(1) : path;
  return new URL(normalizedPath, baseUrl).toString();
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}
