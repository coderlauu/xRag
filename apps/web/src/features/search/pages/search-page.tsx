import { useEffect, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import { Badge, Button, Input, PageShell, SectionCard, Select, StatCard } from "@xrag/ui";
import { listDocuments } from "../../../lib/api";
import {
  buildDocumentsQuery,
  diagnosisLabel,
  formatDateTime,
  jobStatusLabel,
  joinTags,
  normalizeSearchFilters,
  parseStatusLabel,
  parseStatusTone,
  serializeSearchFilters,
  sourceTypeLabel,
  splitTags,
  uploadStatusLabel
} from "../../../lib/document-state";

export function SearchPage() {
  const navigate = useNavigate();
  const searchJson = useRouterState({
    select: (state) => JSON.stringify(state.location.search ?? {})
  });
  const activeFilters = normalizeSearchFilters(JSON.parse(searchJson) as Record<string, unknown>);
  const [draftFilters, setDraftFilters] = useState(activeFilters);

  useEffect(() => {
    setDraftFilters(activeFilters);
  }, [searchJson]);

  const documentsQuery = useQuery({
    queryKey: ["documents", "search", activeFilters],
    queryFn: () => listDocuments(buildDocumentsQuery(activeFilters)),
    refetchOnWindowFocus: false
  });

  const total = documentsQuery.data?.total || 0;
  const items = documentsQuery.data?.items || [];

  const submitFilters = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const nextFilters = {
      ...draftFilters,
      q: draftFilters.q.trim(),
      tags: splitTags(draftFilters.tags).join(", "),
      page: 1
    };

    void navigate({
      to: "/search",
      search: serializeSearchFilters(nextFilters) as never
    });
  };

  return (
    <PageShell
      eyebrow="检索"
      title="快速重新找到文档"
      description="按标题、正文、标签、文件名和来源元数据做关键词检索。"
    >
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard label="匹配结果" value={String(total)} hint="基于当前筛选条件" />
        <StatCard label="每页数量" value={String(activeFilters.page_size)} hint="分页状态写入 URL" />
        <StatCard label="当前页" value={String(activeFilters.page)} hint="可直接翻页分享" />
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(320px,0.85fr)_minmax(0,1.15fr)]">
        <SectionCard title="筛选条件" description="筛选条件会写入 URL，便于分享、恢复和回放排查路径。">
          <form className="grid gap-3" onSubmit={submitFilters}>
            <div className="grid gap-2">
              <Input
                id="search-query"
                aria-label="搜索文档"
                placeholder="按标题、正文、标签或文件名搜索"
                value={draftFilters.q}
                onChange={(event) => setDraftFilters((current) => ({ ...current, q: event.target.value }))}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
              <Select
                aria-label="来源类型"
                value={draftFilters.source_type}
                onChange={(event) => setDraftFilters((current) => ({ ...current, source_type: event.target.value }))}
              >
                <option value="">全部来源</option>
                <option value="text">文本</option>
                <option value="file">文件</option>
                <option value="pdf">PDF</option>
                <option value="link">链接</option>
              </Select>
              <Select
                aria-label="OCR 状态"
                value={draftFilters.ocr_status}
                onChange={(event) => setDraftFilters((current) => ({ ...current, ocr_status: event.target.value }))}
              >
                <option value="">全部 OCR 状态</option>
                <option value="not_required">无需 OCR</option>
                <option value="queued">等待 OCR</option>
                <option value="processing">OCR 中</option>
                <option value="success">OCR 成功</option>
                <option value="failed">OCR 失败</option>
              </Select>
              <Select
                aria-label="解析状态"
                value={draftFilters.parse_status}
                onChange={(event) => setDraftFilters((current) => ({ ...current, parse_status: event.target.value }))}
              >
                <option value="">全部状态</option>
                <option value="pending">等待解析</option>
                <option value="processing">解析中</option>
                <option value="success">解析成功</option>
                <option value="failed">解析失败</option>
              </Select>
              <Select
                aria-label="上传状态"
                value={draftFilters.upload_status}
                onChange={(event) => setDraftFilters((current) => ({ ...current, upload_status: event.target.value }))}
              >
                <option value="">全部上传状态</option>
                <option value="draft">草稿</option>
                <option value="initiated">已初始化</option>
                <option value="uploading">上传中</option>
                <option value="verifying">校验中</option>
                <option value="uploaded">已上传</option>
                <option value="failed">上传失败</option>
              </Select>
              <Select
                aria-label="诊断码"
                value={draftFilters.diagnosis_code}
                onChange={(event) => setDraftFilters((current) => ({ ...current, diagnosis_code: event.target.value }))}
              >
                <option value="">全部诊断</option>
                <option value="storage_presign_failed">存储签名失败</option>
                <option value="multipart_part_failed">分片上传失败</option>
                <option value="upload_complete_invalid_parts">上传完成校验失败</option>
                <option value="object_missing_on_complete">对象校验失败</option>
                <option value="pdf_parse_runtime_error">PDF 解析器运行时异常</option>
                <option value="pdf_parse_unsupported">PDF 暂不支持解析</option>
                <option value="pdf_parse_timeout">PDF 解析超时</option>
                <option value="pdf_parse_empty_text">PDF 未提取到文本</option>
                <option value="ocr_runtime_error">OCR 运行时异常</option>
                <option value="ocr_timeout">OCR 超时</option>
                <option value="ocr_no_text_detected">OCR 未识别到有效文本</option>
                <option value="link_fetch_timeout">链接抓取超时</option>
                <option value="link_fetch_blocked">链接抓取被阻止</option>
                <option value="link_extract_empty">链接正文提取为空</option>
                <option value="link_invalid_url">链接地址无效</option>
                <option value="search_projection_stale">搜索投影需要刷新</option>
                <option value="queue_backlog">解析任务入队失败</option>
              </Select>
            </div>
            <div className="grid gap-3">
              <Input
                aria-label="标签"
                placeholder="标签，用逗号分隔"
                value={draftFilters.tags}
                onChange={(event) => setDraftFilters((current) => ({ ...current, tags: event.target.value }))}
              />
              <div className="grid gap-3 sm:grid-cols-2">
                <Input
                  aria-label="开始时间"
                  type="datetime-local"
                  value={draftFilters.date_from}
                  onChange={(event) => setDraftFilters((current) => ({ ...current, date_from: event.target.value }))}
                />
                <Input
                  aria-label="结束时间"
                  type="datetime-local"
                  value={draftFilters.date_to}
                  onChange={(event) => setDraftFilters((current) => ({ ...current, date_to: event.target.value }))}
                />
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <Select
                  aria-label="每页数量"
                  value={String(draftFilters.page_size)}
                  onChange={(event) =>
                    setDraftFilters((current) => ({
                      ...current,
                      page_size: Number.parseInt(event.target.value, 10) || 20
                    }))
                  }
                >
                  <option value="10">每页 10 条</option>
                  <option value="20">每页 20 条</option>
                  <option value="50">每页 50 条</option>
                </Select>
                <Button id="search-submit" type="submit">开始检索</Button>
              </div>
            </div>
          </form>
        </SectionCard>

        <SectionCard
          title="检索结果"
          description={
            documentsQuery.isFetching
              ? "正在刷新接口数据。"
              : items.length === 0
                ? "当前筛选条件下没有匹配文档。"
                : "点开任意文档，可继续查看详情、失败诊断和重试状态。"
          }
        >
          {items.length === 0 ? (
            <div className="grid gap-3">
              <p className="m-0 text-sm leading-6 text-slate-600">当前没有结果，可以放宽关键词或清空筛选。</p>
              <Button
                type="button"
                variant="outline"
                onClick={() =>
                  void navigate({
                    to: "/search",
                    search: serializeSearchFilters({
                      q: "",
                      source_type: "",
                      ocr_status: "",
                      parse_status: "",
                      upload_status: "",
                      diagnosis_code: "",
                      tags: "",
                      date_from: "",
                      date_to: "",
                      page: 1,
                      page_size: 20
                    }) as never
                  })
                }
              >
                清空筛选
              </Button>
            </div>
          ) : (
            <div className="grid gap-3">
              {items.map((item) => (
                <article
                  className="grid gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-4 text-sm leading-6 text-slate-700"
                  key={item.id}
                >
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="grid gap-1">
                      <Link
                        className="text-lg font-semibold tracking-[-0.03em] text-slate-950 underline-offset-4 hover:underline"
                        to="/detail/$documentId"
                        params={{ documentId: item.id }}
                      >
                        {item.title}
                      </Link>
                      <div className="flex flex-wrap gap-2 text-xs uppercase tracking-[0.18em] text-slate-500">
                        <span>{sourceTypeLabel(item.source_type)}</span>
                        <span>{item.file_name || "手动输入"}</span>
                        <span>{formatDateTime(item.imported_at)}</span>
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Badge variant={parseStatusTone(item.parse_status)}>{parseStatusLabel(item.parse_status)}</Badge>
                      {item.upload_status ? <Badge variant="info">{uploadStatusLabel(item.upload_status)}</Badge> : null}
                    </div>
                  </div>
                  <p className="m-0 text-sm leading-6 text-slate-600">{item.content_preview || "暂无预览内容。"}</p>
                  {item.tags.length > 0 ? <p className="m-0 text-xs text-slate-500">标签：{joinTags(item.tags)}</p> : null}
                  {(item.diagnosis_code || item.latest_job_status || item.page_count || item.parser_name) ? (
                    <div className="grid gap-1 text-xs text-slate-500">
                      {item.diagnosis_code ? <span>诊断：{diagnosisLabel(item.diagnosis_code)}</span> : null}
                      {item.latest_job_status ? <span>最近任务：{jobStatusLabel(item.latest_job_status)}</span> : null}
                      {item.page_count ? <span>页数：{item.page_count} 页</span> : null}
                      {item.parser_name ? <span>解析器：{item.parser_name}</span> : null}
                    </div>
                  ) : null}
                </article>
              ))}
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
            <p className="m-0 text-sm text-slate-600">
              当前显示 {items.length} / {total} 条结果
            </p>
            <div className="flex gap-2">
              <Button
                type="button"
                variant="outline"
                disabled={activeFilters.page <= 1}
                onClick={() =>
                  void navigate({
                    to: "/search",
                    search: serializeSearchFilters({ ...activeFilters, page: Math.max(1, activeFilters.page - 1) }) as never
                  })
                }
              >
                上一页
              </Button>
              <Button
                type="button"
                variant="outline"
                disabled={items.length < activeFilters.page_size}
                onClick={() =>
                  void navigate({
                    to: "/search",
                    search: serializeSearchFilters({ ...activeFilters, page: activeFilters.page + 1 }) as never
                  })
                }
              >
                下一页
              </Button>
            </div>
          </div>
        </SectionCard>
      </section>
    </PageShell>
  );
}
