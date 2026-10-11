import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDownIcon, SearchIcon } from "lucide-react";
import { api, unwrap, type FacetFilter } from "@/api/client";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuCheckboxItem,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

/**
 * What a filter facet shows, kept as the user meant it: the values hidden — every other shows, a
 * value a site adds later too — or, given [shown], those alone — every other hidden, one added
 * later too; and whether the works without any value of it are hidden. The one meant is the
 * shorter at the choice: two fandoms of 361 checked are those two, not 359 others unchecked.
 */
export type FacetChoice = { hidden?: string[]; shown?: string[]; withoutHidden?: boolean };

/** The facets that hide anything, by facet id. */
export type FacetSelection = Record<string, FacetChoice>;

export const NOTHING_HIDDEN: FacetSelection = {};

export function isFiltered(s: FacetSelection): boolean {
  return Object.keys(s).length > 0;
}

/** Whether a value of a facet shows by the choice. */
function isShown(choice: FacetChoice | undefined, key: string): boolean {
  if (!choice) return true;
  return choice.shown ? choice.shown.includes(key) : !(choice.hidden ?? []).includes(key);
}

/**
 * The choice of a facet that shows the values [shown] of [all], written as the shorter list of the
 * two; null when it hides nothing. Asked again of the values a facet has now, it stays what it was
 * meant: the values a site added since show by a list of the hidden, and not by one of the shown.
 */
export function compact(all: string[], shown: Set<string>, withoutHidden: boolean): FacetChoice | null {
  const hidden = all.filter((k) => !shown.has(k));
  if (hidden.length === 0 && !withoutHidden) return null;
  const visible = all.filter((k) => shown.has(k));
  return visible.length < hidden.length ? { shown: visible, withoutHidden } : { hidden, withoutHidden };
}

/** The choices as the list asks for them: each of the values the facets have now, the shorter list. */
export function facetQuery(selection: FacetSelection, facets: FacetFilter[] | undefined) {
  return Object.entries(selection).flatMap(([facet, choice]) => {
    const values = facets?.find((f) => f.id === facet)?.values.map((v) => v.key);
    // The values not known yet: the choice as it is kept
    const asked = values ? compact(values, new Set(values.filter((k) => isShown(choice, k))), choice.withoutHidden === true) : choice;
    return asked ? [{ facet, ...asked }] : [];
  });
}

/**
 * A remembered selection as it is kept now: of the old form — every value hidden as
 * "<facet id>=<value key>", and the facets hiding their works without values — the hidden values
 * of each facet; without the facets of the old "<source>:<key>" ids, which the backend knows no more.
 */
export function validSelection(s: unknown): FacetSelection {
  const current = (facetId: string) => !facetId.includes(":");
  if (s && typeof s === "object" && Array.isArray((s as { hidden?: unknown }).hidden)) {
    const old = s as { hidden: string[]; hiddenWithout?: string[] };
    const out: FacetSelection = {};
    old.hidden.forEach((ref) => {
      const at = ref.indexOf("=");
      const facet = ref.slice(0, Math.max(0, at));
      if (at > 0 && current(facet)) (out[facet] ??= { hidden: [] }).hidden!.push(ref.slice(at + 1));
    });
    (old.hiddenWithout ?? []).filter(current).forEach((facet) => ((out[facet] ??= { hidden: [] }).withoutHidden = true));
    return out;
  }
  return (s as FacetSelection | null) ?? NOTHING_HIDDEN;
}

/** A list this long gets a line to find a value in. */
const FINDABLE = 15;

export function useFacets(type: string) {
  return useQuery({
    queryKey: ["facets", type],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/facets", { params: { path: { type } } })),
  });
}

/**
 * A drop-down list of checkboxes for every filter facet of the content type, all checked at
 * first. A work shows when, in every facet, it has a checked value — or none of the facet's, with
 * that line checked.
 */
export function FacetFilters({ type, value, onChange }: { type: string; value: FacetSelection; onChange: (v: FacetSelection) => void }) {
  const facets = useFacets(type);
  return (
    <div className="flex flex-wrap items-center gap-1">
      {(facets.data ?? []).map((f) => (
        <FacetDropdown key={f.id} facet={f} value={value} onChange={onChange} />
      ))}
    </div>
  );
}

