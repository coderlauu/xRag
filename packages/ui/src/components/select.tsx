import * as React from "react";
import { cn } from "../lib/utils";

export interface SelectProps extends React.SelectHTMLAttributes<HTMLSelectElement> {}

export const Select = React.forwardRef<HTMLSelectElement, SelectProps>(({ className, ...props }, ref) => {
  return (
    <select
      ref={ref}
      className={cn(
        "flex h-11 w-full rounded-xl border border-slate-200 bg-white/85 px-3 py-2 text-sm text-slate-900 shadow-sm outline-none transition focus-visible:border-sky-400 focus-visible:ring-2 focus-visible:ring-sky-200",
        className
      )}
      {...props}
    />
  );
});

Select.displayName = "Select";
