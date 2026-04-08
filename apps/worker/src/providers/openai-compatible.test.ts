import assert from "node:assert/strict";
import test from "node:test";
import {
  createOpenAICompatibleAnswerProvider,
  createOpenAICompatibleEmbeddingProvider,
  OpenAICompatibleHttpClient,
  ProviderConfigurationError,
  ProviderRequestError
} from "./openai-compatible";

test("embedding provider posts batch inputs and parses vectors", async () => {
  const requests: Array<{ url: string; init: RequestInit | undefined }> = [];
  const provider = createOpenAICompatibleEmbeddingProvider({
    baseUrl: "https://provider.example/v1",
    apiKey: "secret",
    model: "text-embedding-3-small",
    timeoutMs: 1000,
    maxRetries: 0,
    expectedDimensions: 3,
    fetchImpl: async (url, init) => {
      requests.push({ url: String(url), init });
      return new Response(
        JSON.stringify({
          model: "text-embedding-3-small",
          data: [
            { index: 1, embedding: [4, 5, 6] },
            { index: 0, embedding: [1, 2, 3] }
          ],
          usage: {
            prompt_tokens: 8,
            total_tokens: 8
          }
        }),
        {
          status: 200,
          headers: {
            "content-type": "application/json"
          }
        }
      );
    }
  });

  const result = await provider.embed(["first chunk", "second chunk"]);

  assert.equal(requests.length, 1);
  assert.equal(requests[0]?.url, "https://provider.example/v1/embeddings");
  assert.equal(
    requests[0]?.init?.headers && (requests[0]?.init.headers as Record<string, string>).authorization,
    "Bearer secret"
  );
  assert.equal(result.providerName, "openai-compatible");
  assert.equal(result.model, "text-embedding-3-small");
  assert.equal(result.inputCount, 2);
  assert.equal(result.dimensions, 3);
  assert.deepEqual(result.vectors, [
    [1, 2, 3],
    [4, 5, 6]
  ]);
  assert.equal(result.usage?.promptTokens, 8);
});

test("answer provider retries retryable failures and returns assistant text", async () => {
  let attempts = 0;
  const provider = createOpenAICompatibleAnswerProvider({
    baseUrl: "https://provider.example/v1/",
    model: "gpt-4.1-mini",
    timeoutMs: 1000,
    maxRetries: 1,
    fetchImpl: async () => {
      attempts += 1;
      if (attempts === 1) {
        return new Response("upstream unavailable", {
          status: 503,
          headers: {
            "content-type": "text/plain"
          }
        });
      }

      return new Response(
        JSON.stringify({
          model: "gpt-4.1-mini",
          choices: [
            {
              finish_reason: "stop",
              message: {
                content: "Grounded answer."
              }
            }
          ],
          usage: {
            prompt_tokens: 12,
            completion_tokens: 4,
            total_tokens: 16
          }
        }),
        {
          status: 200,
          headers: {
            "content-type": "application/json"
          }
        }
      );
    }
  });

  const result = await provider.generate({
    messages: [
      {
        role: "system",
        content: "Answer strictly from evidence."
      },
      {
        role: "user",
        content: "What changed?"
      }
    ],
    temperature: 0.1,
    maxOutputTokens: 256,
    responseFormat: "json_object"
  });

  assert.equal(attempts, 2);
  assert.equal(result.model, "gpt-4.1-mini");
  assert.equal(result.text, "Grounded answer.");
  assert.equal(result.finishReason, "stop");
  assert.equal(result.usage?.promptTokens, 12);
  assert.equal(result.usage?.completionTokens, 4);
  assert.equal(result.usage?.totalTokens, 16);
});

test("provider configuration rejects empty base url and model", () => {
  assert.throws(
    () =>
      new OpenAICompatibleHttpClient({
        baseUrl: " ",
        model: "text-embedding-3-small",
        timeoutMs: 1000,
        maxRetries: 0
      }),
    ProviderConfigurationError
  );

  assert.throws(
    () =>
      new OpenAICompatibleHttpClient({
        baseUrl: "https://provider.example/v1",
        model: " ",
        timeoutMs: 1000,
        maxRetries: 0
      }),
    ProviderConfigurationError
  );
});

test("embedding provider surfaces inconsistent vector dimensions", async () => {
  const provider = createOpenAICompatibleEmbeddingProvider({
    baseUrl: "https://provider.example/v1",
    model: "text-embedding-3-small",
    timeoutMs: 1000,
    maxRetries: 0,
    fetchImpl: async () =>
      new Response(
        JSON.stringify({
          data: [{ index: 0, embedding: [1, 2] }, { index: 1, embedding: [1, 2, 3] }]
        }),
        {
          status: 200,
          headers: {
            "content-type": "application/json"
          }
        }
      )
  });

  await assert.rejects(
    () => provider.embed(["a", "b"]),
    (error: unknown): error is ProviderRequestError =>
      error instanceof ProviderRequestError && error.message.includes("inconsistent vector dimensions")
  );
});
