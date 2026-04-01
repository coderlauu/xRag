import { useQuery } from "@tanstack/react-query";
import { PageShell, SectionCard, StatCard } from "@xrag/ui";
import { listDocuments } from "../../../lib/api";
import { formatRelativeTime, isParseActive } from "../../../lib/document-state";
import { InboxWorkspace } from "../workspace/inbox-workspace";

const inboxOverviewQueryKey = ["documents", "inbox-overview"] as const;

export function InboxPage() {
  const overviewQuery = useQuery({
    queryKey: inboxOverviewQueryKey,
    queryFn: () => listDocuments({ page_size: 100 }),
    refetchInterval: 15_000
  });

  const items = overviewQuery.data?.items || [];
  const total = overviewQuery.data?.total || 0;
  const processing = items.filter((item) => item.parse_status === "processing").length;
  const failed = items.filter((item) => item.parse_status === "failed").length;
  const recent = items[0];

  return (
    <PageShell
      eyebrow="Inbox"
      title="Capture first, organize later"
      description="Create documents directly, upload files, and track parse state in one place."
    >
      <section aria-label="Inbox metrics" className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Total documents" value={String(total)} hint="Live from the API" />
        <StatCard label="Processing" value={String(processing)} hint="Queued or active" />
        <StatCard label="Failed" value={String(failed)} hint="Needs review" tone="warning" />
        <StatCard
          label="Newest import"
          value={recent ? formatRelativeTime(recent.imported_at) : "n/a"}
          hint={recent ? recent.title : "No imports yet"}
        />
      </section>

      <InboxWorkspace overviewQueryKey={inboxOverviewQueryKey} />

      <SectionCard title="Inbox status" description="The top-level inbox view mirrors the live document set.">
        <div className="grid gap-2 text-sm leading-6 text-slate-700">
          <p className="m-0">Pending or processing items will stay visible until their parse status resolves.</p>
          <p className="m-0">File uploads are direct-to-storage, then completed into a queued job.</p>
          <p className="m-0">Open any recent document to edit tags or retry parsing.</p>
        </div>
      </SectionCard>
    </PageShell>
  );
}
