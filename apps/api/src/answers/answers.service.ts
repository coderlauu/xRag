import { BadRequestException, Injectable, NotFoundException } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import type {
  AnswerRetrievalTraceResponse,
  AnswerScope,
  AnswerSessionResponse,
  CreateAnswerResponse
} from "@xrag/shared-types";
import { normalizeWhitespace } from "../common/document-utils";
import { QueueService } from "../queue/queue.service";
import { CreateAnswerRequestDto } from "./answers.dto";
import { AnswersRepository } from "./answers.repository";

@Injectable()
export class AnswersService {
  constructor(
    private readonly answersRepository: AnswersRepository,
    private readonly queueService: QueueService
  ) {}

  async createAnswer(body: CreateAnswerRequestDto): Promise<CreateAnswerResponse> {
    const question = normalizeWhitespace(body.question);
    const scope = this.normalizeScope(body.scope);

    const session = await this.answersRepository.createAnswerSession({
      id: randomUUID(),
      queueJobId: null,
      question,
      scopeMode: scope.mode,
      scopePayload: scope.payload,
      retrievalMode: "hybrid",
      status: "idle"
    });

    try {
      const queueJobId = await this.queueService.enqueueAnswerSession(session.id);
      await this.answersRepository.updateAnswerSession(session.id, {
        queueJobId,
        status: "retrieving"
      });

      return {
        session_id: session.id,
        status: "retrieving"
      };
    } catch (error) {
      await this.answersRepository.updateAnswerSession(session.id, {
        status: "failed",
        diagnosisCode: "queue_backlog",
        refusalReason: null
      });
      throw new BadRequestException(error instanceof Error ? error.message : "Failed to enqueue answer session");
    }
  }

  async getAnswer(sessionId: string): Promise<AnswerSessionResponse> {
    const session = await this.answersRepository.getAnswerSessionById(sessionId);
    if (!session) {
      throw new NotFoundException("Answer session not found");
    }

    const citations = await this.answersRepository.listCitationsBySessionId(sessionId);
    return {
      session_id: session.id,
      question: session.question,
      scope: {
        mode: session.scopeMode,
        payload: session.scopePayload ?? null
      },
      status: session.status,
      answer_summary: session.answerSummary,
      refusal_reason: session.refusalReason,
      diagnosis_code: (session.diagnosisCode as AnswerSessionResponse["diagnosis_code"]) ?? null,
      retrieval_mode: session.retrievalMode,
      citations: citations.map((citation) => ({
        document_id: citation.documentId,
        chunk_id: citation.chunkId,
        quote_text: citation.quoteText,
        locator: citation.locator ?? null
      })),
      latency_ms: session.latencyMs,
      total_cost_usd: session.totalCostUsd ? String(session.totalCostUsd) : null
    };
  }

  async getAnswerRetrieval(sessionId: string): Promise<AnswerRetrievalTraceResponse> {
    const session = await this.answersRepository.getAnswerSessionById(sessionId);
    if (!session) {
      throw new NotFoundException("Answer session not found");
    }

    const retrievalRun = await this.answersRepository.getLatestRetrievalRunBySessionId(sessionId);
    if (!retrievalRun) {
      return {
        session_id: sessionId,
        items: []
      };
    }

    const items = await this.answersRepository.listRetrievalHitsByRunId(retrievalRun.id);
    return {
      session_id: sessionId,
      items: items.map((item) => ({
        document_id: item.documentId,
        chunk_id: item.chunkId,
        rank: item.rank,
        lexical_score: item.lexicalScore === null ? null : Number(item.lexicalScore),
        semantic_score: item.semanticScore === null ? null : Number(item.semanticScore),
        final_score: item.finalScore === null ? null : Number(item.finalScore),
        used_in_answer: item.usedInAnswer,
        exclusion_reason: item.exclusionReason
      }))
    };
  }

  private normalizeScope(scope: AnswerScope): AnswerScope {
    if (scope.mode === "global") {
      return {
        mode: "global",
        payload: scope.payload ?? null
      };
    }

    if (scope.mode === "document") {
      const documentId = scope.payload?.document_id;
      if (typeof documentId !== "string" || documentId.trim().length === 0) {
        throw new BadRequestException("document scope requires payload.document_id");
      }

      return {
        mode: "document",
        payload: {
          document_id: documentId.trim()
        }
      };
    }

    const documentIds = scope.payload?.document_ids;
    const truncated = scope.payload?.truncated;

    if (!Array.isArray(documentIds) || documentIds.some((value) => typeof value !== "string")) {
      throw new BadRequestException("search_result scope requires payload.document_ids");
    }

    if (documentIds.length === 0 || documentIds.length > 100) {
      throw new BadRequestException("search_result scope requires 1-100 document_ids");
    }

    if (typeof truncated !== "boolean") {
      throw new BadRequestException("search_result scope requires payload.truncated");
    }

    return {
      mode: "search_result",
      payload: {
        ...scope.payload,
        document_ids: documentIds.map((value) => value.trim()).filter(Boolean).slice(0, 100),
        truncated
      }
    };
  }
}
