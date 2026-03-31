import type { ReactNode } from "react";
import { Link } from "@tanstack/react-router";
import { cn } from "@xrag/ui";

interface AppShellProps {
  children: ReactNode;
}

export function AppShell({ children }: AppShellProps) {
  return (
    <main className="mx-auto grid min-h-screen w-full max-w-7xl gap-7 px-4 py-6 md:px-6 xl:px-8">
      <header className="grid gap-4 rounded-[32px] border border-white/60 bg-slate-950 px-6 py-6 text-slate-50 shadow-[0_24px_80px_rgba(15,23,42,0.18)]">
        <div className="flex flex-col gap-2 lg:flex-row lg:items-end lg:justify-between">
          <div className="grid gap-2">
            <span className="text-xs font-semibold uppercase tracking-[0.32em] text-sky-300">xRag Phase 1A</span>
            <h1 className="m-0 text-3xl font-semibold tracking-[-0.05em] md:text-4xl">Knowledge Inbox</h1>
            <p className="m-0 max-w-3xl text-sm leading-6 text-slate-300 md:text-base">
              Frontend engineering foundation is now using Tailwind utility styling and shadcn-style components.
            </p>
          </div>
        </div>
        <nav className="flex flex-wrap gap-2">
          <Link
            to="/"
            className={cn(
              "rounded-full border border-white/15 bg-white/10 px-4 py-2 text-sm font-medium text-slate-50 transition hover:bg-white/18"
            )}
          >
            Inbox
          </Link>
          <Link
            to="/search"
            className={cn(
              "rounded-full border border-white/15 bg-white/10 px-4 py-2 text-sm font-medium text-slate-50 transition hover:bg-white/18"
            )}
          >
            Search
          </Link>
          <Link
            to="/detail/$documentId"
            params={{ documentId: "doc_123" }}
            className={cn(
              "rounded-full border border-white/15 bg-white/10 px-4 py-2 text-sm font-medium text-slate-50 transition hover:bg-white/18"
            )}
          >
            Detail
          </Link>
        </nav>
      </header>
      {children}
    </main>
  );
}
