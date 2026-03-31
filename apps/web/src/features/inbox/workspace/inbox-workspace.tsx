import { SectionCard } from "@xrag/ui";

export function InboxWorkspace() {
  return (
    <section style={{ display: "grid", gap: 24, gridTemplateColumns: "minmax(0, 1.15fr) minmax(0, 0.85fr)" }}>
      <SectionCard title="Text capture" description="Placeholder for manual note save, tag selection, and immediate success-state creation.">
        <div style={{ display: "grid", gap: 12 }}>
          <div style={{ display: "grid", gap: 6 }}>
            <label htmlFor="inbox-title">Title</label>
            <input id="inbox-title" placeholder="e.g. RAG product MVP notes" />
          </div>
          <div style={{ display: "grid", gap: 6 }}>
            <label htmlFor="inbox-content">Content</label>
            <textarea id="inbox-content" rows={8} placeholder="Paste or draft the raw content here." />
          </div>
          <button type="button">Save note</button>
        </div>
      </SectionCard>

      <div style={{ display: "grid", gap: 24 }}>
        <SectionCard title="Upload queue" description="Placeholder for direct-to-object-storage upload and parse progress.">
          <div style={{ display: "grid", gap: 8 }}>
            <p style={{ margin: 0 }}>Supported: txt, md, text-based pdf.</p>
            <p style={{ margin: 0 }}>Future flow: initiate upload, complete upload, enqueue parse job.</p>
          </div>
        </SectionCard>

        <SectionCard title="Recent imports" description="Placeholder list for newest documents and retry triggers.">
          <div style={{ display: "grid", gap: 10 }}>
            <article>RAG Product Minimal Loop - success</article>
            <article>Interview notes - processing</article>
            <article>PDF OCR sample - failed</article>
          </div>
        </SectionCard>

        <SectionCard title="Processing jobs" description="Placeholder for worker-driven parse lifecycle updates.">
          <div style={{ display: "grid", gap: 10 }}>
            <article>parse_document / doc_456 / processing</article>
            <article>reparse_document / doc_889 / queued</article>
          </div>
        </SectionCard>
      </div>
    </section>
  );
}