function FacetDropdown({ facet, value, onChange }: { facet: FacetFilter; value: FacetSelection; onChange: (v: FacetSelection) => void }) {
  const [find, setFind] = useState("");
  const hasWithout = facet.noneLabel !== undefined && facet.noneLabel !== "";
  const withoutLabel = facet.noneLabel ?? "";
  const choice = value[facet.id];
  const keys = facet.values.map((v) => v.key);
  const shownValues = facet.values.filter((v) => isShown(choice, v.key));
  const withoutShown = !choice?.withoutHidden;
  const total = facet.values.length + (hasWithout ? 1 : 0);
  const shown = shownValues.length + (hasWithout && withoutShown ? 1 : 0);
  // All shown says nothing; one shown is named; else how many of how many
  const summary =
    shown === total ? null : shown === 0 ? "ничего" : shown === 1 ? (shownValues[0]?.name ?? withoutLabel) : `${shown} из ${total}`;

  // The facet's own choice written anew, the shorter list; the other facets' kept
  const choose = (visible: Set<string>, withoutHidden: boolean) => {
    const rest = { ...value };
    delete rest[facet.id];
    const next = compact(keys, visible, withoutHidden);
    onChange(next ? { ...rest, [facet.id]: next } : rest);
  };
  const visible = () => new Set(shownValues.map((v) => v.key));
  const toggle = (key: string, on: boolean) => {
    const v = visible();
    if (on) v.add(key);
    else v.delete(key);
    choose(v, !withoutShown);
  };
  const toggleWithout = (on: boolean) => choose(visible(), !on);
  const all = () => choose(new Set(keys), false);
  const none = () => choose(new Set(), hasWithout);

  const needle = find.trim().toLowerCase();
  const listed = needle ? facet.values.filter((v) => v.name.toLowerCase().includes(needle)) : facet.values;

  if (facet.values.length === 0 && !hasWithout) return null;
  return (
    <DropdownMenu onOpenChange={(open) => !open && setFind("")}>
      <DropdownMenuTrigger render={<Button variant="outline" size="sm" className={cn(summary && "border-primary/60")} />}>
        <span className="max-w-44 truncate">
          {facet.label}
          {summary && <span className="font-semibold">: {summary}</span>}
        </span>
        <ChevronDownIcon className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="max-h-[70vh] w-auto min-w-56">
        <div className="flex gap-1">
          <DropdownMenuItem className="flex-1 justify-center" onClick={all} closeOnClick={false} disabled={shown === total}>
            Всё
          </DropdownMenuItem>
          <DropdownMenuItem className="flex-1 justify-center" onClick={none} closeOnClick={false} disabled={shown === 0}>
            Ничего
          </DropdownMenuItem>
        </div>
        {facet.values.length > FINDABLE && (
          <div className="relative px-1 py-1">
            <SearchIcon className="pointer-events-none absolute top-1/2 left-3 size-3.5 -translate-y-1/2 text-muted-foreground" />
            <input
              value={find}
              onChange={(e) => setFind(e.target.value)}
              // The menu's own keys (type-ahead, arrows) must not take the letters typed here
              onKeyDown={(e) => e.key !== "Escape" && e.stopPropagation()}
              placeholder="Найти…"
              className="h-7 w-full rounded-md border border-input bg-transparent pr-2 pl-7 text-sm outline-none focus-visible:border-ring"
            />
          </div>
        )}
        <DropdownMenuSeparator />
        {listed.map((v) => (
          <DropdownMenuCheckboxItem key={v.key} checked={isShown(choice, v.key)} onCheckedChange={(on) => toggle(v.key, on)} closeOnClick={false}>
            <span className="flex-1">{v.name}</span>
            <span className="text-xs tabular-nums text-muted-foreground">{v.items}</span>
          </DropdownMenuCheckboxItem>
        ))}
        {hasWithout && !needle && (
          <DropdownMenuCheckboxItem checked={withoutShown} onCheckedChange={toggleWithout} closeOnClick={false}>
            <span className="flex-1 italic">{withoutLabel}</span>
            <span className="text-xs tabular-nums text-muted-foreground">{facet.itemsWithout}</span>
          </DropdownMenuCheckboxItem>
        )}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
