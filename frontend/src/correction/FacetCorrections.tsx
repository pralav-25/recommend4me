import { useEffect, useId, useState } from "react";
import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { CheckIcon, Loader2Icon, PlusIcon, SearchIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type FacetValueInfo, type ItemFacet, type ItemRef, type SourceInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ChanceMark, unlikely } from "@/facet/FacetValueMarks";
import { decided, useStableOrder } from "@/facet/valueOrder";
import { label, valueName } from "@/i18n/label";
import { OriginalOf } from "@/item/WorkFacts";
import { cn } from "@/lib/utils";
import { SuggestedValues } from "@/suggestion/Suggestions";
import { useCandidates, useSuggestions } from "@/suggestion/useSuggestions";
import { FacetAnswers } from "./FacetAnswers";
import { EditButton } from "./FieldEditor";
import type { Corrections } from "./useCorrections";

/** How many values of a facet the line of a new value offers. */
const OFFERED = 30;

/** How many more candidates a press of "…" shows. */
const MORE_STEP = 5;

/**
 * Every facet of the item, the user's corrections included. Above the values of a facet the site
 * writes as one line, that line as the site has it. Each value says how likely the model finds it
 * (until the user answers on it) and takes the user's ✓ or ✕; a value the user added is outlined,
 * one the model worked out dotted, one the user took away crossed out. In editing a value of any
 * facet of the source can be added. Below them the application's suggestions to answer. The
 * facets of [except] are answered elsewhere: neither their values, nor their suggestions, nor
 * the adding of them are here.
 */
export function FacetCorrections({
  item,
  source,
  facets: allFacets,
  corrections,
  except = [],
}: {
  item: ItemRef;
  source?: SourceInfo;
  facets: ItemFacet[];
  corrections: Corrections;
  except?: readonly string[];
}) {
  const { t } = useTranslation("correction");
  const [editing, setEditing] = useState(false);
  const suggestions = useSuggestions(item, source);
  const facets = allFacets.filter((f) => !except.includes(f.facet));
  // The application's layer is corrected; the site's facets are its word, shown as they are — its
  // word on what the layer works out (the role ORIGINAL) above all of them, not here
  const originals = new Set(source?.facets.filter((f) => f.role === "ORIGINAL").map((f) => f.key));
  const layer = facets.filter((f) => f.editable);
  const site = facets.filter((f) => !f.editable && !originals.has(f.facet));
  const suggested = suggestions.data?.filter((s) => !except.includes(s.facet));
  const addable = source && { ...source, facets: source.facets.filter((f) => f.editable && !except.includes(f.key)) };
  return (
    <section className="flex flex-col gap-3">
      <div className="flex items-center gap-1">
        <h4 className="text-sm font-semibold">{t("FacetCorrections.heading")}</h4>
        {addable && addable.facets.length > 0 && (
          <EditButton onClick={() => setEditing(!editing)} title={editing ? t("FacetCorrections.finishAdding") : t("FacetCorrections.addValue")} />
        )}
      </div>
      {layer.length === 0 && !editing && <div className="text-sm text-muted-foreground">—</div>}
      {layer.map((f) => (
        <div key={f.facet}>
          {source && <OriginalOf source={source} facets={allFacets} facet={f.facet} />}
          <FacetLine facet={f} item={item} source={source} corrections={corrections} />
        </div>
      ))}
      {editing && addable && <AddFacetValue source={addable} corrections={corrections} />}
      {suggested && <SuggestedValues suggestions={suggested} corrections={corrections} />}
      {site.length > 0 && (
        <div className="flex flex-col gap-3 border-t pt-3">
          <h4 className="text-sm font-semibold">{t("FacetCorrections.fromSite")}</h4>
          {site.map((f) => (
            <FacetLine key={f.facet} facet={f} />
          ))}
        </div>
      )}
    </section>
  );
}

/**
 * A facet of the work with its values, in the order first shown: answered on when [corrections]
 * are given — with "✓ all" for every value shown the user has not answered on, and "…" for more
 * candidates of the [item] — shown as it is when not.
 */
