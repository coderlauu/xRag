import type { ReactNode } from "react";

interface AppShellProps {
  children: ReactNode;
}

export function AppShell({ children }: AppShellProps) {
  return (
    <main style={{ fontFamily: "system-ui, sans-serif", margin: "0 auto", maxWidth: 960, padding: 32 }}>
      <header>
        <h1>xRag Web</h1>
        <p>React SPA scaffold aligned with the Phase 1A architecture.</p>
      </header>
      {children}
    </main>
  );
}
