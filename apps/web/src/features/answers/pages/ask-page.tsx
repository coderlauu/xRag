import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import type { AnswerScope, AnswerSessionStatus, CreateAnswerRequest } from "@xrag/shared-types";
import { Badge, Button, Input, PageShell, SectionCard, Select, StatCard, Textarea } from "@xrag/ui";
import { createAnswer, getAnswer, getAnswerRetrieval, listAnswers } from "../../../lib/api";
import { GovernanceNoticeStrip } from "../../ops/components/governance-notice-strip";
import {
  ANSWER_SCOPE_MODE_OPTIONS,
  answerClaimFreshnessLabel,
  answerClaimFreshnessTone,
  answerScopeModeLabel,
  answerSessionStatusLabel,
  answerSessionStatusTone,
  buildScopeFiltersFromDraft,
  createDefaultAnswerScopeDraft,
  formatAnswerUpdatedAt,
  formatLatencyMs,
  formatUsd,
  isAnswerSessionActive,
  isAnswerSessionTerminal,
  parseAskWorkspaceSearch,
  parseDelimitedValueList,
  parseDocumentIdList,
  parseSourceTypeList,
  retrievalExclusionReasonLabel,
  retrievalModeLabel,
  scopeDraftFromAnswerScope,
  summarizeAnswerScope,
  summarizeScopeFilters,
  summarizeScopePayload
} from "../../../lib/answer-state";
import {
  clearAnswerSessionId,
  loadAnswerWorkspaceState,
  rememberAnswerWorkspaceState
} from "../../../lib/answer-session-store";

const MAX_SCOPE_DOCUMENT_IDS = 100;
const HISTORY_PAGE_SIZE = 8;
const GLOBAL_OR_SEARCH_SOURCE_TYPE_HINT = "text, file, pdf, link";
const ACTIVE_SESSION_CLIENT_TIMEOUT_MS = 10 * 60 * 1000;

