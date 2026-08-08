import type { ReactNode } from "react";

interface EmptyStateProps {
  title: string;
  detail?: string;
  action?: ReactNode;
}

export function EmptyState({ title, detail, action }: EmptyStateProps) {
  return (
    <div className="state-block state-empty" role="status">
      <p className="state-title">{title}</p>
      {detail ? <p className="state-detail">{detail}</p> : null}
      {action}
    </div>
  );
}
