import type { ReactNode } from "react";
import { cn } from "../lib/utils";

interface SectionCardProps {
  title: string;
  description?: string;
  children: ReactNode;
  className?: string;
}

export function SectionCard({ title, description, children, className }: SectionCardProps) {
  return (
    <section
      className={cn(
        "grid gap-4 rounded-[24px] border border-white/60 bg-white/80 p-5 shadow-[0_12px_32px_rgba(15,23,42,0.06)] backdrop-blur",
        className
      )}
    >
      <header className="grid gap-1.5">
        <h2 className="m-0 text-lg font-semibold tracking-[-0.03em] text-slate-950">{title}</h2>
        {description ? <p className="m-0 text-sm leading-6 text-slate-600">{description}</p> : null}
      </header>
      {children}
    </section>
  );
}
