import type { ReactNode } from "react";
import { Link } from "@tanstack/react-router";

interface AppShellProps {
  children: ReactNode;
}

export function AppShell({ children }: AppShellProps) {
  return (
    <main
      style={{
        fontFamily: "system-ui, sans-serif",
        margin: "0 auto",
        maxWidth: 1120,
        padding: 32,
        display: "grid",
        gap: 28
      }}
    >
      <header style={{ display: "grid", gap: 12 }}>
        <h1 style={{ margin: 0 }}>xRag Web</h1>
        <p style={{ margin: 0, maxWidth: 720 }}>React SPA scaffold aligned with the Phase 1A architecture.</p>
        <nav style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
          <Link to="/">Inbox</Link>
          <Link to="/search">Search</Link>
          <Link to="/detail/$documentId" params={{ documentId: "doc_123" }}>
            Detail
          </Link>
        </nav>
      </header>
      {children}
    </main>
  );
}
