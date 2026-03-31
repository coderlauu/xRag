import { Button, Input, PageShell, SectionCard, Select, StatCard } from "@xrag/ui";

export function SearchPage() {
  return (
    <PageShell
      eyebrow="Search"
      title="Find it again quickly"
      description="Keyword-first retrieval surface for title, body, tags, and source metadata."
      actions={[
        { label: "Back to inbox", href: "/", variant: "outline" },
        { label: "Open document detail", href: "/detail/doc_123", variant: "default" }
      ]}
    >
      <section className="grid gap-4 md:grid-cols-3">
        <StatCard label="Matches" value="18" hint="Query-driven" />
        <StatCard label="Tag hits" value="7" hint="Selection or free input" />
        <StatCard label="Newest hit" value="2h" hint="Freshness ordering" />
      </section>

      <section className="grid gap-6 xl:grid-cols-[minmax(320px,0.85fr)_minmax(0,1.15fr)]">
        <SectionCard title="Search controls" description="Placeholder search box and filters mapped to the planned API contract.">
          <div className="grid gap-3">
            <Input aria-label="Search documents" placeholder="Search by title, content, tags, file name" />
            <div className="grid gap-3 sm:grid-cols-2">
              <Select aria-label="Source type">
                <option>All source types</option>
                <option>Manual</option>
                <option>Upload</option>
              </Select>
              <Select aria-label="Status">
                <option>All statuses</option>
                <option>Pending</option>
                <option>Processing</option>
                <option>Success</option>
                <option>Failed</option>
              </Select>
            </div>
            <Button type="button" className="w-full sm:w-fit">
              Search
            </Button>
          </div>
        </SectionCard>

        <SectionCard title="Results" description="Placeholder result cards for ranked retrieval and detail navigation.">
          <div className="grid gap-3">
            {[
              "RAG Product Minimal Loop - matched in title and tags",
              "Knowledge inbox follow-up - matched in body text",
              "Failure recovery note - matched in file name"
            ].map((item) => (
              <article
                className="rounded-2xl border border-slate-200 bg-slate-50/80 px-4 py-3 text-sm leading-6 text-slate-700"
                key={item}
              >
                {item}
              </article>
            ))}
          </div>
        </SectionCard>
      </section>
    </PageShell>
  );
}
