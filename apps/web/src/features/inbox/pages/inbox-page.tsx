import { PageShell, SectionCard, StatCard } from "@xrag/ui";
import { InboxWorkspace } from "../workspace/inbox-workspace";

export function InboxPage() {
  return (
    <PageShell
      eyebrow="Inbox"
      title="Capture first, organize later"
      description="Phase 1A inbox foundation for manual notes, uploads, parse state, and recent imports."
      actions={[
        { label: "Search documents", href: "/search" },
        { label: "Open sample detail", href: "/detail/doc_123" }
      ]}
    >
      <section aria-label="Inbox metrics" style={{ display: "grid", gap: 16, gridTemplateColumns: "repeat(4, minmax(0, 1fr))" }}>
        <StatCard label="Total documents" value="128" hint="Manual + uploaded" />
        <StatCard label="Searchable" value="121" hint="Ready for retrieval" />
        <StatCard label="Processing" value="4" hint="Queued or active" />
        <StatCard label="Failed" value="3" hint="Needs review" tone="warning" />
      </section>

      <InboxWorkspace />

      <SectionCard title="Planned module map" description="This page is wired to the future backend modules described in tech/architecture.">
        <ul style={{ margin: 0, paddingLeft: 18, display: "grid", gap: 8 }}>
          <li>Document intake surface for manual content and uploads.</li>
          <li>Job status surface for `pending`, `processing`, `success`, `failed`.</li>
          <li>Recent imports and retry entry point for future document details.</li>
        </ul>
      </SectionCard>
    </PageShell>
  );
}
