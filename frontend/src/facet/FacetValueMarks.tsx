import { useTranslation } from "react-i18next";
import type { FacetValueInfo } from "@/api/client";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { percent } from "@/i18n/format";

/** Below one half the model thinks the work more likely has the value not than has it. */
const HALF = 0.5;

/**
 * The model's chance is shown only by a value the user has not answered on, and only when the
 * model has one: a value the model has no chance of comes with none — null in the JSON, not only
 * absent — and shows none.
 */
export function shownChance(value: FacetValueInfo): number | undefined {
  return value.corrected || value.chance == null ? undefined : value.chance;
}

/** The model more likely says no than yes to a value the user has not answered on. */
export function unlikely(value: FacetValueInfo): boolean {
  const chance = shownChance(value);
  return chance !== undefined && chance < HALF;
}

/** "87%" in small print — the model's chance by the value's name; amber, with a word why, when below one half. */
export function ChanceMark({ value }: { value: FacetValueInfo }) {
  const { t } = useTranslation("facet");
  const chance = shownChance(value);
  if (chance === undefined) return null;
  const text = percent(chance);
  if (!unlikely(value)) return <span className="text-[10px] text-muted-foreground tabular-nums">{text}</span>;
  return (
    <Tooltip>
      <TooltipTrigger render={<span className="cursor-help text-[10px] font-medium text-maybe tabular-nums" />}>{text}</TooltipTrigger>
      <TooltipContent>{t("ChanceMark.unlikely")}</TooltipContent>
    </Tooltip>
  );
}
