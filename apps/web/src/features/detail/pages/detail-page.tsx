import { PageShell, SectionCard, StatCard } from "@xrag/ui";

interface DetailPageProps {
  documentId?: string;
}

export function DetailPage({ documentId = "doc_123" }: DetailPageProps) {
  return (
    <PageShell
      eyebrow="Detail"
      title="Document detail"
      description="Placeholder detail view for source metadata, content, tags, and retry actions."
      actions={[
        { label: "Back to search", href: "/search" },
        { label: "Back to inbox", href: "/" }
      ]}
    >
      <section style={{ display: "grid", gap: 16, gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
        <StatCard label="Document ID" value={documentId} hint="Route param ready" />
        <StatCard label="Parse status" value="success" hint="From backend state" />
        <StatCard label="Source" value="upload" hint="File or manual input" />
      </section>

      <section style={{ display: "grid", gap: 24, gridTemplateColumns: "minmax(0, 1.15fr) minmax(0, 0.85fr)" }}>
        <SectionCard title="Content" description="Placeholder detail body for cleaned content and raw traceability.">
          <div style={{ display: "grid", gap: 12 }}>
            <h3 style={{ margin: 0 }}>RAG Product Minimal Loop</h3>
            <p style={{ margin: 0 }}>
              This is the canonical detail surface for the selected document. It will later bind to the document
              retrieval API and render raw content, clean content, and parse notes.
            </p>
          </div>
        </SectionCard>

        <div style={{ display: "grid", gap: 24 }}>
          <SectionCard title="Metadata" description="Placeholder for tag editing, source metadata, and timestamps.">
            <div style={{ display: "grid", gap: 10 }}>
              <article>Tags: RAG, MVP, retrieval</article>
              <article>File name: rag-mvp.pdf</article>
              <article>Imported: 2026-03-31 12:00</article>
            </div>
          </SectionCard>

          <SectionCard title="Actions" description="Placeholder for retry, tag update, and search-back navigation.">
            <div style={{ display: "grid", gap: 10 }}>
              <button type="button">Retry parse</button>
              <button type="button">Edit tags</button>
            </div>
          </SectionCard>
        </div>
      </section>
    </PageShell>
  );
}
