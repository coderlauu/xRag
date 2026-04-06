import { useEffect, useState, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useRouterState } from "@tanstack/react-router";
import { Badge, Button, PageShell, SectionCard, StatCard, Textarea, Input } from "@xrag/ui";
import { getDocument, getJob, retryDocument, updateDocumentTags } from "../../../lib/api";
import {
  diagnosisLabel,
  formatDateTime,
  formatRelativeTime,
  isJobActive,
  isParseActive,
  joinTags,
  jobStatusLabel,
  jobStatusTone,
  parseStatusLabel,
  parseStatusTone,
  splitTags,
  sourceTypeLabel,
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
  const [trackedJobId, setTrackedJobId] = useState<string | null>(loadJobId(documentId));

  useEffect(() => {
    setTrackedJobId(loadJobId(documentId));
    setTagError(null);
    setTagMessage(null);
  }, [documentId]);

  const documentQuery = useQuery({
    queryKey: ["document", documentId],
    queryFn: () => getDocument(documentId),
    enabled: hasValidDocumentId,
    refetchInterval: (query) => (query.state.data && isParseActive(query.state.data.parse_status) ? 3000 : false)
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

  const updateTagsMutation = useMutation({
    mutationFn: (tags: string[]) => updateDocumentTags(documentId, { tags }),
    onSuccess: async (updatedDocument) => {
      setTagError(null);
      setTagMessage("Tags updated.");
      setTagInput(joinTags(updatedDocument.tags));
      await queryClient.invalidateQueries({ queryKey: ["document", documentId] });
      await queryClient.invalidateQueries({ queryKey: ["documents"] });
    },
    onError: (error) => {
      setTagError(error instanceof Error ? error.message : "Failed to update tags");
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
      setTagError(error instanceof Error ? error.message : "Failed to retry document");
    }
  });

  if (!hasValidDocumentId) {
    return (
      <PageShell eyebrow="Detail" title="Document detail" description="No document id was found in the route.">
        <SectionCard title="Navigation error" description="Go back to the inbox or search view.">
          <div className="flex gap-3">
            <Button asChild variant="outline">
              <Link to="/">Back to inbox</Link>
            </Button>
            <Button asChild>
              <Link to="/search">Back to search</Link>
            </Button>
          </div>
        </SectionCard>
      </PageShell>
    );
  }

  if (documentQuery.isLoading) {
    return (
      <PageShell eyebrow="Detail" title="Document detail" description="Loading document...">
        <SectionCard title="Loading" description="Fetching the document from the API.">
          <p className="m-0 text-sm leading-6 text-slate-600">Loading detail view.</p>
        </SectionCard>
      </PageShell>
    );
  }

  if (documentQuery.isError || !documentQuery.data) {
    return (
      <PageShell eyebrow="Detail" title="Document detail" description="The requested document could not be loaded.">
        <SectionCard title="Unable to load" description="The document may not exist or the API may be unavailable.">
          <div className="grid gap-3">
            <p className="m-0 text-sm leading-6 text-slate-600">
              {documentQuery.error instanceof Error ? documentQuery.error.message : "Unknown error"}
            </p>
            <div className="flex gap-3">
              <Button asChild variant="outline">
                <Link to="/">Back to inbox</Link>
              </Button>
              <Button asChild>
                <Link to="/search">Back to search</Link>
              </Button>
            </div>
          </div>
        </SectionCard>
      </PageShell>
    );
  }

  const document = documentQuery.data;
  const job = trackedJobQuery.data;

  return (
    <PageShell
      eyebrow="Detail"
      title={document.title}
      description="Live document detail with tag editing, retry, and status polling."
    >
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard label="Parse status" value={parseStatusLabel(document.parse_status)} hint="Polled from the API" />
        <StatCard label="Source" value={sourceTypeLabel(document.source_type)} hint={document.file_name || "Manual input"} />
        <StatCard label="Imported" value={formatRelativeTime(document.imported_at)} hint={formatDateTime(document.imported_at)} />
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
        <SectionCard title="Content" description="Raw and cleaned content from the document record.">
          <div className="grid gap-5">
            <div className="grid gap-2">
              <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">Clean content</p>
              <pre className="m-0 overflow-x-auto whitespace-pre-wrap rounded-2xl bg-slate-950 px-4 py-4 text-sm leading-7 text-slate-50">
                {document.content_clean || document.content_raw || "No content available."}
              </pre>
            </div>
            {document.content_raw && document.content_raw !== document.content_clean ? (
              <div className="grid gap-2">
                <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">Raw content</p>
                <pre className="m-0 overflow-x-auto whitespace-pre-wrap rounded-2xl border border-slate-200 bg-slate-50 px-4 py-4 text-sm leading-7 text-slate-700">
                  {document.content_raw}
                </pre>
              </div>
            ) : null}
          </div>
        </SectionCard>

        <div className="grid gap-6">
          <SectionCard title="Metadata" description="Update tags and inspect the source metadata.">
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
              <article>Tags: {document.tags.length > 0 ? joinTags(document.tags) : "None"}</article>
              <article>File name: {document.file_name || "Manual input"}</article>
              <article>Source URL: {document.source_url || "N/A"}</article>
              <article>Mime type: {document.mime_type || "N/A"}</article>
              <article>Imported: {formatDateTime(document.imported_at)}</article>
              <article>Upload status: {uploadStatusLabel(document.upload_status)}</article>
              <article>Diagnosis: {diagnosisLabel(document.diagnosis_code)}</article>
              <article>Diagnosis summary: {document.diagnosis_summary || "None"}</article>
              <article>Parser: {document.parser_name || "N/A"}</article>
              <article>Pages: {document.page_count ?? "N/A"}</article>
              <article>Incident ref: {document.last_incident_ref || "N/A"}</article>
            </div>
          </SectionCard>

          <SectionCard title="Actions" description="Edit tags, retry parse, or go back to search.">
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
                  aria-label="Tags"
                  value={tagInput}
                  onChange={(event) => setTagInput(event.target.value)}
                  placeholder="RAG, MVP, retrieval"
                />
                <div className="flex flex-wrap gap-3">
                  <Button type="submit" disabled={updateTagsMutation.isPending}>
                    {updateTagsMutation.isPending ? "Saving..." : "Save tags"}
                  </Button>
                  <Button
                    type="button"
                    variant="outline"
                    disabled={retryMutation.isPending}
                    onClick={() => retryMutation.mutate()}
                  >
                    {retryMutation.isPending ? "Retrying..." : "Retry parse"}
                  </Button>
                </div>
                {tagMessage ? <p className="m-0 text-sm text-emerald-700">{tagMessage}</p> : null}
                {tagError ? <p className="m-0 text-sm text-rose-700">{tagError}</p> : null}
              </form>
              <div className="flex gap-3">
                <Button asChild variant="outline">
                  <Link to="/search">Back to search</Link>
                </Button>
                <Button asChild variant="ghost">
                  <Link to="/">Back to inbox</Link>
                </Button>
              </div>
            </div>
          </SectionCard>

          <SectionCard title="Live status" description="The current document and tracked job state are polled automatically.">
            <div className="grid gap-3 text-sm leading-6 text-slate-700">
              <article className="flex items-center justify-between gap-3">
                <span>Document parse status</span>
                <Badge variant={parseStatusTone(document.parse_status)}>{parseStatusLabel(document.parse_status)}</Badge>
              </article>
              <article className="flex items-center justify-between gap-3">
                <span>Tracked job</span>
                {job ? <Badge variant={jobStatusTone(job.status)}>{jobStatusLabel(job.status)}</Badge> : <span className="text-slate-500">No job tracked yet</span>}
              </article>
              {job ? (
                <>
                  <article>Job id: {job.id}</article>
                  <article>Attempts: {job.attempt}</article>
                  <article>Job diagnosis: {diagnosisLabel(job.diagnosis_code)}</article>
                  <article>Incident ref: {job.incident_ref || "None"}</article>
                  <article>Error: {job.error_message || "None"}</article>
                </>
              ) : null}
              {document.upload ? (
                <>
                  <article>Upload mode: {document.upload.upload_mode}</article>
                  <article>Uploaded parts: {document.upload.uploaded_part_count} / {document.upload.part_count ?? 1}</article>
                  <article>Verified at: {formatDateTime(document.upload.verified_at)}</article>
                </>
              ) : null}
            </div>
          </SectionCard>
        </div>
      </section>
    </PageShell>
  );
}
