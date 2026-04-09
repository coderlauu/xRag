import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import type { DocumentProcessingEventItem } from "@xrag/shared-types";
import { Badge, Button, PageShell, SectionCard, StatCard, Textarea, Input } from "@xrag/ui";
import {
  getDocument,
  getDocumentEvidence,
  getDocumentTimeline,
  getJob,
  reindexDocument,
  retryDocument,
  updateDocumentTags
} from "../../../lib/api";
import {
  describeEvidenceLocator,
  diagnosisLabel,
  formatDateTime,
  formatRelativeTime,
  indexStatusLabel,
  indexStatusTone,
  isCitationReady,
  isJobActive,
  isIndexActive,
  isParseActive,
  joinTags,
  jobStatusLabel,
  jobStatusTone,
  parseStatusLabel,
  parseStatusTone,
  splitTags,
  sourceTypeLabel,
  summarizeIndexReadiness,
  uploadStatusLabel
} from "../../../lib/document-state";
import { loadJobId, rememberJobId } from "../../../lib/job-store";

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

export function DetailPage() {
  const queryClient = useQueryClient();
  const pathname = useRouterState({ select: (state) => state.location.pathname });
  const documentId = decodeURIComponent(pathname.split("/").filter(Boolean).pop() || "");
  const hasValidDocumentId = UUID_PATTERN.test(documentId);
  const [tagInput, setTagInput] = useState("");
  const [tagError, setTagError] = useState<string | null>(null);
  const [tagMessage, setTagMessage] = useState<string | null>(null);
  const [indexActionError, setIndexActionError] = useState<string | null>(null);
  const [indexActionMessage, setIndexActionMessage] = useState<string | null>(null);
  const [trackedJobId, setTrackedJobId] = useState<string | null>(loadJobId(documentId));

  useEffect(() => {
    setTrackedJobId(loadJobId(documentId));
    setTagError(null);
    setTagMessage(null);
    setIndexActionError(null);
    setIndexActionMessage(null);
  }, [documentId]);

  const documentQuery = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId),
    enabled: hasValidDocumentId,
    refetchInterval: (query) =>
      query.state.data && (isParseActive(query.state.data.parse_status) || isIndexActive(query.state.data.index_status))
        ? 3000
        : false
  });

  useEffect(() => {
    if (documentQuery.data) {
      setTagInput(joinTags(documentQuery.data.tags));
    }
  }, [documentQuery.data?.id]);

  const trackedJobQuery = useQuery({
    queryKey: ["job", trackedJobId],
    queryFn: () => getJob(trackedJobId || ""),
    enabled: Boolean(trackedJobId),
    refetchInterval: (query) => (query.state.data && isJobActive(query.state.data.status) ? 3000 : false)
  });

  const documentEvidenceQuery = useQuery({
    queryKey: ["document", documentId, "evidence"],
    queryFn: () => getDocumentEvidence(documentId),
    enabled: hasValidDocumentId,
    refetchInterval: () => (documentQuery.data && isIndexActive(documentQuery.data.index_status) ? 3000 : false)
  });

  const timelineQuery = useQuery({
    queryKey: ["document", documentId, "timeline"],
    queryFn: () => getDocumentTimeline(documentId),
    enabled: hasValidDocumentId,
    refetchInterval: () =>
      documentQuery.data &&
      (isParseActive(documentQuery.data.parse_status) ||
        isIndexActive(documentQuery.data.index_status) ||
        (trackedJobQuery.data && isJobActive(trackedJobQuery.data.status)))
        ? 3000
        : false
  });

  const updateTagsMutation = useMutation({
    mutationFn: (tags: string[]) => updateDocumentTags(documentId, { tags }),
    onSuccess: async (updatedDocument) => {
      setTagError(null);
      setTagMessage("标签已更新。");
      setTagInput(joinTags(updatedDocument.tags));
      await queryClient.invalidateQueries({ queryKey: ["document", documentId] });
      await queryClient.invalidateQueries({ queryKey: ["documents"] });
    },
    onError: (error) => {
      setTagError(error instanceof Error ? error.message : "更新标签失败");
    }
  });

  const retryMutation = useMutation({
    mutationFn: () => retryDocument(documentId),
    onSuccess: async (result) => {
      rememberJobId(result.document_id, result.job_id);
      setTrackedJobId(result.job_id);
      await queryClient.invalidateQueries({ queryKey: ["document", documentId] });
      await queryClient.invalidateQueries({ queryKey: ["job"] });
    },
    onError: async (error) => {
      setTagError(error instanceof Error ? error.message : "重试解析失败");
    }
  });

  const reindexMutation = useMutation({
    mutationFn: () => reindexDocument(documentId),
    onSuccess: async (result) => {
      rememberJobId(result.document_id, result.job_id);
      setTrackedJobId(result.job_id);
      setIndexActionError(null);
      setIndexActionMessage(`索引任务已提交，当前状态：${indexStatusLabel(result.index_status)}。`);
      await queryClient.invalidateQueries({ queryKey: ["document", documentId] });
      await queryClient.invalidateQueries({ queryKey: ["document", documentId, "evidence"] });
      await queryClient.invalidateQueries({ queryKey: ["document", documentId, "timeline"] });
      await queryClient.invalidateQueries({ queryKey: ["documents"] });
      await queryClient.invalidateQueries({ queryKey: ["job"] });
    },
    onError: (error) => {
      setIndexActionMessage(null);
      setIndexActionError(error instanceof Error ? error.message : "重建索引失败");
    }
  });

  if (!hasValidDocumentId) {
    return (
      <PageShell eyebrow="详情" title="文档详情" description="当前路由里没有有效的文档 ID。">
        <SectionCard title="导航错误" description="请返回导入页或搜索页重新选择文档。">
          <div className="flex gap-3">
            <Button asChild variant="outline">
              <Link to="/">返回导入页</Link>
            </Button>
            <Button asChild>
              <Link to="/search">返回搜索页</Link>
            </Button>
          </div>
        </SectionCard>
      </PageShell>
    );
  }

  if (documentQuery.isLoading) {
    return (
      <PageShell eyebrow="详情" title="文档详情" description="正在加载文档详情。">
        <SectionCard title="加载中" description="正在从 API 拉取文档详情。">
          <p className="m-0 text-sm leading-6 text-slate-600">请稍候，详情页正在准备数据。</p>
        </SectionCard>
      </PageShell>
    );
  }

  if (documentQuery.isError || !documentQuery.data) {
    return (
      <PageShell eyebrow="详情" title="文档详情" description="请求的文档无法加载。">
        <SectionCard title="加载失败" description="文档可能不存在，或者 API 当前不可用。">
          <div className="grid gap-3">
            <p className="m-0 text-sm leading-6 text-slate-600">
              {documentQuery.error instanceof Error ? documentQuery.error.message : "未知错误"}
            </p>
            <div className="flex gap-3">
              <Button asChild variant="outline">
                <Link to="/">返回导入页</Link>
              </Button>
              <Button asChild>
                <Link to="/search">返回搜索页</Link>
              </Button>
            </div>
          </div>
        </SectionCard>
      </PageShell>
    );
  }

  const document = documentQuery.data;
  const job = trackedJobQuery.data;
  const timelineItems = timelineQuery.data?.items || [];
  const canReindex = document.parse_status === "success" && !isIndexActive(document.index_status);

  return (
    <PageShell
      eyebrow="详情"
      title={document.title}
      description="查看正文、上传会话、诊断结果、索引可用性和重建状态。"
    >
      <section className="grid gap-4 md:grid-cols-4">
        <StatCard label="解析状态" value={parseStatusLabel(document.parse_status)} hint="自动轮询接口状态" />
        <StatCard
          label="索引状态"
          value={indexStatusLabel(document.index_status)}
          hint={summarizeIndexReadiness(document.index_status, document.citation_ready, document.indexed_at)}
          tone={indexStatusTone(document.index_status) === "warning" ? "warning" : "default"}
        />
        <StatCard
          label="引用可用"
          value={isCitationReady(document.index_status, document.citation_ready) ? "是" : "否"}
          hint={document.indexed_at ? `最近索引 ${formatRelativeTime(document.indexed_at)}` : "等待索引完成"}
        />
        <StatCard
          label="索引版本"
          value={document.index_version || "未记录"}
          hint={document.indexed_at ? formatDateTime(document.indexed_at) : "尚未建立索引"}
        />
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
        <SectionCard title="正文内容" description="展示提取后的正文，以及必要时的原始内容。">
          <div className="grid gap-5">
            <div className="grid gap-2">
              <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">清洗后正文</p>
              <pre className="m-0 overflow-x-auto whitespace-pre-wrap rounded-2xl bg-slate-950 px-4 py-4 text-sm leading-7 text-slate-50">
                {document.content_clean || document.content_raw || "暂无可用正文。"}
              </pre>
            </div>
            {document.content_raw && document.content_raw !== document.content_clean ? (
              <div className="grid gap-2">
                <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">原始提取结果</p>
                <pre className="m-0 overflow-x-auto whitespace-pre-wrap rounded-2xl border border-slate-200 bg-slate-50 px-4 py-4 text-sm leading-7 text-slate-700">
                  {document.content_raw}
                </pre>
              </div>
            ) : null}
          </div>
        </SectionCard>

        <div className="grid gap-6">
          <SectionCard title="文档信息" description="查看来源元数据、诊断摘要和关联事件。">
            <div className="grid gap-3 text-sm leading-6 text-slate-700">
              {document.parse_error_message ? (
                <div className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-rose-700">
                  <strong className="block text-sm">解析错误</strong>
                  <span>{document.parse_error_message}</span>
                </div>
              ) : null}
              {document.diagnosis_summary ? (
                <div className="rounded-2xl border border-amber-200 bg-amber-50 px-4 py-3 text-amber-800">
                  <strong className="block text-sm">诊断摘要</strong>
                  <span>{document.diagnosis_summary}</span>
                </div>
              ) : null}
              {document.match_explanation ? (
                <div className="rounded-2xl border border-sky-200 bg-sky-50 px-4 py-3 text-sky-900">
                  <strong className="block text-sm">命中说明</strong>
                  <span>{document.match_explanation}</span>
                </div>
              ) : null}
              {document.ranking_hint ? (
                <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-slate-700">
                  <strong className="block text-sm text-slate-900">排序提示</strong>
                  <span>{document.ranking_hint}</span>
                </div>
              ) : null}
              <article>来源：{sourceTypeLabel(document.source_type)}</article>
              <article>标签：{document.tags.length > 0 ? joinTags(document.tags) : "暂无"}</article>
              <article>文件名：{document.file_name || "手动输入"}</article>
              <article>来源链接：{document.source_url || "无"}</article>
              <article>MIME 类型：{document.mime_type || "无"}</article>
              <article>导入时间：{formatDateTime(document.imported_at)}</article>
              <article>索引状态：{indexStatusLabel(document.index_status)}</article>
              <article>引用可用：{isCitationReady(document.index_status, document.citation_ready) ? "是" : "否"}</article>
              <article>索引时间：{document.indexed_at ? formatDateTime(document.indexed_at) : "无"}</article>
              <article>索引版本：{document.index_version || "无"}</article>
              <article>上传状态：{uploadStatusLabel(document.upload_status)}</article>
              <article>诊断结果：{diagnosisLabel(document.diagnosis_code)}</article>
              <article>诊断摘要：{document.diagnosis_summary || "无"}</article>
              <article>解析器：{document.parser_name || "无"}</article>
              <article>页数：{document.page_count ?? "无"}</article>
              <article>事件引用：{document.last_incident_ref || "无"}</article>
            </div>
          </SectionCard>

          <SectionCard title="操作区" description="更新标签、重试解析、重建索引，或返回其他页面继续排查。">
            <div className="grid gap-3">
              <form
                className="grid gap-3"
                onSubmit={(event: FormEvent<HTMLFormElement>) => {
                  event.preventDefault();
                  setTagError(null);
                  setTagMessage(null);
                  updateTagsMutation.mutate(splitTags(tagInput));
                }}
              >
                <Input
                  aria-label="标签"
                  value={tagInput}
                  onChange={(event) => setTagInput(event.target.value)}
                  placeholder="知识库, 检索, 诊断"
                />
                <div className="flex flex-wrap gap-3">
                  <Button type="submit" disabled={updateTagsMutation.isPending}>
                    {updateTagsMutation.isPending ? "保存中..." : "保存标签"}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={retryMutation.isPending}
                    onClick={() => retryMutation.mutate()}
                  >
                    {retryMutation.isPending ? "重试中..." : "重试解析"}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={reindexMutation.isPending || !canReindex}
                    onClick={() => reindexMutation.mutate()}
                  >
                    {reindexMutation.isPending ? "重建中..." : "重建索引"}
                  </Button>
                </div>
                {tagMessage ? <p className="m-0 text-sm text-emerald-700">{tagMessage}</p> : null}
                {tagError ? <p className="m-0 text-sm text-rose-700">{tagError}</p> : null}
                {indexActionMessage ? <p className="m-0 text-sm text-emerald-700">{indexActionMessage}</p> : null}
                {indexActionError ? <p className="m-0 text-sm text-rose-700">{indexActionError}</p> : null}
                {!canReindex ? (
                  <p className="m-0 text-xs text-slate-500">
                    {document.parse_status !== "success" ? "仅解析成功的文档支持重建索引。" : "当前索引任务仍在处理中。"}
                  </p>
                ) : null}
              </form>
              <div className="flex gap-3">
                <Button asChild variant="outline">
                  <Link id="detail-back-to-search" to="/search">返回搜索页</Link>
                </Button>
                <Button asChild variant="ghost">
                  <Link to="/">返回导入页</Link>
                </Button>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="实时状态" description="当前文档和关联任务状态会自动轮询更新。">
            <div className="grid gap-3 text-sm leading-6 text-slate-700">
              <article className="flex items-center justify-between gap-3">
                <span>文档解析状态</span>
                <Badge variant={parseStatusTone(document.parse_status)}>{parseStatusLabel(document.parse_status)}</Badge>
              </article>
              <article className="flex items-center justify-between gap-3">
                <span>索引状态</span>
                <Badge variant={indexStatusTone(document.index_status)}>{indexStatusLabel(document.index_status)}</Badge>
              </article>
              <article className="flex items-center justify-between gap-3">
                <span>引用可用</span>
                <Badge variant={isCitationReady(document.index_status, document.citation_ready) ? "success" : "warning"}>
                  {isCitationReady(document.index_status, document.citation_ready) ? "可跳回" : "待建立"}
                </Badge>
              </article>
              <article className="flex items-center justify-between gap-3">
                <span>跟踪任务</span>
                {job ? <Badge variant={jobStatusTone(job.status)}>{jobStatusLabel(job.status)}</Badge> : <span className="text-slate-500">当前没有跟踪中的任务</span>}
              </article>
              {job ? (
                <>
                  <article>任务 ID：{job.id}</article>
                  <article>尝试次数：{job.attempt}</article>
                  <article>任务诊断：{diagnosisLabel(job.diagnosis_code)}</article>
                  <article>事件引用：{job.incident_ref || "无"}</article>
                  <article>错误信息：{job.error_message || "无"}</article>
                </>
              ) : null}
              {document.upload ? (
                <>
                  <article>上传模式：{document.upload.upload_mode === "multipart" ? "分片上传" : "单对象上传"}</article>
                  <article>已上传分片：{document.upload.uploaded_part_count} / {document.upload.part_count ?? 1}</article>
                  <article>校验时间：{formatDateTime(document.upload.verified_at)}</article>
                </>
              ) : null}
            </div>
          </SectionCard>

          <SectionCard
            title="引用证据"
            description={
              isCitationReady(document.index_status, document.citation_ready)
                ? "每条证据都带有 chunk_id 和页面内锚点，可用于 citation jumpback。"
                : "索引尚未就绪时，只能展示证据状态，不能把当前文档当成稳定引用源。"
            }
          >
            {documentEvidenceQuery.isLoading ? (
              <p className="m-0 text-sm leading-6 text-slate-600">正在加载引用证据。</p>
            ) : documentEvidenceQuery.isError ? (
              <p className="m-0 text-sm leading-6 text-rose-700">
                {documentEvidenceQuery.error instanceof Error ? documentEvidenceQuery.error.message : "引用证据加载失败"}
              </p>
            ) : (documentEvidenceQuery.data?.items || []).length === 0 ? (
              <p className="m-0 text-sm leading-6 text-slate-600">
                当前没有可展示的引用证据，{indexStatusLabel(document.index_status)} 状态下建议先重建索引。
              </p>
            ) : (
              <div className="grid gap-3">
                {(documentEvidenceQuery.data?.items || []).map((item) => (
                  <article
                    className="grid gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-4 text-sm leading-6 text-slate-700"
                    id={`evidence-${item.chunk_id}`}
                    key={item.chunk_id}
                  >
                    <div className="flex flex-wrap items-start justify-between gap-3">
                      <div className="grid gap-1">
                        <strong className="text-slate-950">Chunk {item.chunk_index + 1}</strong>
                        <span className="text-xs uppercase tracking-[0.18em] text-slate-500">{item.chunk_id}</span>
                      </div>
                      <Badge variant="info">{item.page_ref || item.section_label || "无页面定位"}</Badge>
                    </div>
                    <p className="m-0 text-sm leading-6 text-slate-700">{item.quote_text}</p>
                    <p className="m-0 text-xs text-slate-500">定位：{describeEvidenceLocator(item)}</p>
                    <p className="m-0 text-xs text-slate-500">锚点：#{`evidence-${item.chunk_id}`}</p>
                  </article>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard title="处理时间线" description="按阶段展示上传、解析、OCR、抓取和搜索投影的处理证据。">
            {timelineQuery.isLoading ? (
              <p className="m-0 text-sm leading-6 text-slate-600">正在加载处理时间线。</p>
            ) : timelineItems.length === 0 ? (
              <p className="m-0 text-sm leading-6 text-slate-600">当前文档还没有可展示的处理事件。</p>
            ) : (
              <div className="grid gap-3">
                {timelineItems.map((item, index) => (
                  <article
                    className="grid gap-2 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3"
                    key={`${item.event_type}-${item.created_at}-${index}`}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <div className="grid gap-1">
                        <p className="m-0 text-sm font-medium text-slate-900">{timelineStageLabel(item)}</p>
                        <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">
                          {formatDateTime(item.created_at)}
                        </p>
                      </div>
                      <Badge variant={timelineStatusTone(item.status)}>{parseStatusLabel(item.status)}</Badge>
                    </div>
                    <p className="m-0 text-sm leading-6 text-slate-700">{item.summary}</p>
                    {item.diagnosis_code ? (
                      <p className="m-0 text-xs text-slate-500">诊断：{diagnosisLabel(item.diagnosis_code)}</p>
                    ) : null}
                  </article>
                ))}
              </div>
            )}
          </SectionCard>
        </div>
      </section>
    </PageShell>
  );
}

function timelineStageLabel(item: DocumentProcessingEventItem) {
  const stagePrefix = {
    upload: "上传阶段",
    parse: "解析阶段",
    ocr: "OCR 阶段",
    fetch: "抓取阶段",
    projection: "搜索投影",
    ops: "运维事件",
    index: "索引阶段"
  }[item.stage];

  return `${stagePrefix} · ${item.summary}`;
}

function timelineStatusTone(status: DocumentProcessingEventItem["status"]) {
  if (status === "success") {
    return "success" as const;
  }

  if (status === "failed") {
    return "warning" as const;
  }

  return "info" as const;
}