function FacetLine({ facet: f, item, source, corrections }: { facet: ItemFacet; item?: ItemRef; source?: SourceInfo; corrections?: Corrections }) {
  const { t } = useTranslation("correction");
  const values = useStableOrder(f.values);
  const [searching, setSearching] = useState(false);
  // A value of this facet alone found by its name, when the facet takes values the user adds
  const own = source?.facets.filter((d) => d.key === f.facet && d.editable) ?? [];
  const addable = source && own.length > 0 ? { ...source, facets: own } : undefined;
  // The work's values the user has not answered on — not those only offered, nor those it has by a tag under them
  const open = f.values.filter((v) => !decided(v) && !v.offered && !v.implied);
  const confirmAll = async () => {
    for (const v of open) await corrections!.setFacet(f.facet, { key: v.key }, true);
  };
  return (
    <div>
      <div className="mb-1 flex items-center gap-2 text-xs text-muted-foreground">
        {label(f.labelKey)}
        {corrections && open.length > 1 && (
          <AsyncButton variant="outline" size="xs" className="h-5 px-1.5 text-[11px]" onClick={confirmAll} title={t("FacetLine.confirmAllHint")}>
            {t("FacetLine.confirmAll", { n: open.length })}
          </AsyncButton>
        )}
      </div>
      <div className="flex flex-wrap gap-1">
        {values.map((v) => (
          <FacetValue key={v.key} facet={f.facet} value={v} corrections={corrections} />
        ))}
        {item && corrections && <MoreCandidates item={item} facet={f.facet} shown={f.values} corrections={corrections} />}
        {corrections && addable && <SearchButton onClick={() => setSearching(!searching)} title={t("FacetLine.search")} />}
      </div>
      {searching && corrections && addable && <AddFacetValue source={addable} corrections={corrections} onClose={() => setSearching(false)} />}
    </div>
  );
}

/** A small 🔍 after a line of values, beside "…": a value found by its name and added. */
export function SearchButton({ onClick, title }: { onClick: () => void; title: string }) {
  return (
    <Button variant="outline" size="xs" className="h-5 px-1.5" onClick={onClick} title={title} aria-label={title}>
      <SearchIcon className="size-3" />
    </Button>
  );
}

/**
 * "…" after a facet's values: each press shows the next [MORE_STEP] likeliest candidates the line
 * does not show yet ([shown]), dashed, to answer on — one answered on gives its place to the next —
 * until there are none. How many stays while the work is open.
 */
export function MoreCandidates({
  item,
  facet,
  shown,
  corrections,
}: {
  item: ItemRef;
  facet: string;
  shown: { key: string }[];
  corrections: Corrections;
}) {
  const { t } = useTranslation("correction");
  const [asked, setAsked] = useState(0);
  const candidates = useCandidates(item, facet, asked > 0);
  const keys = new Set(shown.map((v) => v.key));
  const rest = (candidates.data ?? []).filter((c) => !keys.has(c.key));
  if (candidates.isError) return null;
  const done = candidates.data !== undefined && rest.length <= asked;
  return (
    <>
      {rest.slice(0, asked).map((c) => (
        <FacetValue key={c.key} facet={facet} value={{ key: c.key, name: c.name, nameKey: c.nameKey, chance: c.chance ?? undefined, offered: true }} corrections={corrections} />
      ))}
      {!done && (
        <Button
          variant="outline"
          size="xs"
          className="h-5 px-1.5"
          onClick={() => setAsked(asked + MORE_STEP)}
          disabled={candidates.isFetching}
          title={t("MoreCandidates.showMore", { count: MORE_STEP })}
        >
          {candidates.isFetching ? <Loader2Icon className="animate-spin" /> : "…"}
        </Button>
      )}
    </>
  );
}

/** The keys of the titles of a value by the user's answer on it, translated at render. */
const CORRECTED_TITLE = {
  ADDED: "FacetValue.added",
  CONFIRMED: "FacetValue.confirmed",
  REMOVED: "FacetValue.removed",
} as const satisfies Record<NonNullable<FacetValueInfo["corrected"]>, string>;

/**
 * A value of the work's facet, its state told by its look and an icon, never by colour alone:
 *
 * - the user's — confirmed or added: filled, a ✓ before the name, no chance; the word taken back
 *   by the button shown when pointed at;
 * - not answered on: outlined on white, the model's chance in small print, ✓ ✕; dashed when only
 *   offered, not the work's yet;
 * - taken away by the user: faded, crossed out, ↺.
 *
 * A value of a facet of the site ([corrections] not given) has no answers.
 */
