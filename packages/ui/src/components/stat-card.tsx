import { Badge } from "./badge";
import { cn } from "../lib/utils";

interface StatCardProps {
  label: string;
  value: string;
  hint?: string;
  tone?: "default" | "warning";
}

export function StatCard({ label, value, hint, tone = "default" }: StatCardProps) {
  const toneClass =
    tone === "warning"
      ? "border-amber-200/80 bg-amber-50/80"
      : "border-sky-200/80 bg-linear-to-br from-sky-50 to-white";

  return (
    <article className={cn("grid gap-3 rounded-[22px] border p-5 shadow-sm", toneClass)}>
      <div className="flex items-start justify-between gap-3">
        <Badge variant={tone === "warning" ? "warning" : "info"}>{label}</Badge>
      </div>
      <strong className="text-3xl font-semibold tracking-[-0.05em] text-slate-950 md:text-4xl">{value}</strong>
      {hint ? <span className="text-sm leading-6 text-slate-600">{hint}</span> : null}
    </article>
  );
}
