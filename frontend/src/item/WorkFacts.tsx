import { useTranslation } from "react-i18next";
import type { FacetRole, ItemFacet, ItemSummary, NumberRole, SourceInfo } from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { ago, compact, formatDate, number } from "@/i18n/format";
import { intlTag } from "@/i18n/i18n";
import { label, valueName } from "@/i18n/label";
import { cn } from "@/lib/utils";
import { FacetBadges } from "./pieces";

/** The facets of the work the source gives a role, in the source's order. */
export function facetsOfRole(source: SourceInfo, facets: ItemFacet[], role: FacetRole): ItemFacet[] {
  const keys = source.facets.filter((f) => f.role === role).map((f) => f.key);
  return keys.map((k) => facets.find((f) => f.facet === k)).filter((f): f is ItemFacet => f !== undefined);
}

/** The names of the values the work has of the facets: a value the user took away is not the work's. */
function namesOf(facets: ItemFacet[]): string[] {
  return facets.flatMap((f) => f.values.filter((v) => v.corrected !== "REMOVED" && !v.offered).map(valueName));
}

/** The numbers of the work the source gives a role, with their labels and scales. */
function numbersOfRole(source: SourceInfo, numbers: Record<string, number>, role: NumberRole) {
  return source.numbers
    .filter((n) => n.role === role && numbers[n.key] !== undefined)
    .map((n) => ({ ...n, label: label(n.labelKey), value: numbers[n.key] }));
}

/** Who made the work: "Sergey Plotnikov", "Sakrilas & VelcroFist". */
export function Byline({ source, facets, className }: { source: SourceInfo; facets: ItemFacet[]; className?: string }) {
  const authors = namesOf(facetsOfRole(source, facets, "AUTHOR"));
  if (authors.length === 0) return null;
  return <div className={cn("truncate", className)}>{authors.join(", ")}</div>;
}

/**
 * The line of facts a work is chosen by, the same for every source by the roles its source gives
 * its facets and numbers: whether it is finished, what it is made as, how long it is, when it
 * was updated, how many liked it — and, of a count of likes beside a count of views, what share
 * of the views liked it.
 */
export function FactLine({ source, summary, facets, className }: { source: SourceInfo; summary: ItemSummary; facets: ItemFacet[]; className?: string }) {
  const { t } = useTranslation("item");
  const status = namesOf(facetsOfRole(source, facets, "STATUS"));
  const form = namesOf(facetsOfRole(source, facets, "FORM"));
  const size = numbersOfRole(source, summary.numbers, "SIZE");
  const approval = numbersOfRole(source, summary.numbers, "APPROVAL");
  const reach = numbersOfRole(source, summary.numbers, "REACH")[0];
  return (
    <div className={cn("flex flex-wrap items-center gap-x-3 gap-y-1", className)}>
      {status.map((s) => (
        <Badge key={s} variant="secondary" className="font-medium">
          {s}
        </Badge>
      ))}
      {form.map((f) => (
        <span key={f}>{f}</span>
      ))}
      {summary.version && <span className="font-medium">{summary.version}</span>}
      {size.map((n) => (
        <span key={n.key} title={`${n.label}: ${number(n.value)}`}>
          {compact(n.value)} {n.label.toLowerCase()}
        </span>
      ))}
      <span title={formatDate(summary.updatedAt)}>{t("FactLine.updated", { ago: ago(summary.updatedAt) })}</span>
      {approval.map((n) => (
        <span key={n.key} title={`${n.label}: ${number(n.value)}`}>
          {n.label} <span className="font-medium">{n.scale === "LINEAR" ? n.value.toLocaleString(intlTag(), { maximumFractionDigits: 1 }) : compact(n.value)}</span>
          {n.scale !== "LINEAR" && reach && reach.value > 0 && (
            <span className="opacity-70" title={t("FactLine.reachShare", { reach: reach.label.toLowerCase(), value: number(reach.value) })}>
              {" "}
              ({((n.value / reach.value) * 100).toLocaleString(intlTag(), { maximumSignificantDigits: 2 })}%)
            </span>
          )}
        </span>
      ))}
    </div>
  );
}

/**
 * The site's own word on a facet of the layer ([facet] — its key): its tags above the work's tags,
 * its fandoms above the universes, its line of pairings and characters above the characters — as
 * the site gives them, never answered on, right above the values the user answers on.
 */
export function OriginalOf({ source, facets, facet }: { source: SourceInfo; facets: ItemFacet[]; facet: string }) {
  const key = source.facets.find((f) => f.key === facet)?.originalFacet;
  const original = key && facets.find((f) => f.facet === key);
  if (!original || original.values.length === 0) return null;
  return (
    <div className="mb-1 flex flex-col gap-0.5">
      <div className="text-xs text-muted-foreground">{label(original.labelKey)}</div>
      <div className="text-sm break-words">{original.values.map(valueName).join(", ")}</div>
    </div>
  );
}

/**
 * What is in the work — its genres, tags, fandom, the facets of the role CONTENT — as plain chips:
 * one the user confirmed filled with a ✓, one the model doubts amber-edged, one the user took away left out.
 * The chances and the answers are the marking's, not the reading's.
 */
export function ContentFacts({ source, facets, except = [] }: { source: SourceInfo; facets: ItemFacet[]; except?: readonly string[] }) {
  const content = facetsOfRole(source, facets, "CONTENT")
    .filter((f) => !except.includes(f.facet))
    .map((f) => ({ ...f, values: f.values.filter((v) => v.corrected !== "REMOVED" && !v.offered) }))
    .filter((f) => f.values.length > 0);
  if (content.length === 0) return null;
  return (
    <div className="flex flex-col gap-2">
      {content.map((f) => (
        <div key={f.facet} className="flex flex-col gap-1">
          <OriginalOf source={source} facets={facets} facet={f.facet} />
          <div className="text-xs text-muted-foreground">{label(f.labelKey)}</div>
          <FacetBadges facets={[f]} />
        </div>
      ))}
    </div>
  );
}