export function FacetValue({ facet, value, corrections }: { facet: string; value: FacetValueInfo; corrections?: Corrections }) {
  const { t } = useTranslation("correction");
  const mine = value.corrected === "CONFIRMED" || value.corrected === "ADDED";
  const removed = value.corrected === "REMOVED";
  const open = corrections && !value.corrected;
  return (
    <Badge
      variant="outline"
      className={cn(
        "group pr-0.5 font-normal",
        mine && "border-yes/50 bg-yes/15 text-foreground",
        removed && "border-dashed text-muted-foreground opacity-70",
        open && "bg-background",
        open && unlikely(value) && "border-maybe/60",
        // Only offered, not the work's yet: dashed, as a suggestion
        open && value.offered && "border-dashed",
        !corrections && "bg-muted/40",
      )}
      title={value.corrected ? t(CORRECTED_TITLE[value.corrected]) : value.implied ? t("FacetValue.implied") : undefined}
    >
      {mine && <CheckIcon className="size-3 text-yes" aria-label={t("FacetValue.yourAnswerYes")} />}
      {/* Given by a tag under it: marked as such, not by a chance of its own */}
      {value.implied && !value.corrected && <span className="text-muted-foreground" aria-label={t("FacetValue.fromSubtag")}>↑</span>}
      <span className={cn(removed && "line-through")}>{valueName(value)}</span>
      <ChanceMark value={value} />
      {corrections && <FacetAnswers facet={facet} value={value} corrections={corrections} />}
    </Badge>
  );
}

/**
 * Adding a value: the facet from the source's facets, the value typed — the facet's values the
 * site or the user gave are offered as it is typed and one picked is taken with its key; a new
 * one is sent by its name alone, and the backend makes its key. Of one facet alone, no choice of
 * it; with [onClose], closed by its ✕ or Escape.
 */
function AddFacetValue({ source, corrections, onClose }: { source: SourceInfo; corrections: Corrections; onClose?: () => void }) {
  const { t } = useTranslation("correction");
  const facets = source.facets;
  const [facetKey, setFacetKey] = useState(facets[0]?.key ?? "");
  const [name, setName] = useState("");
  const [query, setQuery] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setQuery(name.trim()), 250);
    return () => clearTimeout(timer);
  }, [name]);
  const listId = useId();
  const facet = facets.find((f) => f.key === facetKey) ?? facets[0];
  const known = useQuery({
    queryKey: ["facet-values", source.id, facet?.key, query],
    enabled: facet !== undefined,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/sources/{source}/facets/{facet}/values", {
          params: { path: { source: source.id, facet: facet!.key }, query: { query: query || undefined, limit: OFFERED } },
        }),
      ),
    // The offers do not blink away at every letter
    placeholderData: keepPreviousData,
  });
  if (!facet) return null;
  const values = known.data ?? [];
  const typed = name.trim();
  // The values kept from another facet only stand in while its own come: never taken by key
  // Found by the name shown as well as by the data's own: of a value of the application's, they differ
  const existing = known.isPlaceholderData
    ? undefined
    : values.find((v) => [v.name, valueName(v)].some((n) => n.toLowerCase() === typed.toLowerCase()));
  const isNew = typed !== "" && query === typed && known.isSuccess && !known.isPlaceholderData && !known.isFetching && !existing;

  const add = async () => {
    await corrections.setFacet(facet.key, existing ? { key: existing.key } : { name: typed }, true);
    setName("");
  };

  return (
    <div className="mt-1 flex flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
      <div className="flex items-center justify-between text-xs text-muted-foreground">
        {facets.length === 1 ? t("AddFacetValue.headingOf", { facet: label(facet.labelKey) }) : t("AddFacetValue.heading")}
        {onClose && (
          <Button variant="ghost" size="icon-xs" onClick={onClose} title={t("AddFacetValue.close")}>
            <XIcon />
          </Button>
        )}
      </div>
      {facets.length > 1 && (
        <select
          value={facet.key}
          onChange={(e) => setFacetKey(e.target.value)}
          className="h-8 rounded-lg border border-input bg-transparent px-2 text-sm outline-none focus-visible:border-ring dark:bg-input/30"
        >
          {facets.map((f) => (
            <option key={f.key} value={f.key}>
              {label(f.labelKey)}
            </option>
          ))}
        </select>
      )}
      <div className="flex gap-1">
        <Input
          autoFocus={onClose !== undefined}
          value={name}
          onChange={(e) => setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && typed) add().catch(() => {});
            if (e.key === "Escape") onClose?.();
          }}
          list={listId}
          placeholder={t("AddFacetValue.placeholder")}
          className="h-8"
        />
        <datalist id={listId}>
          {values.map((v) => (
            <option key={v.key} value={valueName(v)} />
          ))}
        </datalist>
        <AsyncButton size="icon" variant="outline" onClick={add} disabled={!typed} title={existing ? t("AddFacetValue.add") : t("AddFacetValue.addNew")} icon={<PlusIcon />} />
      </div>
      {isNew && <div className="text-xs text-muted-foreground">{t("AddFacetValue.newValue")}</div>}
    </div>
  );
}
