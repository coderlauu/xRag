import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import type { AnswerScopeMode, CreateAnswerRequest } from "@xrag/shared-types";
import { Badge, Button, Input, PageShell, SectionCard, Select, StatCard, Textarea } from "@xrag/ui";
import { createAnswer, getAnswer, getAnswerRetrieval } from "../../../lib/api";
import {
  ANSWER_SCOPE_MODE_OPTIONS,
  answerScopeModeLabel,
  answerSessionStatusLabel,
  answerSessionStatusTone,
  formatLatencyMs,
  formatUsd,
  isAnswerSessionActive,
  isAnswerSessionTerminal,
  parseDocumentIdList,
  retrievalModeLabel,
  summarizeAnswerScope,
  summarizeScopePayload
} from "../../../lib/answer-state";
import { clearAnswerSessionId, loadAnswerSessionId, rememberAnswerSessionId } from "../../../lib/answer-session-store";

type ScopeDraft = {
  mode: AnswerScopeMode;
  documentId: string;
  documentIdsText: string;
};

const MAX_SCOPE_DOCUMENT_IDS = 100;

export function AskPage() {
  const [question, setQuestion] = useState("");
  const [scopeDraft, setScopeDraft] = useState<ScopeDraft>({
    mode: "global",
    documentId: "",
    documentIdsText: ""
  });
  const [activeSessionId, setActiveSessionId] = useState<string | null>(() => loadAnswerSessionId());
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitMessage, setSubmitMessage] = useState<string | null>(null);

  useEffect(() => {
    if (activeSessionId) {
      rememberAnswerSessionId(activeSessionId);
      return;
    }

    clearAnswerSessionId();
  }, [activeSessionId]);

  const answerQuery = useQuery({
    queryKey: ["answers", activeSessionId],
    queryFn: () => getAnswer(activeSessionId || ""),
    enabled: Boolean(activeSessionId),
    refetchInterval: (query) => (isAnswerSessionActive(query.state.data?.status) ? 2500 : false)
  });

  const retrievalQuery = useQuery({
    queryKey: ["answers", activeSessionId, "retrieval"],
    queryFn: () => getAnswerRetrieval(activeSessionId || ""),
    enabled: Boolean(activeSessionId),
    refetchInterval: (query) => (isAnswerSessionActive(answerQuery.data?.status) ? 2500 : false)
  });

  const createAnswerMutation = useMutation({
    mutationFn: (body: CreateAnswerRequest) => createAnswer(body),
    onSuccess: async (result) => {
      setSubmitError(null);
      setSubmitMessage(`会话 ${result.session_id} 已创建，正在进入 ${answerSessionStatusLabel(result.status)}。`);
      setActiveSessionId(result.session_id);
    },
    onError: (error) => {
      setSubmitMessage(null);
      setSubmitError(error instanceof Error ? error.message : "创建问答会话失败");
    }
  });

  const answer = answerQuery.data;
  const retrieval = retrievalQuery.data;
  const sessionStatus = answer?.status ?? "idle";
  const citations = answer?.citations ?? [];
  const retrievalItems = retrieval?.items ?? [];
  const latencyLabel = formatLatencyMs(answer?.latency_ms);
  const citationCount = citations.length;
  const retrievalCount = retrievalItems.length;
  const answerStatusLabel = activeSessionId ? answerSessionStatusLabel(sessionStatus) : "未启动";
  const answerStatusTone = activeSessionId ? answerSessionStatusTone(sessionStatus) : "info";
  const sessionComplete = isAnswerSessionTerminal(sessionStatus);

  const submitQuestion = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitError(null);
    setSubmitMessage(null);

    const normalizedQuestion = question.trim();
    if (!normalizedQuestion) {
      setSubmitError("请输入问题后再提交。");
      return;
    }

    let scope: CreateAnswerRequest["scope"];
    if (scopeDraft.mode === "global") {
      scope = {
        mode: "global",
        payload: null
      };
    } else if (scopeDraft.mode === "document") {
      const documentId = scopeDraft.documentId.trim();
      if (!documentId) {
        setSubmitError("单文档作用域需要填写 document_id。");
        return;
      }

      scope = {
        mode: "document",
        payload: {
          document_id: documentId
        }
      };
    } else {
      const documentIds = parseDocumentIdList(scopeDraft.documentIdsText);
      if (documentIds.length === 0) {
        setSubmitError("搜索结果作用域至少需要 1 个 document_id。");
        return;
      }

      if (documentIds.length > MAX_SCOPE_DOCUMENT_IDS) {
        setSubmitError(`搜索结果作用域最多支持 ${MAX_SCOPE_DOCUMENT_IDS} 个 document_id。`);
        return;
      }

      scope = {
        mode: "search_result",
        payload: {
          document_ids: documentIds,
          truncated: false
        }
      };
    }

    createAnswerMutation.mutate({
      question: normalizedQuestion,
      scope
    });
  };

  return (
    <PageShell
      eyebrow="问答"
      title="Ask Workspace"
      description="以证据为中心的 AI 问答入口，支持全库、单文档和搜索结果作用域。"
    >
      <section className="grid gap-4 md:grid-cols-4">
        <StatCard
          label="会话状态"
          value={answerStatusLabel}
          hint={activeSessionId || "等待新会话"}
          tone={activeSessionId && !sessionComplete ? "warning" : "default"}
        />
        <StatCard label="证据数量" value={String(citationCount)} hint={activeSessionId ? "来自 answer session" : "尚未提交"} />
        <StatCard label="检索命中" value={String(retrievalCount)} hint={activeSessionId ? retrievalModeLabel(answer?.retrieval_mode) : "等待会话"} />
        <StatCard label="延迟" value={latencyLabel} hint={answer?.total_cost_usd ? formatUsd(answer.total_cost_usd) : "暂无成本数据"} />
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(360px,0.92fr)_minmax(0,1.08fr)]">
        <SectionCard
          title="提出问题"
          description="提交后会自动轮询会话状态，并在答案、拒答或失败后保留最后一次结果。"
        >
          <form className="grid gap-4" onSubmit={submitQuestion}>
            <div className="grid gap-2">
              <Textarea
                aria-label="问题"
                rows={6}
                value={question}
                onChange={(event) => setQuestion(event.target.value)}
                placeholder="例如：基于当前资料，下一步最值得优先投入的能力是什么？"
              />
              <p className="m-0 text-xs leading-6 text-slate-500">
                这页当前只做问答入口，不自动从搜索页接收结果集；`search_result` 作用域需要手动填写文档 ID。
              </p>
            </div>

            <div className="grid gap-3">
              <Select
                aria-label="作用域模式"
                value={scopeDraft.mode}
                onChange={(event) =>
                  setScopeDraft((current) => ({
                    ...current,
                    mode: event.target.value as AnswerScopeMode
                  }))
                }
              >
                {ANSWER_SCOPE_MODE_OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </Select>

              {scopeDraft.mode === "global" ? (
                <div className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700">
                  <strong className="block text-slate-950">全库作用域</strong>
                  <span>模型会在整个知识库中做混合检索，然后返回带证据的答案或拒答。</span>
                </div>
              ) : null}

              {scopeDraft.mode === "document" ? (
                <div className="grid gap-2">
                  <Input
                    aria-label="文档 ID"
                    value={scopeDraft.documentId}
                    onChange={(event) =>
                      setScopeDraft((current) => ({
                        ...current,
                        documentId: event.target.value
                      }))
                    }
                    placeholder="document_id"
                  />
                  <p className="m-0 text-xs leading-6 text-slate-500">适合单文档解释、摘要或局部证据追踪。</p>
                </div>
              ) : null}

              {scopeDraft.mode === "search_result" ? (
                <div className="grid gap-2">
                  <Textarea
                    aria-label="搜索结果文档 ID"
                    rows={4}
                    value={scopeDraft.documentIdsText}
                    onChange={(event) =>
                      setScopeDraft((current) => ({
                        ...current,
                        documentIdsText: event.target.value
                      }))
                    }
                    placeholder="document_id_1, document_id_2, document_id_3"
                  />
                  <p className="m-0 text-xs leading-6 text-slate-500">
                    用逗号或换行分隔，最多 {MAX_SCOPE_DOCUMENT_IDS} 个。后端会按这组文档做作用域快照。
                  </p>
                </div>
              ) : null}

              <div className="grid gap-3 sm:grid-cols-2">
                <Button type="submit" disabled={createAnswerMutation.isPending}>
                  {createAnswerMutation.isPending ? "创建中..." : "开始问答"}
                </Button>
                <Button
                  type="button"
                  variant="outline"
                  onClick={() => {
                    setActiveSessionId(null);
                    setSubmitError(null);
                    setSubmitMessage(null);
                  }}
                >
                  清空会话
                </Button>
              </div>

              {submitMessage ? <p className="m-0 text-sm leading-6 text-emerald-700">{submitMessage}</p> : null}
              {submitError ? <p className="m-0 text-sm leading-6 text-rose-700">{submitError}</p> : null}
            </div>
          </form>
        </SectionCard>

        <div className="grid gap-6">
          <SectionCard
            title="会话状态"
            description={
              activeSessionId
                ? sessionComplete
                  ? "会话已经进入终态，结果会保留在这里。"
                  : "会话仍在运行中，会自动轮询更新。"
                : "提交一个问题后，这里会开始展示会话、答案和证据。"
            }
          >
            {activeSessionId ? (
              <div className="grid gap-4">
                <div className="flex flex-wrap items-start justify-between gap-3">
                  <div className="grid gap-1">
                    <strong className="text-sm text-slate-950">会话 ID</strong>
                    <span className="font-mono text-xs leading-6 text-slate-600">{activeSessionId}</span>
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Badge variant={answerStatusTone}>{answerStatusLabel}</Badge>
                    <Badge variant="info">{answerScopeModeLabel(answer?.scope.mode)}</Badge>
                  </div>
                </div>
                <div className="grid gap-2 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700">
                  <strong className="text-slate-950">问题</strong>
                  <p className="m-0">{answer?.question || question || "等待后端回传问题内容。"}</p>
                  <strong className="text-slate-950">作用域</strong>
                  <p className="m-0">{answer ? summarizeAnswerScope(answer.scope) : "等待会话加载。"}</p>
                  <span>{answer ? summarizeScopePayload(answer.scope) : "会话创建后会显示作用域快照。"}</span>
                </div>
                <div className="grid gap-2 text-sm leading-6 text-slate-700">
                  <article className="rounded-2xl border border-slate-200 bg-white px-4 py-3">
                    <strong className="block text-slate-950">答案摘要</strong>
                    <span>{answer?.answer_summary || "暂未生成答案摘要。"}</span>
                  </article>
                  {answer?.refusal_reason ? (
                    <article className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-amber-900">
                      <strong className="block text-sm">拒答/收窄原因</strong>
                      <span>{answer.refusal_reason}</span>
                    </article>
                  ) : null}
                  {answer?.diagnosis_code ? (
                    <article className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                      <strong className="block text-slate-950">诊断码</strong>
                      <span>{answer.diagnosis_code}</span>
                    </article>
                  ) : null}
                  <article className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3">
                    <strong className="block text-slate-950">运行模式</strong>
                    <span>{retrievalModeLabel(answer?.retrieval_mode)}</span>
                  </article>
                </div>
              </div>
            ) : (
              <div className="grid gap-3 text-sm leading-6 text-slate-700">
                <p className="m-0">问答会话会在这里保留，刷新页面后也会从最近一次会话继续展示。</p>
                <p className="m-0">如果你要做作用域收窄，先切换到 `document` 或 `search_result`。</p>
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="证据链"
            description="每个答案都应该能回到具体文档和 chunk；没有证据时，系统应拒答或要求收窄作用域。"
          >
            {citations.length > 0 ? (
              <div className="grid gap-3">
                {citations.map((citation) => (
                  <article
                    key={`${citation.document_id}-${citation.chunk_id}`}
                    className="grid gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-4 text-sm leading-6 text-slate-700"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div className="grid gap-1">
                        <Link
                          className="font-semibold tracking-[-0.03em] text-slate-950 underline-offset-4 hover:underline"
                          to="/detail/$documentId"
                          params={{ documentId: citation.document_id }}
                        >
                          {citation.document_id}
                        </Link>
                        <span className="text-xs uppercase tracking-[0.18em] text-slate-500">{citation.chunk_id}</span>
                      </div>
                      <Badge variant="info">citation</Badge>
                    </div>
                    <blockquote className="m-0 rounded-2xl border border-slate-200 bg-white px-4 py-3 text-slate-700">
                      {citation.quote_text}
                    </blockquote>
                    <pre className="m-0 overflow-x-auto rounded-2xl bg-slate-950 px-4 py-3 text-xs leading-6 text-slate-50">
                      {citation.locator ? JSON.stringify(citation.locator, null, 2) : "无 locator"}
                    </pre>
                  </article>
                ))}
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">当前还没有可展示的证据链。</p>
            )}
          </SectionCard>

          <SectionCard
            title="检索 Trace"
            description="展示 hybrid retrieval 的命中顺序、打分和是否被最终答案使用。"
          >
            {retrievalItems.length > 0 ? (
              <div className="grid gap-3">
                {retrievalItems.map((item) => (
                  <article
                    key={`${item.rank}-${item.document_id}-${item.chunk_id || "root"}`}
                    className="grid gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-4 text-sm leading-6 text-slate-700"
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="grid gap-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge variant={item.used_in_answer ? "success" : "info"}>Rank {item.rank}</Badge>
                          {item.used_in_answer ? <Badge variant="success">used</Badge> : <Badge variant="warning">filtered</Badge>}
                        </div>
                        <Link
                          className="font-semibold tracking-[-0.03em] text-slate-950 underline-offset-4 hover:underline"
                          to="/detail/$documentId"
                          params={{ documentId: item.document_id }}
                        >
                          {item.document_id}
                        </Link>
                        <span className="text-xs uppercase tracking-[0.18em] text-slate-500">
                          {item.chunk_id || "document-level hit"}
                        </span>
                      </div>
                      <div className="grid gap-1 text-right text-xs leading-6 text-slate-500">
                        <span>lexical: {item.lexical_score ?? "null"}</span>
                        <span>semantic: {item.semantic_score ?? "null"}</span>
                        <span>final: {item.final_score ?? "null"}</span>
                      </div>
                    </div>
                    {item.exclusion_reason ? (
                      <div className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-amber-900">
                        {item.exclusion_reason}
                      </div>
                    ) : null}
                  </article>
                ))}
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">
                还没有检索 trace。提交一个问题后，这里会展示候选文档的排序和过滤原因。
              </p>
            )}
          </SectionCard>
        </div>
      </section>
    </PageShell>
  );
}
