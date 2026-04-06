import { useState, type ChangeEvent, type FormEvent } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { Button, Input, SectionCard, Textarea, Badge } from "@xrag/ui";
import { completeUpload, completeUploadPart, createTextDocument, getUploadPartUrls, initiateUpload, listDocuments } from "../../../lib/api";
import {
  formatDateTime,
  formatRelativeTime,
  isParseActive,
  joinTags,
  parseStatusLabel,
  parseStatusTone,
  splitTags,
  sourceTypeLabel
} from "../../../lib/document-state";
import { rememberJobId } from "../../../lib/job-store";
import { sha256Hex } from "../../../lib/sha256";

interface InboxWorkspaceProps {
  overviewQueryKey: readonly unknown[];
}

interface TextFormState {
  title: string;
  content: string;
  tags: string;
}

interface UploadFormState {
  title: string;
  tags: string;
  file: File | null;
}

export function InboxWorkspace({ overviewQueryKey }: InboxWorkspaceProps) {
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [textForm, setTextForm] = useState<TextFormState>({
    title: "",
    content: "",
    tags: ""
  });
  const [uploadForm, setUploadForm] = useState<UploadFormState>({
    title: "",
    tags: "",
    file: null
  });
  const [textError, setTextError] = useState<string | null>(null);
  const [uploadError, setUploadError] = useState<string | null>(null);
  const [uploadMessage, setUploadMessage] = useState<string | null>(null);

  const overviewQuery = useQuery({
    queryKey: overviewQueryKey,
    queryFn: () => listDocuments({ page_size: 100 }),
    refetchInterval: 15_000
  });

  const documents = overviewQuery.data?.items || [];
  const recentDocuments = documents.slice(0, 5);
  const counts = {
    total: overviewQuery.data?.total || 0,
    pending: documents.filter((item) => item.parse_status === "pending").length,
    processing: documents.filter((item) => item.parse_status === "processing").length,
    failed: documents.filter((item) => item.parse_status === "failed").length
  };

  const createTextMutation = useMutation({
    mutationFn: createTextDocument,
    onSuccess: async (result) => {
      setTextError(null);
      queryClient.invalidateQueries({ queryKey: overviewQueryKey });
      await navigate({
        to: "/detail/$documentId",
        params: {
          documentId: result.id
        }
      });
    },
    onError: (error) => {
      setTextError(error instanceof Error ? error.message : "Failed to create document");
    }
  });

  const uploadMutation = useMutation({
    mutationFn: async () => {
      if (!uploadForm.file) {
        throw new Error("Please choose a file first.");
      }

      const file = uploadForm.file;
      const checksum = await sha256Hex(file);
      const initiate = await initiateUpload({
        file_name: file.name,
        mime_type: file.type || "application/octet-stream",
        file_size: file.size,
        checksum_sha256: checksum
      });

      if (initiate.upload_mode === "single") {
        if (!initiate.upload_url) {
          throw new Error("上传会话缺少单文件上传地址。");
        }

        const uploadResponse = await fetch(initiate.upload_url, {
          method: "PUT",
          headers: {
            ...(initiate.headers || {}),
            "content-type": file.type || "application/octet-stream"
          },
          body: file
        });

        if (!uploadResponse.ok) {
          throw new Error(`上传失败，状态码 ${uploadResponse.status}`);
        }
      } else {
        if (!initiate.part_count || !initiate.part_size_bytes) {
          throw new Error("分片上传会话缺少分片参数。");
        }

        const partNumbers = Array.from({ length: initiate.part_count }, (_, index) => index + 1);
        const partUrlResponse = await getUploadPartUrls(initiate.upload_id, {
          part_numbers: partNumbers
        });
        const completedParts: Array<{ part_number: number; etag: string }> = [];

        for (const part of partUrlResponse.parts) {
          const start = (part.part_number - 1) * initiate.part_size_bytes;
          const end = Math.min(start + initiate.part_size_bytes, file.size);
          const chunk = file.slice(start, end);
          const uploadResponse = await fetch(part.upload_url, {
            method: "PUT",
            headers: part.headers,
            body: chunk
          });

          if (!uploadResponse.ok) {
            throw new Error(`分片 ${part.part_number} 上传失败，状态码 ${uploadResponse.status}`);
          }

          const etag = uploadResponse.headers.get("etag");
          if (!etag) {
            throw new Error(`分片 ${part.part_number} 未返回 etag。`);
          }

          await completeUploadPart(initiate.upload_id, part.part_number, {
            etag,
            size_bytes: chunk.size
          });

          completedParts.push({
            part_number: part.part_number,
            etag
          });
        }

        return completeUpload(initiate.upload_id, {
          title: uploadForm.title,
          tags: splitTags(uploadForm.tags),
          checksum_sha256: checksum,
          parts: completedParts
        });
      }

      return completeUpload(initiate.upload_id, {
        title: uploadForm.title,
        tags: splitTags(uploadForm.tags),
        checksum_sha256: checksum
      });
    },
    onSuccess: async (result) => {
      setUploadError(null);
      setUploadMessage("Upload completed and queued for parsing.");
      queryClient.invalidateQueries({ queryKey: overviewQueryKey });
      rememberJobId(result.document_id, result.job_id);
      await navigate({
        to: "/detail/$documentId",
        params: {
          documentId: result.document_id
        }
      });
    },
    onError: (error) => {
      setUploadError(error instanceof Error ? error.message : "Failed to upload file");
    }
  });

  const handleTextChange = (field: keyof TextFormState) => (event: ChangeEvent<HTMLInputElement | HTMLTextAreaElement>) => {
    const value = event.target.value;
    setTextForm((current) => ({ ...current, [field]: value }));
  };

  const handleUploadChange = (field: keyof UploadFormState) => (event: ChangeEvent<HTMLInputElement>) => {
    const value = event.target.value;
    setUploadForm((current) => ({ ...current, [field]: value }));
  };

  return (
    <section className="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
      <SectionCard
        title="Text capture"
        description="Save a note directly to the inbox and jump to the created detail view."
      >
        <form
          className="grid gap-4"
          onSubmit={(event: FormEvent<HTMLFormElement>) => {
            event.preventDefault();
            setTextError(null);
            createTextMutation.mutate({
              title: textForm.title,
              content: textForm.content,
              tags: splitTags(textForm.tags)
            });
          }}
        >
          <div className="grid gap-2">
            <label className="text-sm font-medium text-slate-800" htmlFor="inbox-title">
              Title
            </label>
            <Input
              id="inbox-title"
              value={textForm.title}
              onChange={handleTextChange("title")}
              placeholder="e.g. RAG product MVP notes"
            />
          </div>
          <div className="grid gap-2">
            <label className="text-sm font-medium text-slate-800" htmlFor="inbox-content">
              Content
            </label>
            <Textarea
              id="inbox-content"
              rows={8}
              value={textForm.content}
              onChange={handleTextChange("content")}
              placeholder="Paste or draft the raw content here."
            />
          </div>
          <div className="grid gap-2">
            <label className="text-sm font-medium text-slate-800" htmlFor="inbox-tags">
              Tags
            </label>
            <Input
              id="inbox-tags"
              value={textForm.tags}
              onChange={handleTextChange("tags")}
              placeholder="RAG, MVP, retrieval"
            />
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button type="submit" disabled={createTextMutation.isPending}>
              {createTextMutation.isPending ? "Saving..." : "Save note"}
            </Button>
            {textError ? <span className="text-sm text-rose-700">{textError}</span> : null}
          </div>
        </form>
      </SectionCard>

      <div className="grid gap-6">
        <SectionCard
          title="Upload queue"
          description="Direct-to-object-storage upload with checksum verification and parse enqueue."
        >
          <form
            className="grid gap-4"
            onSubmit={(event: FormEvent<HTMLFormElement>) => {
              event.preventDefault();
              setUploadError(null);
              setUploadMessage(null);
              uploadMutation.mutate();
            }}
          >
            <div className="grid gap-2">
              <label className="text-sm font-medium text-slate-800" htmlFor="upload-title">
                Title
              </label>
              <Input
                id="upload-title"
                value={uploadForm.title}
                onChange={handleUploadChange("title")}
                placeholder="e.g. Interview notes PDF"
              />
            </div>
            <div className="grid gap-2">
              <label className="text-sm font-medium text-slate-800" htmlFor="upload-tags">
                Tags
              </label>
              <Input
                id="upload-tags"
                value={uploadForm.tags}
                onChange={handleUploadChange("tags")}
                placeholder="interview, notes, research"
              />
            </div>
            <div className="grid gap-2">
              <label className="text-sm font-medium text-slate-800" htmlFor="upload-file">
                File
              </label>
              <Input
                id="upload-file"
                type="file"
                accept=".txt,.md,.pdf,text/plain,text/markdown,application/pdf"
                onChange={(event) => {
                  const file = event.target.files?.[0] || null;
                  setUploadForm((current) => ({ ...current, file }));
                }}
              />
            </div>
            <div className="flex flex-wrap items-center gap-3">
              <Button type="submit" disabled={uploadMutation.isPending}>
                {uploadMutation.isPending ? "Uploading..." : "Upload file"}
              </Button>
              {uploadMessage ? <span className="text-sm text-emerald-700">{uploadMessage}</span> : null}
              {uploadError ? <span className="text-sm text-rose-700">{uploadError}</span> : null}
            </div>
            <p className="m-0 text-sm leading-6 text-slate-600">
              Supported: txt, md, text-based pdf. Uploads are signed, then completed into a queued parse job.
            </p>
          </form>
        </SectionCard>

        <SectionCard title="Inbox status" description="Live counts from the current document set.">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="Total documents" value={String(counts.total)} hint="Current inbox size" />
            <MetricCard label="Pending" value={String(counts.pending)} hint="Awaiting parse" />
            <MetricCard label="Processing" value={String(counts.processing)} hint="Queued or active" />
            <MetricCard label="Failed" value={String(counts.failed)} hint="Needs review" tone="warning" />
          </div>
        </SectionCard>

        <SectionCard title="Recent imports" description="Newest documents in the inbox.">
          {recentDocuments.length === 0 ? (
            <p className="m-0 text-sm leading-6 text-slate-600">No documents yet.</p>
          ) : (
            <div className="grid gap-3">
              {recentDocuments.map((document) => (
                <article
                  className="grid gap-3 rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700"
                  key={document.id}
                >
                  <div className="flex flex-wrap items-center justify-between gap-3">
                    <Link
                      className="font-medium text-slate-950 underline-offset-4 hover:underline"
                      to="/detail/$documentId"
                      params={{ documentId: document.id }}
                    >
                      {document.title}
                    </Link>
                    <Badge variant={parseStatusTone(document.parse_status)}>{parseStatusLabel(document.parse_status)}</Badge>
                  </div>
                  <div className="flex flex-wrap gap-2 text-xs uppercase tracking-[0.18em] text-slate-500">
                    <span>{sourceTypeLabel(document.source_type)}</span>
                    <span>{formatRelativeTime(document.imported_at)}</span>
                  </div>
                  <p className="m-0 text-sm leading-6 text-slate-600">{document.content_preview || "No preview available."}</p>
                  {document.tags.length > 0 ? <p className="m-0 text-xs text-slate-500">Tags: {joinTags(document.tags)}</p> : null}
                </article>
              ))}
            </div>
          )}
        </SectionCard>

        <SectionCard title="Processing jobs" description="Documents still moving through the parse lifecycle.">
          {overviewQuery.data?.items.some((item) => isParseActive(item.parse_status)) ? (
            <div className="grid gap-3">
              {overviewQuery.data.items
                .filter((item) => isParseActive(item.parse_status))
                .map((item) => (
                  <article
                    className="rounded-2xl border border-slate-200 bg-white/80 px-4 py-3 text-sm leading-6 text-slate-700"
                    key={item.id}
                  >
                    <div className="flex flex-wrap items-center justify-between gap-3">
                      <Link className="font-medium text-slate-950 underline-offset-4 hover:underline" to="/detail/$documentId" params={{ documentId: item.id }}>
                        {item.title}
                      </Link>
                      <Badge variant={parseStatusTone(item.parse_status)}>{parseStatusLabel(item.parse_status)}</Badge>
                    </div>
                    <p className="m-0 text-xs uppercase tracking-[0.18em] text-slate-500">
                      Imported {formatDateTime(item.imported_at)}
                    </p>
                  </article>
                ))}
            </div>
          ) : (
            <p className="m-0 text-sm leading-6 text-slate-600">No documents are currently processing.</p>
          )}
        </SectionCard>
      </div>
    </section>
  );
}

function MetricCard({
  label,
  value,
  hint,
  tone = "default"
}: {
  label: string;
  value: string;
  hint: string;
  tone?: "default" | "warning";
}) {
  return (
    <article
      className={
        tone === "warning"
          ? "grid gap-2 rounded-[22px] border border-amber-200/80 bg-amber-50/80 p-5 shadow-sm"
          : "grid gap-2 rounded-[22px] border border-sky-200/80 bg-linear-to-br from-sky-50 to-white p-5 shadow-sm"
      }
    >
      <Badge variant={tone === "warning" ? "warning" : "info"}>{label}</Badge>
      <strong className="text-3xl font-semibold tracking-[-0.05em] text-slate-950">{value}</strong>
      <span className="text-sm leading-6 text-slate-600">{hint}</span>
    </article>
  );
}
