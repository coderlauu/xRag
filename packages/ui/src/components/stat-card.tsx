interface StatCardProps {
  label: string;
  value: string;
  hint?: string;
  tone?: "default" | "warning";
}

export function StatCard({ label, value, hint, tone = "default" }: StatCardProps) {
  const accent = tone === "warning" ? "#f4c15a" : "#8ad3ff";

  return (
    <article
      style={{
        border: `1px solid ${accent}40`,
        borderRadius: 18,
        padding: 18,
        background: "rgba(255,255,255,0.04)",
        display: "grid",
        gap: 8
      }}
    >
      <span style={{ fontSize: 12, letterSpacing: 1, textTransform: "uppercase", opacity: 0.75 }}>{label}</span>
      <strong style={{ fontSize: 34, lineHeight: 1 }}>{value}</strong>
      {hint ? <span style={{ opacity: 0.78 }}>{hint}</span> : null}
    </article>
  );
}
