import { PageShell, SectionCard, StatCard } from "@xrag/ui";

export function SearchPage() {
  return (
    <PageShell
      eyebrow="Search"
      title="Find it again quickly"
      description="Keyword-first retrieval surface for title, body, tags, and source metadata."
      actions={[
        { label: "Back to inbox", href: "/" },
        { label: "Open document detail", href: "/detail/doc_123" }
      ]}
    >
      <section style={{ display: "grid", gap: 16, gridTemplateColumns: "repeat(3, minmax(0, 1fr))" }}>
        <StatCard label="Matches" value="18" hint="Query-driven" />
        <StatCard label="Tag hits" value="7" hint="Selection or free input" />
        <StatCard label="Newest hit" value="2h" hint="Freshness ordering" />
      </section>

      <section style={{ display: "grid", gap: 24, gridTemplateColumns: "minmax(0, 0.85fr) minmax(0, 1.15fr)" }}>
        <SectionCard title="Search controls" description="Placeholder search box and filters mapped to the planned API contract.">
          <div style={{ display: "grid", gap: 12 }}>
            <input aria-label="Search documents" placeholder="Search by title, content, tags, file name" />
            <div style={{ display: "grid", gap: 8, gridTemplateColumns: "repeat(2, minmax(0, 1fr))" }}>
              <select aria-label="Source type">
                <option>All source types</option>
                <option>Manual</option>
                <option>Upload</option>
              </select>
              <select aria-label="Status">
                <option>All statuses</option>
                <option>Pending</option>
                <option>Processing</option>
                <option>Success</option>
                <option>Failed</option>
              </select>
            </div>
            <button type="button">Search</button>
          </div>
        </SectionCard>

        <SectionCard title="Results" description="Placeholder result cards for ranked retrieval and detail navigation.">
          <div style={{ display: "grid", gap: 12 }}>
            <article>RAG Product Minimal Loop - matched in title and tags</article>
            <article>Knowledge inbox follow-up - matched in body text</article>
            <article>Failure recovery note - matched in file name</article>
          </div>
        </SectionCard>
      </section>
    </PageShell>
  );
}