export function AskPage() {
  const queryClient = useQueryClient();
  const searchJson = useRouterState({
    select: (state) => JSON.stringify(state.location.search ?? {})
  });
  const routeState = parseAskWorkspaceSearch(JSON.parse(searchJson) as Record<string, unknown>);
  const storedWorkspace = loadAnswerWorkspaceState();
  const [question, setQuestion] = useState("");
  const [scopeDraft, setScopeDraft] = useState(() =>
    routeState.hasPrefill ? routeState.scopeDraft : storedWorkspace?.scopeDraft ?? createDefaultAnswerScopeDraft()
  );
  const [activeSessionId, setActiveSessionId] = useState<string | null>(() => storedWorkspace?.activeSessionId ?? null);
  const [continuedFromSessionId, setContinuedFromSessionId] = useState<string | null>(() =>
    routeState.hasPrefill ? routeState.continuedFromSessionId : storedWorkspace?.continuedFromSessionId ?? null
  );
  const [activeSessionObservedAt, setActiveSessionObservedAt] = useState<number | null>(() =>
    storedWorkspace?.activeSessionId ? Date.now() : null
  );
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [submitMessage, setSubmitMessage] = useState<string | null>(null);

  useEffect(() => {
    if (!routeState.hasPrefill) {
      return;
    }

    setScopeDraft(routeState.scopeDraft);
    setContinuedFromSessionId(routeState.continuedFromSessionId);
    setSubmitError(null);
  }, [searchJson]);

  useEffect(() => {
    rememberAnswerWorkspaceState({
      activeSessionId,
      continuedFromSessionId,
      scopeDraft
    });
  }, [activeSessionId, continuedFromSessionId, scopeDraft]);

  useEffect(() => {
    setActiveSessionObservedAt(activeSessionId ? Date.now() : null);
  }, [activeSessionId]);

  const isPollingStuck = (status: AnswerSessionStatus | undefined, updatedAt?: string) => {
    if (!activeSessionId || !isAnswerSessionActive(status)) {
      return false;
    }

    const referenceTime = updatedAt ? new Date(updatedAt).getTime() : activeSessionObservedAt;
    if (!referenceTime) {
      return false;
    }

    return Date.now() - referenceTime >= ACTIVE_SESSION_CLIENT_TIMEOUT_MS;
  };

  const answerQuery = useQuery({
    queryKey: ["answers", activeSessionId],
    queryFn: () => getAnswer(activeSessionId || ""),
    enabled: Boolean(activeSessionId),
    refetchInterval: (query) =>
      isAnswerSessionActive(query.state.data?.status) &&
      !isPollingStuck(query.state.data?.status, query.state.data?.updated_at)
        ? 2500
        : false
  });

  const retrievalQuery = useQuery({
    queryKey: ["answers", activeSessionId, "retrieval"],
    queryFn: () => getAnswerRetrieval(activeSessionId || ""),
    enabled: Boolean(activeSessionId),
    refetchInterval: () =>
      isAnswerSessionActive(answerQuery.data?.status) && !isPollingStuck(answerQuery.data?.status, answerQuery.data?.updated_at)
        ? 2500
        : false
  });

  const historyQuery = useQuery({
    queryKey: ["answers", "history", HISTORY_PAGE_SIZE],
    queryFn: () => listAnswers({ page: 1, page_size: HISTORY_PAGE_SIZE }),
    refetchInterval: () =>
      isAnswerSessionActive(answerQuery.data?.status) && !isPollingStuck(answerQuery.data?.status, answerQuery.data?.updated_at)
        ? 5000
        : false
  });

  const createAnswerMutation = useMutation({
    mutationFn: (body: CreateAnswerRequest) => createAnswer(body),
    onSuccess: async (result) => {
      setSubmitError(null);
      setSubmitMessage(`会话 ${result.session_id} 已创建，正在进入 ${answerSessionStatusLabel(result.status)}。`);
      setActiveSessionId(result.session_id);
      await queryClient.invalidateQueries({ queryKey: ["answers", "history"] });
    },
    onError: (error) => {
      setSubmitMessage(null);
      setSubmitError(error instanceof Error ? error.message : "创建问答会话失败");
    }
  });

  const answer = answerQuery.data;
  const retrieval = retrievalQuery.data;
  const activeSessionPollingStuck = isPollingStuck(answer?.status, answer?.updated_at);
  const historyItems = historyQuery.data?.items || [];
  const sessionStatus = answer?.status ?? "idle";
  const evidenceGroups = answer?.evidence_groups ?? [];
  const citations = answer?.citations ?? [];
  const retrievalItems = retrieval?.items ?? [];
  const retrievalSummary = retrieval?.summary;
  const latencyLabel = formatLatencyMs(answer?.latency_ms);
  const evidenceGroupCount = evidenceGroups.length;
  const retrievalCount = retrievalItems.length;
  const answerStatusLabel = activeSessionId ? answerSessionStatusLabel(sessionStatus) : "未启动";
  const answerStatusTone = activeSessionId ? answerSessionStatusTone(sessionStatus) : "info";
  const sessionComplete = isAnswerSessionTerminal(sessionStatus);
  const scopeFilters = scopeDraft.mode === "document" ? null : buildScopeFiltersFromDraft(scopeDraft);
  const scopeFiltersSummary = summarizeScopeFilters(scopeFilters);

  const applyFollowUpSource = (sessionId: string, scope: AnswerScope) => {
    setActiveSessionId(sessionId);
    setContinuedFromSessionId(sessionId);
    setScopeDraft(scopeDraftFromAnswerScope(scope));
    setSubmitError(null);
    setSubmitMessage(`后续问题将承接会话 ${sessionId}，并已显式回填该会话的作用域。`);
  };

  const submitQuestion = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setSubmitError(null);
    setSubmitMessage(null);

    const normalizedQuestion = question.trim();
    if (!normalizedQuestion) {
      setSubmitError("请输入问题后再提交。");
      return;
    }

    const normalizedDocumentId = scopeDraft.documentId.trim();
    const normalizedDocumentIds = parseDocumentIdList(scopeDraft.documentIdsText);
    const normalizedTags = parseDelimitedValueList(scopeDraft.filterTagsText).join(", ");
    const parsedSourceTypes = parseSourceTypeList(scopeDraft.filterSourceTypesText);

    if (parsedSourceTypes.invalid.length > 0) {
      setSubmitError(`未知的 source_type：${parsedSourceTypes.invalid.join(", ")}。允许值为 ${GLOBAL_OR_SEARCH_SOURCE_TYPE_HINT}。`);
      return;
    }

    let scope: CreateAnswerRequest["scope"];
    if (scopeDraft.mode === "global") {
      scope = {
        mode: "global",
        payload: scopeFilters ? { filters: scopeFilters } : null
      };
    } else if (scopeDraft.mode === "document") {
      if (!normalizedDocumentId) {
        setSubmitError("单文档作用域需要填写 document_id。");
        return;
      }

      scope = {
        mode: "document",
        payload: {
          document_id: normalizedDocumentId
        }
      };
    } else {
      if (normalizedDocumentIds.length === 0) {
        setSubmitError("搜索结果作用域至少需要 1 个 document_id。");
        return;
      }

      if (normalizedDocumentIds.length > MAX_SCOPE_DOCUMENT_IDS) {
        setSubmitError(`搜索结果作用域最多支持 ${MAX_SCOPE_DOCUMENT_IDS} 个 document_id。`);
        return;
      }

      scope = {
        mode: "search_result",
        payload: {
          document_ids: normalizedDocumentIds,
          truncated: false,
          query: scopeDraft.searchQuery.trim() || null,
          filters: scopeFilters
        }
      };
    }

    setScopeDraft((current) => ({
      ...current,
      documentId: normalizedDocumentId,
      documentIdsText: normalizedDocumentIds.join(", "),
      filterTagsText: normalizedTags,
      filterSourceTypesText: parsedSourceTypes.values.join(", ")
    }));

    createAnswerMutation.mutate({
      question: normalizedQuestion,
      scope,
      continued_from_session_id: continuedFromSessionId || undefined
    });
  };

  return (
    <PageShell
      eyebrow="问答"
      title="Ask Workspace"
      description="以证据为中心的 AI 问答入口，支持 recent history、continue asking、typed scope filters 与 claim-based evidence groups。"
    >
      <section className="grid gap-4 md:grid-cols-4">
        <StatCard
          label="会话状态"
          value={answerStatusLabel}
          hint={activeSessionId || "等待新会话"}
          tone={activeSessionId && !sessionComplete ? "warning" : "default"}
        />
        <StatCard
          label="证据组"
          value={String(evidenceGroupCount)}
          hint={activeSessionId ? "按 claim_slot 聚合" : "尚未提交"}
        />
        <StatCard label="检索命中" value={String(retrievalCount)} hint={activeSessionId ? retrievalModeLabel(answer?.retrieval_mode) : "等待会话"} />
        <StatCard label="延迟" value={latencyLabel} hint={answer?.total_cost_usd ? formatUsd(answer.total_cost_usd) : "暂无成本数据"} />
      </section>

      <GovernanceNoticeStrip target="ask" />

      <section className="grid gap-6 xl:grid-cols-[minmax(360px,0.92fr)_minmax(0,1.08fr)]">
        <div className="grid gap-6">
          <SectionCard
            title="提出问题"
            description="新问题始终显式提交 scope；若启用 continue asking，只继承 lineage，不隐式继承作用域。"
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
                  {routeState.hasPrefill
                    ? "当前 scope 已从 URL 预填；如果你是从搜索页或详情页进入，这里的作用域已经可直接提交。"
                    : "如果需要作为 follow-up 提问，请先从右侧当前会话或下方 recent history 里显式选择 continue source。"}
                </p>
              </div>

              <div className="grid gap-3">
                <Select
                  aria-label="作用域模式"
                  value={scopeDraft.mode}
                  onChange={(event) =>
                    setScopeDraft((current) => ({
                      ...current,
                      mode: event.target.value as AnswerScope["mode"]
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
                    <span>模型会在整个知识库中做混合检索；如果附加 tags/source_types/date，会先做 typed scope 收窄。</span>
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
                  <div className="grid gap-3">
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
                    <Input
                      aria-label="查询快照"
                      value={scopeDraft.searchQuery}
                      onChange={(event) =>
                        setScopeDraft((current) => ({
                          ...current,
                          searchQuery: event.target.value
                        }))
                      }
                      placeholder="当前搜索 query，可选"
                    />
                    <p className="m-0 text-xs leading-6 text-slate-500">
                      用逗号或换行分隔，最多 {MAX_SCOPE_DOCUMENT_IDS} 个。后端会把这组文档和查询快照一起记录为 scope snapshot。
                    </p>
                  </div>
                ) : null}

                {scopeDraft.mode !== "document" ? (
                  <div className="grid gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-4">
                    <div className="grid gap-1">
                      <strong className="text-sm text-slate-950">附加过滤条件</strong>
                      <span className="text-xs leading-6 text-slate-500">
                        仅对 `global` / `search_result` 生效，用于显式收窄候选范围。
                      </span>
                    </div>
                    <Input
                      aria-label="标签过滤"
                      value={scopeDraft.filterTagsText}
                      onChange={(event) =>
                        setScopeDraft((current) => ({
                          ...current,
                          filterTagsText: event.target.value
                        }))
                      }
                      placeholder="知识库, 评估, 生产"
                    />
                    <Input
                      aria-label="来源过滤"
                      value={scopeDraft.filterSourceTypesText}
                      onChange={(event) =>
                        setScopeDraft((current) => ({
                          ...current,
                          filterSourceTypesText: event.target.value
                        }))
                      }
                      placeholder={GLOBAL_OR_SEARCH_SOURCE_TYPE_HINT}
                    />
                    <div className="grid gap-3 sm:grid-cols-2">
                      <Input
                        aria-label="过滤开始时间"
                        type="datetime-local"
                        value={scopeDraft.filterDateFrom}
                        onChange={(event) =>
                          setScopeDraft((current) => ({
                            ...current,
                            filterDateFrom: event.target.value
                          }))
                        }
                      />
                      <Input
                        aria-label="过滤结束时间"
                        type="datetime-local"
                        value={scopeDraft.filterDateTo}
                        onChange={(event) =>
                          setScopeDraft((current) => ({
                            ...current,
                            filterDateTo: event.target.value
                          }))
                        }
                      />
                    </div>
                    <p className="m-0 text-xs leading-6 text-slate-500">
                      {scopeFiltersSummary ? `当前过滤条件：${scopeFiltersSummary}` : "当前未附加过滤条件。"}
                    </p>
                  </div>
                ) : null}

                {continuedFromSessionId ? (
                  <div className="grid gap-2 rounded-2xl border border-sky-200 bg-sky-50 px-4 py-3 text-sm leading-6 text-sky-900">
                    <strong>Continue Asking</strong>
                    <span>新问题会承接会话 {continuedFromSessionId} 的 lineage，但仍以当前表单里的 scope 作为显式输入。</span>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        onClick={() => setContinuedFromSessionId(null)}
                      >
                        清空续问链
                      </Button>
                      <Button
                        type="button"
                        variant="ghost"
                        onClick={() => setActiveSessionId(continuedFromSessionId)}
                      >
                        查看上游会话
                      </Button>
                    </div>
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
                      setContinuedFromSessionId(null);
                      setSubmitError(null);
                      setSubmitMessage(null);
                      clearAnswerSessionId();
                    }}
                  >
                    清空当前会话
                  </Button>
                </div>

                {submitMessage ? <p className="m-0 text-sm leading-6 text-emerald-700">{submitMessage}</p> : null}
                {submitError ? <p className="m-0 text-sm leading-6 text-rose-700">{submitError}</p> : null}
              </div>
            </form>
          </SectionCard>

          <SectionCard
            title="Recent History"
            description="最近会话来自 `GET /api/v1/answers`，可直接恢复查看，或显式选择某次会话作为 continue source。"
          >
            {historyQuery.isLoading ? (
              <p className="m-0 text-sm leading-6 text-slate-600">正在加载 recent answers。</p>
            ) : historyQuery.isError ? (
              <p className="m-0 text-sm leading-6 text-rose-700">
                {historyQuery.error instanceof Error ? historyQuery.error.message : "recent answers 加载失败"}
              </p>
            ) : historyItems.length === 0 ? (
              <p className="m-0 text-sm leading-6 text-slate-600">当前还没有 recent answers，会话创建后会自动出现在这里。</p>
            ) : (
              <div className="grid gap-3">
                {historyItems.map((item) => {
                  const isActiveItem = item.session_id === activeSessionId;
                  const canContinue = isAnswerSessionTerminal(item.status);

                  return (
                    <article
                      key={item.session_id}
                      className={`grid gap-3 rounded-2xl border px-4 py-4 text-sm leading-6 ${
                        isActiveItem ? "border-sky-200 bg-sky-50/70 text-sky-950" : "border-slate-200 bg-slate-50/80 text-slate-700"
                      }`}
                    >
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div className="grid gap-1">
                          <strong className="text-slate-950">{item.question}</strong>
                          <span className="font-mono text-xs text-slate-500">{item.session_id}</span>
                        </div>
                        <div className="flex flex-wrap gap-2">
                          <Badge variant={answerSessionStatusTone(item.status)}>{answerSessionStatusLabel(item.status)}</Badge>
                          <Badge variant="info">{answerScopeModeLabel(item.scope.mode)}</Badge>
                          {item.continued_from_session_id ? <Badge variant="warning">follow-up</Badge> : null}
                        </div>
                      </div>
                      <div className="grid gap-1 text-xs leading-6 text-slate-500">
                        <span>{item.scope_summary || summarizeAnswerScope(item.scope)}</span>
                        <span>更新时间：{formatAnswerUpdatedAt(item.updated_at)}</span>
                        {item.continued_from_session_id ? <span>承接自：{item.continued_from_session_id}</span> : null}
                      </div>
                      <div className="flex flex-wrap gap-2">
                        <Button
                          type="button"
                          variant={isActiveItem ? "ghost" : "outline"}
                          onClick={() => {
                            setActiveSessionId(item.session_id);
                            setSubmitError(null);
                            setSubmitMessage(null);
                          }}
                        >
                          {isActiveItem ? "当前会话" : "查看会话"}
                        </Button>
                        <Button
                          type="button"
                          variant="outline"
                          disabled={!canContinue}
                          onClick={() => applyFollowUpSource(item.session_id, item.scope)}
                        >
                          继续提问
                        </Button>
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </SectionCard>
        </div>

        <div className="grid gap-6">
          <SectionCard
            title="会话状态"
            description={
              activeSessionId
                ? sessionComplete
                  ? "会话已经进入终态，结果会保留在这里，可直接继续基于它提问。"
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
                    <Badge data-testid="ask-active-session-status" variant={answerStatusTone}>
                      {answerStatusLabel}
                    </Badge>
                    <Badge variant="info">{answerScopeModeLabel(answer?.scope.mode)}</Badge>
                    {answer?.continued_from_session_id ? <Badge variant="warning">follow-up</Badge> : null}
                  </div>
                </div>
                <div className="grid gap-2 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700">
                  <strong className="text-slate-950">问题</strong>
                  <p className="m-0">{answer?.question || question || "等待后端回传问题内容。"}</p>
                  <strong className="text-slate-950">作用域</strong>
                  <p className="m-0">{answer ? summarizeAnswerScope(answer.scope) : "等待会话加载。"}</p>
                  <span>{answer ? summarizeScopePayload(answer.scope) : "会话创建后会显示作用域快照。"}</span>
                  {answer?.continued_from_session_id ? (
                    <span>继续来源：{answer.continued_from_session_id}</span>
                  ) : null}
                </div>
                {activeSessionPollingStuck ? (
                  <article
                    data-testid="ask-stuck-notice"
                    className="grid gap-3 rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm leading-6 text-amber-900"
                  >
                    <div className="grid gap-1">
                      <strong>该会话长时间未进入终态</strong>
                      <span>
                        页面已停止自动轮询，避免持续请求同一个 active session。服务端应最终将不可恢复会话收口为 failed；如果状态仍未变化，请进入运维主板继续排查 queue / worker。
                      </span>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Button
                        type="button"
                        variant="outline"
                        onClick={async () => {
                          await Promise.all([
                            answerQuery.refetch(),
                            retrievalQuery.refetch(),
                            historyQuery.refetch()
                          ]);
                        }}
                      >
                        手动刷新一次
                      </Button>
                      <Link className="text-sm font-semibold underline-offset-4 hover:underline" to="/ops">
                        查看运维主板
                      </Link>
                    </div>
                  </article>
                ) : null}
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
                {answer ? (
                  <div className="flex flex-wrap gap-2">
                    <Button
                      type="button"
                      variant="outline"
                      disabled={!isAnswerSessionTerminal(answer.status)}
                      onClick={() => applyFollowUpSource(answer.session_id, answer.scope)}
                    >
                      基于当前会话继续提问
                    </Button>
                    {answer.continued_from_session_id ? (
                      <Button
                        type="button"
                        variant="ghost"
                        onClick={() => setActiveSessionId(answer.continued_from_session_id)}
                      >
                        查看上游会话
                      </Button>
                    ) : null}
                  </div>
                ) : null}
              </div>
            ) : (
              <div className="grid gap-3 text-sm leading-6 text-slate-700">
                <p className="m-0">问答会话会在这里保留，刷新页面后仍会恢复最近一次的 session 与 scope draft。</p>
                <p className="m-0">如果你要做作用域收窄，优先从 `global + typed filters` 或 `search_result snapshot` 开始。</p>
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="Evidence Groups"
            description="答案按 claim_slot 聚合证据；每个 group 都是可审计的 claim-level evidence package。"
          >
            {evidenceGroups.length > 0 ? (
              <div className="grid gap-3">
                {evidenceGroups.map((group) => (
                  <article
                    key={group.claim_slot}
                    className="grid gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-4 text-sm leading-6 text-slate-700"
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="grid gap-1">
                        <strong className="text-slate-950">{group.claim_text}</strong>
                        <span className="text-xs uppercase tracking-[0.18em] text-slate-500">{group.claim_slot}</span>
                      </div>
                      <Badge variant={answerClaimFreshnessTone(group.freshness_badge)}>
                        {answerClaimFreshnessLabel(group.freshness_badge)}
                      </Badge>
                    </div>
                    {group.citations.length === 0 ? (
                      <p className="m-0 text-sm leading-6 text-slate-600">当前 claim 暂无 citation。</p>
                    ) : (
                      <div className="grid gap-3">
                        {group.citations.map((citation) => (
                          <article
                            key={`${group.claim_slot}-${citation.document_id}-${citation.chunk_id}`}
                            className="grid gap-3 rounded-2xl border border-slate-200 bg-white px-4 py-3"
                          >
                            <div className="flex flex-wrap items-center justify-between gap-3">
                              <div className="grid gap-1">
                                <Link
                                  className="font-semibold tracking-[-0.03em] text-slate-950 underline-offset-4 hover:underline"
                                  to="/detail/$documentId"
                                  params={{ documentId: citation.document_id }}
                                  search={
                                    {
                                      answer_session_id: activeSessionId || undefined,
                                      claim_slot: group.claim_slot,
                                      jumpback_source: "evidence_group"
                                    } as never
                                  }
                                  hash={`evidence-${citation.chunk_id}`}
                                >
                                  {citation.document_id}
                                </Link>
                                <span className="text-xs uppercase tracking-[0.18em] text-slate-500">{citation.chunk_id}</span>
                              </div>
                              <Badge variant="info">citation</Badge>
                            </div>
                            <blockquote className="m-0 rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-slate-700">
                              {citation.quote_text}
                            </blockquote>
                          </article>
                        ))}
                      </div>
                    )}
                  </article>
                ))}
              </div>
            ) : citations.length > 0 ? (
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
                          search={
                            {
                              answer_session_id: activeSessionId || undefined,
                              jumpback_source: "citation"
                            } as never
                          }
                          hash={`evidence-${citation.chunk_id}`}
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
                  </article>
                ))}
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">当前还没有可展示的 evidence groups。</p>
            )}
          </SectionCard>

          <SectionCard
            title="检索 Trace"
            description="展示 retrieval summary、候选命中顺序、打分和最终未入答原因。"
          >
            {retrievalSummary ? (
              <article className="mb-4 grid gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-4 text-sm leading-6 text-slate-700">
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <strong className="text-slate-950">Retrieval Summary</strong>
                  <Badge variant="info">{retrievalModeLabel(retrievalSummary.rerank_strategy)}</Badge>
                </div>
                <div className="grid gap-2 sm:grid-cols-2">
                  <span>normalized query：{retrievalSummary.query_normalized}</span>
                  <span>eligible documents：{retrievalSummary.eligible_document_count}</span>
                  <span>lexical hits：{retrievalSummary.lexical_hit_count}</span>
                  <span>semantic hits：{retrievalSummary.semantic_hit_count}</span>
                  <span>merged hits：{retrievalSummary.merged_hit_count}</span>
                  <span>latency：{formatLatencyMs(retrievalSummary.latency_ms)}</span>
                </div>
              </article>
            ) : null}

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
                          {item.used_in_answer ? <Badge variant="success">used</Badge> : <Badge variant="warning">excluded</Badge>}
                        </div>
                        <Link
                          className="font-semibold tracking-[-0.03em] text-slate-950 underline-offset-4 hover:underline"
                          to="/detail/$documentId"
                          params={{ documentId: item.document_id }}
                          search={
                            {
                              answer_session_id: activeSessionId || undefined,
                              jumpback_source: "retrieval"
                            } as never
                          }
                          hash={item.chunk_id ? `evidence-${item.chunk_id}` : undefined}
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
                        {retrievalExclusionReasonLabel(item.exclusion_reason)}
                      </div>
                    ) : null}
                  </article>
                ))}
              </div>
            ) : (
              <p className="m-0 text-sm leading-6 text-slate-600">
                还没有检索 trace。提交一个问题后，这里会展示 retrieval summary 和候选文档的排序、过滤原因。
              </p>
            )}
          </SectionCard>
        </div>
      </section>
    </PageShell>
  );
}
