import type { ReactNode } from "react";
import { Button } from "./button";
import { cn } from "../lib/utils";

interface PageAction {
  label: string;
  href: string;
  variant?: "default" | "outline" | "secondary" | "ghost";
}

interface PageShellProps {
  eyebrow: string;
  title: string;
  description: string;
  actions?: PageAction[];
  children: ReactNode;
}

export function PageShell({ eyebrow, title, description, actions, children }: PageShellProps) {
  return (
    <section className="grid gap-6">
      <header className="grid gap-4 rounded-[28px] border border-white/60 bg-white/75 p-6 shadow-[0_20px_60px_rgba(15,23,42,0.08)] backdrop-blur xl:p-8">
        <p className="m-0 text-xs font-semibold uppercase tracking-[0.28em] text-sky-700">{eyebrow}</p>
        <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_auto] xl:items-end">
          <div className="grid gap-3">
            <h1 className="m-0 max-w-4xl text-4xl font-semibold tracking-[-0.04em] text-slate-950 md:text-5xl">
              {title}
            </h1>
            <p className="m-0 max-w-3xl text-sm leading-7 text-slate-600 md:text-base">{description}</p>
          </div>
          {actions ? (
            <nav className="flex flex-wrap gap-3">
              {actions.map((action) => (
                <Button asChild key={action.href} variant={action.variant ?? "outline"}>
                  <a href={action.href}>{action.label}</a>
                </Button>
              ))}
            </nav>
          ) : null}
        </div>
      </header>
      <div className={cn("grid gap-6")}>{children}</div>
    </section>
  );
}
