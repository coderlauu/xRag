import { Button, Input, SectionCard, Textarea } from "@xrag/ui";

export function InboxWorkspace() {
  return (
    <section className="grid gap-6 xl:grid-cols-[minmax(0,1.15fr)_minmax(320px,0.85fr)]">
      <SectionCard
        title="Text capture"
        description="Manual note save, tag selection, and immediate success-state creation will land here."
      >
        <div className="grid gap-4">
          <div className="grid gap-2">
            <label className="text-sm font-medium text-slate-800" htmlFor="inbox-title">
              Title
            </label>
            <Input id="inbox-title" placeholder="e.g. RAG product MVP notes" />
          </div>
          <div className="grid gap-2">
            <label className="text-sm font-medium text-slate-800" htmlFor="inbox-content">
              Content
            </label>
            <Textarea id="inbox-content" rows={8} placeholder="Paste or draft the raw content here." />
          </div>
          <Button type="button" className="w-full sm:w-fit">
            Save note
          </Button>
        </div>
      </SectionCard>

      <div className="grid gap-6">
        <SectionCard title="Upload queue" description="Placeholder for direct-to-object-storage upload and parse progress.">
          <div className="grid gap-2 text-sm leading-6 text-slate-700">
            <p className="m-0">Supported: txt, md, text-based pdf.</p>
            <p className="m-0">Future flow: initiate upload, complete upload, enqueue parse job.</p>
          </div>
        </SectionCard>

        <SectionCard title="Recent imports" description="Placeholder list for newest documents and retry triggers.">
          <div className="grid gap-2 text-sm leading-6 text-slate-700">
            <article>RAG Product Minimal Loop - success</article>
            <article>Interview notes - processing</article>
            <article>PDF OCR sample - failed</article>
          </div>
        </SectionCard>

        <SectionCard title="Processing jobs" description="Placeholder for worker-driven parse lifecycle updates.">
          <div className="grid gap-2 text-sm leading-6 text-slate-700">
            <article>parse_document / doc_456 / processing</article>
            <article>reparse_document / doc_889 / queued</article>
          </div>
        </SectionCard>
      </div>
    </section>
  );
}
