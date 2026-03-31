import { cva, type VariantProps } from "class-variance-authority";
import type { HTMLAttributes } from "react";
import { cn } from "../lib/utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-full border px-2.5 py-1 text-xs font-medium uppercase tracking-[0.18em]",
  {
    variants: {
      variant: {
        default: "border-slate-200 bg-white/80 text-slate-700",
        info: "border-sky-200 bg-sky-100 text-sky-800",
        warning: "border-amber-200 bg-amber-100 text-amber-900",
        success: "border-emerald-200 bg-emerald-100 text-emerald-900"
      }
    },
    defaultVariants: {
      variant: "default"
    }
  }
);

interface BadgeProps extends HTMLAttributes<HTMLDivElement>, VariantProps<typeof badgeVariants> {}

export function Badge({ className, variant, ...props }: BadgeProps) {
  return <div className={cn(badgeVariants({ variant }), className)} {...props} />;
}
