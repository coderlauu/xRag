import { useEffect, useState, type FormEvent } from "react";
import { useQuery } from "@tanstack/react-query";
import { Link, useNavigate, useRouterState } from "@tanstack/react-router";
import { Badge, Button, Input, PageShell, SectionCard, Select, StatCard } from "@xrag/ui";
import { listDocuments } from "../../../lib/api";
import {
  buildDocumentsQuery,
  diagnosisLabel,
  formatDateTime,
  joinTags,
  normalizeSearchFilters,
  parseStatusLabel,
  parseStatusTone,
  serializeSearchFilters,
  sourceTypeLabel,
  splitTags
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
      eyebrow="Search"
      title="Find it again quickly"
      description="Keyword-first retrieval over title, content, tags, file name, and source metadata."
    >
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard label="Matches" value={String(total)} hint="From the current filter set" />
        <StatCard label="Page size" value={String(activeFilters.page_size)} hint="URL-driven pagination" />
        <StatCard label="Active page" value={String(activeFilters.page)} hint="Use the pager below" />
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(320px,0.85fr)_minmax(0,1.15fr)]">
        <SectionCard title="Search controls" description="Filters are persisted in the URL so the view can be shared and resumed.">
          <form className="grid gap-3" onSubmit={submitFilters}>
            <div className="grid gap-2">
              <Input
                aria-label="Search documents"
                placeholder="Search by title, content, tags, file name"
                value={draftFilters.q}
                onChange={(event) => setDraftFilters((current) => ({ ...current, q: event.target.value }))}
              />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <Select
                aria-label="Source type"
                value={draftFilters.source_type}
                onChange={(event) => setDraftFilters((current) => ({ ...current, source_type: event.target.value }))}
              >
                <option value="">All source types</option>
                <option value="text">Text</option>
                <option value="file">File</option>
                <option value="link">Link</option>
              </Select>
              <Select
                aria-label="Status"
                value={draftFilters.parse_status}
                onChange={(event) => setDraftFilters((current) => ({ ...current, parse_status: event.target.value }))}
              >
                <option value="">All statuses</option>
                <option value="pending">Pending</option>
                <option value="processing">Processing</option>
                <option value="success">Success</option>
                <option value="failed">Failed</option>
              </Select>
            </div>
            <div className="grid gap-3">
              <Input
                aria-label="Tags"
                placeholder="tags, separated, by commas"
                value={draftFilters.tags}
                onChange={(event) => setDraftFilters((current) => ({ ...current, tags: event.target.value }))}
              />
              <div className="grid gap-3 sm:grid-cols-2">
                <Input
                  aria-label="Date from"
                  type="datetime-local"
                  value={draftFilters.date_from}
                  onChange={(event) => setDraftFilters((current) => ({ ...current, date_from: event.target.value }))}
                />
                <Input
                  aria-label="Date to"
                  type="datetime-local"
                  value={draftFilters.date_to}
                  onChange={(event) => setDraftFilters((current) => ({ ...current, date_to: event.target.value }))}
                />
              </div>
              <div className="grid gap-3 sm:grid-cols-2">
                <Select
                  aria-label="Page size"
                  value={String(draftFilters.page_size)}
                  onChange={(event) =>
                    setDraftFilters((current) => ({
                      ...current,
                      page_size: Number.parseInt(event.target.value, 10) || 20
                    }))
                  }
                >
                  <option value="10">10 per page</option>
                  <option value="20">20 per page</option>
                  <option value="50">50 per page</option>
                </Select>
                <Button type="submit">Search</Button>
              </div>
            </div>
          </form>
        </SectionCard>

        <SectionCard
          title="Results"
          description={
            documentsQuery.isFetching
              ? "Refreshing documents from the API."
              : items.length === 0
                ? "No documents matched the current filters."
                : "Open any document to inspect the detail view."
          }
        >
          {items.length === 0 ? (
            <div className="grid gap-3">
              <p className="m-0 text-sm leading-6 text-slate-600">No results yet. Broaden the query or clear filters.</p>
              <Button
                type="button"
                variant="outline"
                onClick={() =>
                  void navigate({
                    to: "/search",
                    search: serializeSearchFilters({
                      q: "",
                      source_type: "",
                      parse_status: "",
                      tags: "",
                      date_from: "",
                      date_to: "",
                      page: 1,
                      page_size: 20
                    }) as never
                  })
                }
              >
                Clear filters
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
                        <span>{item.file_name || "Manual"}</span>
                        <span>{formatDateTime(item.imported_at)}</span>
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-2">
                      <Badge variant={parseStatusTone(item.parse_status)}>{parseStatusLabel(item.parse_status)}</Badge>
                      {item.upload_status ? <Badge variant="info">{item.upload_status}</Badge> : null}
                    </div>
                  </div>
                  <p className="m-0 text-sm leading-6 text-slate-600">{item.content_preview || "No preview available."}</p>
                  {item.tags.length > 0 ? <p className="m-0 text-xs text-slate-500">Tags: {joinTags(item.tags)}</p> : null}
                  {(item.diagnosis_code || item.latest_job_status) ? (
                    <div className="grid gap-1 text-xs text-slate-500">
                      {item.diagnosis_code ? <span>诊断：{diagnosisLabel(item.diagnosis_code)}</span> : null}
                      {item.latest_job_status ? <span>最近任务：{item.latest_job_status}</span> : null}
                    </div>
                  ) : null}
                </article>
              ))}
            </div>
          )}

          <div className="mt-4 flex flex-wrap items-center justify-between gap-3">
            <p className="m-0 text-sm text-slate-600">
              Showing {items.length} of {total} matches
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
                Previous
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
                Next
              </Button>
            </div>
          </div>
        </SectionCard>
      </section>
    </PageShell>
  );
}
