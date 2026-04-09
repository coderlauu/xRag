import { createServer } from "node:http";
import { setTimeout as delay } from "node:timers/promises";

const PORT = Number.parseInt(process.env.XRAG_E2E_PROVIDER_PORT || "4010", 10);
const HOST = process.env.XRAG_E2E_PROVIDER_HOST || "127.0.0.1";
const VECTOR_DIMENSIONS = 1536;

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "content-type": "application/json"
    }
  });
}

function hashText(value: string): number {
  let hash = 2166136261;

  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }

  return hash >>> 0;
}

function buildVector(seedText: string): number[] {
  const hash = hashText(seedText);
  const vector = Array.from({ length: VECTOR_DIMENSIONS }, (_, index) => {
    const value = ((hash + index * 31) % 101) / 100;
    return Number(value.toFixed(4));
  });

  if (vector.every((value) => value === 0)) {
    vector[0] = 1;
  }

  return vector;
}

async function readRequestBody(req: import("node:http").IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];

  for await (const chunk of req) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }

  return Buffer.concat(chunks).toString("utf8");
}

async function main() {
  const server = createServer(async (req, res) => {
    if (!req.url || !req.method) {
      res.writeHead(400, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "missing request metadata" }));
      return;
    }

    if (req.method === "GET" && req.url === "/health") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ status: "ok" }));
      return;
    }

    if (req.method !== "POST") {
      res.writeHead(404, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "not found" }));
      return;
    }

    const bodyText = await readRequestBody(req);

    try {
      const payload = JSON.parse(bodyText) as Record<string, unknown>;

      if (req.url === "/v1/embeddings") {
        const input = Array.isArray(payload.input) ? payload.input : [payload.input];
        const data = input.map((value, index) => ({
          index,
          embedding: buildVector(typeof value === "string" ? value : JSON.stringify(value))
        }));

        res.writeHead(200, { "content-type": "application/json" });
        res.end(
          JSON.stringify({
            model: typeof payload.model === "string" ? payload.model : "mock-embedding-model",
            data,
            usage: {
              prompt_tokens: Math.max(1, data.length * 8),
              total_tokens: Math.max(1, data.length * 8)
            }
          })
        );
        return;
      }

      if (req.url === "/v1/chat/completions") {
        const messages = Array.isArray(payload.messages) ? payload.messages : [];
        const userMessage = [...messages].reverse().find((message) => {
          return message && typeof message === "object" && (message as Record<string, unknown>).role === "user";
        }) as Record<string, unknown> | undefined;

        let question = "unknown question";
        let evidencePack: Array<Record<string, unknown>> = [];

        if (userMessage && typeof userMessage.content === "string") {
          try {
            const parsed = JSON.parse(userMessage.content) as { question?: string; evidence_pack?: Array<Record<string, unknown>> };
            question = typeof parsed.question === "string" ? parsed.question : question;
            evidencePack = Array.isArray(parsed.evidence_pack) ? parsed.evidence_pack : [];
          } catch {
            // Leave defaults in place.
          }
        }

        if (evidencePack.length === 0) {
          res.writeHead(200, { "content-type": "application/json" });
          res.end(
            JSON.stringify({
              model: typeof payload.model === "string" ? payload.model : "mock-answer-model",
              choices: [
                {
                  index: 0,
                  finish_reason: "stop",
                  message: {
                    role: "assistant",
                    content: JSON.stringify({
                      decision: "refused",
                      refusal_reason: "no evidence pack"
                    })
                  }
                }
              ],
              usage: {
                prompt_tokens: 16,
                completion_tokens: 8,
                total_tokens: 24
              }
            })
          );
          return;
        }

        const supportingChunkIds = evidencePack
          .map((item) => item.chunk_id)
          .filter((value): value is string => typeof value === "string" && value.length > 0)
          .slice(0, 2);

        const firstEvidence = evidencePack[0] || {};
        const answerSummary = [
          `Question: ${question}`,
          `Chunk: ${typeof firstEvidence.chunk_id === "string" ? firstEvidence.chunk_id : "unknown"}`,
          `Title: ${typeof firstEvidence.document_title === "string" ? firstEvidence.document_title : "unknown"}`
        ].join(" | ");

        res.writeHead(200, { "content-type": "application/json" });
        res.end(
          JSON.stringify({
            model: typeof payload.model === "string" ? payload.model : "mock-answer-model",
            choices: [
              {
                index: 0,
                finish_reason: "stop",
                message: {
                  role: "assistant",
                  content: JSON.stringify({
                    decision: "answered",
                    answer_summary: answerSummary,
                    supporting_chunk_ids: supportingChunkIds
                  })
                }
              }
            ],
            usage: {
              prompt_tokens: 24,
              completion_tokens: 16,
              total_tokens: 40
            }
          })
        );
        return;
      }

      res.writeHead(404, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: "unsupported route" }));
    } catch (error) {
      res.writeHead(400, { "content-type": "application/json" });
      res.end(
        JSON.stringify({
          error: error instanceof Error ? error.message : "invalid json"
        })
      );
    }
  });

  server.listen(PORT, HOST, () => {
    process.stdout.write(`mock provider listening on http://${HOST}:${PORT}\n`);
  });

  process.on("SIGTERM", async () => {
    server.close();
    await delay(50);
    process.exit(0);
  });
}

void main();
