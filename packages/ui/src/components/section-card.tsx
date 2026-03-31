import type { ReactNode } from "react";

interface SectionCardProps {
  title: string;
  description?: string;
  children: ReactNode;
}

export function SectionCard({ title, description, children }: SectionCardProps) {
  return (
    <section
      style={{
        border: "1px solid rgba(255,255,255,0.12)",
        borderRadius: 20,
        padding: 20,
        display: "grid",
        gap: 14,
        background: "rgba(255,255,255,0.03)"
      }}
    >
      <header style={{ display: "grid", gap: 6 }}>
        <h2 style={{ margin: 0, fontSize: 20 }}>{title}</h2>
        {description ? <p style={{ margin: 0, opacity: 0.8 }}>{description}</p> : null}
      </header>
      {children}
    </section>
  );
}
