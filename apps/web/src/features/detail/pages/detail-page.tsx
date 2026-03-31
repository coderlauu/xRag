import { Button, PageShell, SectionCard, StatCard } from "@xrag/ui";

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
        { label: "Back to search", href: "/search", variant: "outline" },
        { label: "Back to inbox", href: "/", variant: "ghost" }
      ]}
    >
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard label="Document ID" value={documentId} hint="Route param ready" />
        <StatCard label="Parse status" value="success" hint="From backend state" />
        <StatCard label="Source" value="upload" hint="File or manual input" />
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
        <SectionCard title="Content" description="Placeholder detail body for cleaned content and raw traceability.">
          <div className="grid gap-3">
            <h3 className="m-0 text-2xl font-semibold tracking-[-0.04em] text-slate-950">RAG Product Minimal Loop</h3>
            <p className="m-0 text-sm leading-7 text-slate-700 md:text-base">
              This is the canonical detail surface for the selected document. It will later bind to the document
              retrieval API and render raw content, clean content, and parse notes.
            </p>
          </div>
        </SectionCard>

        <div className="grid gap-6">
          <SectionCard title="Metadata" description="Placeholder for tag editing, source metadata, and timestamps.">
            <div className="grid gap-2 text-sm leading-6 text-slate-700">
              <article>Tags: RAG, MVP, retrieval</article>
              <article>File name: rag-mvp.pdf</article>
              <article>Imported: 2026-03-31 12:00</article>
            </div>
          </SectionCard>

          <SectionCard title="Actions" description="Placeholder for retry, tag update, and search-back navigation.">
            <div className="grid gap-3">
              <Button type="button">Retry parse</Button>
              <Button type="button" variant="outline">
                Edit tags
              </Button>
            </div>
          </SectionCard>
        </div>
      </section>
    </PageShell>
  );
}
