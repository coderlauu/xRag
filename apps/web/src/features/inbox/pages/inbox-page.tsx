import { PageShell, SectionCard, StatCard } from "@xrag/ui";
import { InboxWorkspace } from "../workspace/inbox-workspace";

export function InboxPage() {
  return (
    <PageShell
      eyebrow="Inbox"
      title="Capture first, organize later"
      description="Phase 1A inbox foundation for manual notes, uploads, parse state, and recent imports."
      actions={[
        { label: "Search documents", href: "/search", variant: "default" },
        { label: "Open sample detail", href: "/detail/doc_123", variant: "outline" }
      ]}
    >
      <section aria-label="Inbox metrics" className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Total documents" value="128" hint="Manual + uploaded" />
        <StatCard label="Searchable" value="121" hint="Ready for retrieval" />
        <StatCard label="Processing" value="4" hint="Queued or active" />
        <StatCard label="Failed" value="3" hint="Needs review" tone="warning" />
      </section>

      <InboxWorkspace />

      <SectionCard
        title="Planned module map"
        description="This page is wired to the future backend modules described in tech/architecture."
      >
        <ul className="m-0 grid gap-2 pl-5 text-sm leading-6 text-slate-700">
          <li>Document intake surface for manual content and uploads.</li>
          <li>Job status surface for `pending`, `processing`, `success`, `failed`.</li>
          <li>Recent imports and retry entry point for future document details.</li>
        </ul>
      </SectionCard>
    </PageShell>
  );
}
