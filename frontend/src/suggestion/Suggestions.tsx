import { useTranslation } from "react-i18next";
import type { FacetSuggestions, SuggestedValue } from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { FacetAnswers } from "@/correction/FacetAnswers";
import type { Corrections } from "@/correction/useCorrections";
import { percent } from "@/i18n/format";

/**
 * The values the application suggests adding, facet by facet, the likeliest first: each one
 * confirmed (it is added to the work) or rejected (it is taken away and not suggested again).
 */
export function SuggestedValues({ suggestions, corrections }: { suggestions: FacetSuggestions[]; corrections: Corrections }) {
  const { t } = useTranslation("suggestion");
  const shown = suggestions.filter((s) => s.suggested.length > 0);
  if (shown.length === 0) return null;
  return (
    <div className="flex flex-col gap-2 rounded-lg border border-dashed p-2">
      <div className="text-xs text-muted-foreground">{t("SuggestedValues.title")}</div>
      {shown.map((s) => (
        <div key={s.facet}>
          <div className="mb-1 text-xs text-muted-foreground">{s.label}</div>
          <div className="flex flex-wrap gap-1">
            {s.suggested.map((v) => (
              <SuggestedChip key={v.key} facet={s.facet} value={v} corrections={corrections} />
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}

/** The answer is a correction of the facet, after which the value is no longer suggested. */
export function SuggestedChip({ facet, value, corrections }: { facet: string; value: SuggestedValue; corrections: Corrections }) {
  const { t } = useTranslation("suggestion");
  // The contract gives every suggestion a chance; a null one is still not shown as "0%"
  const chance = value.chance == null ? undefined : value.chance;
  return (
    <Badge variant="outline" className="border-dashed pr-0.5 font-normal" title={chance !== undefined ? t("SuggestedChip.chance", { chance: percent(chance) }) : undefined}>
      {value.name}
      {chance !== undefined && <span className="text-[10px] text-muted-foreground tabular-nums">{percent(chance)}</span>}
      <FacetAnswers facet={facet} value={{ key: value.key }} corrections={corrections} />
    </Badge>
  );
}
