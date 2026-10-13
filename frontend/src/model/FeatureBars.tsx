import { useTranslation } from "react-i18next";
import type { FeatureContribution } from "@/api/client";
import { label } from "@/i18n/label";
import { cn } from "@/lib/utils";

/**
 * What a feature is, as the user is shown it: what it is of — "Tag", "Cover" — and of a feature of
 * one value that value, as the data names it or by the key of its name: "Tag: stealth", "Status: Finished".
 */
export function featureLabel(feature: FeatureContribution): string {
  const of = label(feature.labelKey);
  const value = feature.valueKey ? label(feature.valueKey) : feature.value;
  return value ? `${of}: ${value}` : of;
}

/**
 * Contributions as bars on a shared scale: green pushes the score up, red down. The number is
 * the change of the score, in points of 0..10, against the average work; a feature the work
 * lacks is shown crossed out with "absent".
 */
export function FeatureBars({ items, className }: { items: FeatureContribution[]; className?: string }) {
  const { t } = useTranslation("model");
  if (items.length === 0) return <div className="text-sm text-muted-foreground">—</div>;
  const max = Math.max(...items.map((i) => Math.abs(i.contribution)), 1e-9);
  return (
    <div className={cn("flex flex-col gap-1", className)}>
      {items.map((item) => (
        <div key={item.feature} className="grid grid-cols-[minmax(0,1fr)_6rem_3rem] items-center gap-2 text-sm" title={item.feature}>
          <div className="min-w-0 truncate">
            {!item.present && <span className="text-xs text-muted-foreground">{t("FeatureBars.absent")} </span>}
            <span className={cn(!item.present && "text-muted-foreground line-through")}>{featureLabel(item)}</span>
          </div>
          <div className="h-2 overflow-hidden rounded-full bg-muted">
            <div
              className={cn("h-full rounded-full", item.contribution > 0 ? "bg-yes" : "bg-no")}
              style={{ width: `${(Math.abs(item.contribution) / max) * 100}%` }}
            />
          </div>
          <div className="text-right font-mono text-xs tabular-nums text-muted-foreground">
            {item.contribution > 0 ? "+" : ""}
            {item.contribution.toFixed(2)}
          </div>
        </div>
      ))}
    </div>
  );
}
