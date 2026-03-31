import type { ReactNode } from "react";

interface PageAction {
  label: string;
  href: string;
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
    <section style={{ display: "grid", gap: 24 }}>
      <header style={{ display: "grid", gap: 12 }}>
        <p style={{ margin: 0, textTransform: "uppercase", letterSpacing: 1.6, fontSize: 12 }}>{eyebrow}</p>
        <h1 style={{ margin: 0, fontSize: 40, lineHeight: 1.1 }}>{title}</h1>
        <p style={{ margin: 0, maxWidth: 760 }}>{description}</p>
        {actions ? (
          <nav style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
            {actions.map((action) => (
              <a key={action.href} href={action.href}>
                {action.label}
              </a>
            ))}
          </nav>
        ) : null}
      </header>
      {children}
    </section>
  );
}
