import { useState } from "react";
import { ChevronRightIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContributionGroup } from "@/api/client";
import { cn } from "@/lib/utils";
import { FeatureBars } from "./FeatureBars";

/**
 * Why the work got its prediction, PART BY PART — a facet with all its values, the pictures, a
 * text, the user's marks on the site — each as a bar of the points it moves the work on its own,
 * the strongest first. Folded to one line naming the strongest part: the card is for grading.
 * A part of several features opens to every one of them.
 */
export function Explanation({
  groups,
  className,
  title,
}: {
  groups: ContributionGroup[];
  className?: string;
  /** What the folded line says the parts explain; "Why this prediction" when not given. */
  title?: string;
}) {
  const { t } = useTranslation("model");
  const [shown, setShown] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  if (groups.length === 0) return null;
  const max = Math.max(...groups.map((g) => Math.abs(g.contribution)), 1e-9);
  // Folded, the line names the part that moves the work most: the card is for grading, the why is a reference
  const strongest = groups[0];
  return (
    <section className={cn("flex flex-col gap-1", className)}>
      <button
        type="button"
        className="inline-flex items-center gap-1 text-left text-sm hover:underline"
        onClick={() => setShown(!shown)}
        aria-expanded={shown}
        title={t("Explanation.hint")}
      >
        <ChevronRightIcon className={cn("size-4 shrink-0 transition-transform", shown && "rotate-90")} />
        <span className="font-semibold">{title ?? t("Explanation.title")}</span>
        {!shown && (
          <span className="min-w-0 truncate text-muted-foreground">
            ·{" "}
            {t("Explanation.strongest", {
              part: strongest.label.toLowerCase(),
              contribution: `${strongest.contribution > 0 ? "+" : ""}${strongest.contribution.toFixed(2)}`,
            })}
          </span>
        )}
      </button>
      {shown && (
      <ul className="flex flex-col gap-0.5">
        {groups.map((g) => {
          const several = g.features.length > 1;
          const expanded = open === g.part;
          return (
            <li key={g.part}>
              <button
                type="button"
                className={cn(
                  "grid w-full grid-cols-[1rem_minmax(0,1fr)_5rem_3rem] items-center gap-2 rounded px-1 py-0.5 text-left text-sm",
                  several ? "hover:bg-muted/60" : "cursor-default",
                )}
                onClick={() => several && setOpen(expanded ? null : g.part)}
                aria-expanded={several ? expanded : undefined}
                title={several ? t("Explanation.expand") : undefined}
              >
                {several ? (
                  <ChevronRightIcon className={cn("size-3.5 text-muted-foreground transition-transform", expanded && "rotate-90")} />
                ) : (
                  <span />
                )}
                <span className="min-w-0 truncate">{g.label}</span>
                <span className="h-2 overflow-hidden rounded-full bg-muted">
                  <span
                    className={cn("block h-full rounded-full", g.contribution > 0 ? "bg-yes" : "bg-no")}
                    style={{ width: `${(Math.abs(g.contribution) / max) * 100}%` }}
                  />
                </span>
                <span className="text-right font-mono text-xs tabular-nums text-muted-foreground">
                  {g.contribution > 0 ? "+" : ""}
                  {g.contribution.toFixed(2)}
                </span>
              </button>
              {expanded && <FeatureBars items={g.features} className="mt-1 mb-2 ml-6 max-h-64 overflow-y-auto pr-1" />}
            </li>
          );
        })}
      </ul>
      )}
    </section>
  );
}
