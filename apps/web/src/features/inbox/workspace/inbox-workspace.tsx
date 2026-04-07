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
        title="文本导入"
        description="直接保存一条文本笔记，并跳转到新建完成的详情页。"
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
              标题
            </label>
            <Input
              id="inbox-title"
              value={textForm.title}
              onChange={handleTextChange("title")}
              placeholder="例如：RAG 产品 MVP 会议纪要"
            />
          </div>
          <div className="grid gap-2">
            <label className="text-sm font-medium text-slate-800" htmlFor="inbox-content">
              内容
            </label>
            <Textarea
              id="inbox-content"
              rows={8}
              value={textForm.content}
              onChange={handleTextChange("content")}
              placeholder="在这里粘贴或整理原始内容。"
            />
          </div>
          <div className="grid gap-2">
            <label className="text-sm font-medium text-slate-800" htmlFor="inbox-tags">
              标签
            </label>
            <Input
              id="inbox-tags"
              value={textForm.tags}
              onChange={handleTextChange("tags")}
              placeholder="知识库, MVP, 检索"
            />
          </div>
          <div className="flex flex-wrap items-center gap-3">
            <Button id="inbox-save-note" type="submit" disabled={createTextMutation.isPending}>
              {createTextMutation.isPending ? "保存中..." : "保存笔记"}
            </Button>
            {textError ? <span className="text-sm text-rose-700">{textError}</span> : null}
          </div>
        </form>
      </SectionCard>

      <div className="grid gap-6">
        <SectionCard
          title="上传队列"
          description="直传对象存储，完成校验后进入解析队列。"
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
                标题
              </label>
              <Input
                id="upload-title"
                value={uploadForm.title}
                onChange={handleUploadChange("title")}
                placeholder="例如：访谈纪要 PDF"
              />
            </div>
            <div className="grid gap-2">
              <label className="text-sm font-medium text-slate-800" htmlFor="upload-tags">
                标签
              </label>
              <Input
                id="upload-tags"
                value={uploadForm.tags}
                onChange={handleUploadChange("tags")}
                placeholder="访谈, 笔记, 调研"
              />
            </div>
            <div className="grid gap-2">
              <label className="text-sm font-medium text-slate-800" htmlFor="upload-file">
                文件
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
              <Button id="upload-submit" type="submit" disabled={uploadMutation.isPending}>
                {uploadMutation.isPending ? "上传中..." : "上传文件"}
              </Button>
              {uploadMessage ? <span className="text-sm text-emerald-700">{uploadMessage}</span> : null}
              {uploadError ? <span className="text-sm text-rose-700">{uploadError}</span> : null}
            </div>
            <p className="m-0 text-sm leading-6 text-slate-600">
              当前支持：txt、md、文本型 PDF。上传会先申请签名地址，再完成对象校验并进入解析队列。
            </p>
          </form>
        </SectionCard>

        <SectionCard title="收件箱状态" description="基于当前文档集合实时汇总状态数量。">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <MetricCard label="文档总数" value={String(counts.total)} hint="当前收件箱规模" />
            <MetricCard label="等待解析" value={String(counts.pending)} hint="尚未进入成功态" />
            <MetricCard label="处理中" value={String(counts.processing)} hint="排队中或解析中" />
            <MetricCard label="失败" value={String(counts.failed)} hint="需要人工查看" tone="warning" />
          </div>
        </SectionCard>

        <SectionCard title="最近导入" description="展示收件箱里最新进入的文档。">
          {recentDocuments.length === 0 ? (
            <p className="m-0 text-sm leading-6 text-slate-600">当前还没有文档。</p>
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
                  <p className="m-0 text-sm leading-6 text-slate-600">{document.content_preview || "暂无预览内容。"}</p>
                  {document.tags.length > 0 ? <p className="m-0 text-xs text-slate-500">标签：{joinTags(document.tags)}</p> : null}
                </article>
              ))}
            </div>
          )}
        </SectionCard>

        <SectionCard title="处理中任务" description="仍处在解析生命周期中的文档会显示在这里。">
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
                      导入时间 {formatDateTime(item.imported_at)}
                    </p>
                  </article>
                ))}
            </div>
          ) : (
            <p className="m-0 text-sm leading-6 text-slate-600">当前没有正在处理的文档。</p>
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
