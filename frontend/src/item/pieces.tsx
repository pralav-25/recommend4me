import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import type { TFunction } from "i18next";
import { CheckIcon, StarIcon } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import {
  api,
  pictureSrc,
  unwrap,
  type ContentTypeInfo,
  type FacetValueInfo,
  type Grade,
  type ItemFacet,
  type ItemSummary,
  type LinkedItem,
  type SearchMatch,
  type SignalInfo,
  type SourceInfo,
} from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { ALL_GRADES, gradeLabel, typeIcon } from "@/contenttype/types";
import { shownChance, unlikely } from "@/facet/FacetValueMarks";
import { compact, number, percent, score } from "@/i18n/format";
import { label, sourceShort, sourceTitle, valueName } from "@/i18n/label";
import { placeName } from "@/part/place";
import { useLadder } from "@/model/useModel";
import { cn } from "@/lib/utils";
import { TagNameLink } from "@/tag/TagNameLink";
import { useMarkInterest } from "./useMarkInterest";

/** Keys 1..5 give that grade. */
export const KEY_GRADES: Record<string, Grade> = { "1": 1, "2": 2, "3": 3, "4": 4, "5": 5 };

/**
 * One colour per grade, red to green. The chosen one is IMPORTANT (`!`): the outline button
 * paints its own background in the dark theme (`dark:bg-input/30`), a variant that outranks a
 * plain class, and the chosen grade looked exactly like the others there.
 */
const ACTIVE: Record<Grade, string> = {
  1: "bg-grade-1! text-white! border-grade-1! hover:bg-grade-1/90!",
  2: "bg-grade-2! text-black! border-grade-2! hover:bg-grade-2/90!",
  3: "bg-grade-3! text-black! border-grade-3! hover:bg-grade-3/90!",
  4: "bg-grade-4! text-black! border-grade-4! hover:bg-grade-4/90!",
  5: "bg-grade-5! text-white! border-grade-5! hover:bg-grade-5/90!",
};

const HOVER: Record<Grade, string> = {
  1: "hover:border-grade-1 hover:text-grade-1",
  2: "hover:border-grade-2 hover:text-grade-2",
  3: "hover:border-grade-3 hover:text-grade-3",
  4: "hover:border-grade-4 hover:text-grade-4",
  5: "hover:border-grade-5 hover:text-grade-5",
};

export const GRADE_TEXT: Record<Grade, string> = {
  1: "text-grade-1",
  2: "text-grade-2",
  3: "text-grade-3",
  4: "text-grade-4",
  5: "text-grade-5",
};

/**
 * The five grades; pressing the chosen one again takes the grade back. [labels] adds the
 * content type's words to the digits where there is room (the item dialog), cards show digits only.
 */
export function RatingButtons({
  type,
  value,
  onRate,
  size = "default",
  labels = false,
  className,
}: {
  type: ContentTypeInfo;
  value?: Grade;
  onRate: (grade: Grade | null) => void;
  size?: "sm" | "default" | "lg";
  labels?: boolean;
  className?: string;
}) {
  const { t } = useTranslation("item");
  const ladder = useLadder(type.id);
  return (
    <div className={cn("grid grid-cols-5 gap-1", className)}>
      {ALL_GRADES.map((g) => (
        <Tooltip key={g}>
          <TooltipTrigger
            render={
              <Button
                variant="outline"
                size={size}
                className={cn("w-full min-w-0 px-1", value === g ? ACTIVE[g] : HOVER[g])}
                onClick={(e) => {
                  e.stopPropagation();
                  onRate(value === g ? null : g);
                }}
              />
            }
          >
            <span className="font-semibold tabular-nums">{g}</span>
            {/* A phone has room for the digits only: a cut word says nothing */}
            {labels && <span className="hidden truncate text-xs font-normal sm:inline">{gradeLabel(type.id, g)}</span>}
            {/* Where the grade stands on the scale the predictions are shown on */}
            {labels && ladder[g] !== undefined && (
              <span className="inline-flex shrink-0 items-center gap-0.5 text-xs font-normal tabular-nums opacity-70">
                <StarIcon className="size-3 fill-current" />
                {score(ladder[g])}
              </span>
            )}
          </TooltipTrigger>
          <TooltipContent>
            {value === g ? t("RatingButtons.takeBack") : gradeLabel(type.id, g)}
            {value !== g && ladder[g] !== undefined && ` ${t("RatingButtons.onScale", { score: score(ladder[g]) })}`} ·{" "}
            <Trans t={t} i18nKey="RatingButtons.key" values={{ grade: g }} components={{ kbd: <kbd className="font-mono" /> }} />
          </TooltipContent>
        </Tooltip>
      ))}
    </div>
  );
}

/**
 * Whether the work is interesting to read at all — beside how good it is: the user's mark, pressed
 * again to take it back; without one the model's chance is shown. A grade says nothing of it. The
 * list sorted by the formula multiplies the prediction by the chance.
 */
export function InterestButtons({
  summary,
  size = "sm",
  labels = false,
  className,
}: {
  summary: ItemSummary;
  size?: "sm" | "default" | "lg";
  /** The words beside the faces, where there is room. */
  labels?: boolean;
  className?: string;
}) {
  const { t } = useTranslation("item");
  const mark = useMarkInterest();
  const chance = summary.interest == null ? summary.interestChance : undefined;
  const press = (value: boolean) => (e: React.MouseEvent) => {
    e.stopPropagation();
    mark.mutate({ summary, interesting: summary.interest === value ? null : value });
  };
  return (
    <div className={cn("flex items-center gap-1", className)}>
      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant="outline"
              size={size}
              aria-pressed={summary.interest === true}
              aria-label={t("InterestButtons.interesting")}
              className={cn(
                "min-w-0 px-2",
                summary.interest === true ? "border-emerald-600! bg-emerald-600! text-white! hover:bg-emerald-600/90!" : "hover:border-emerald-600 hover:text-emerald-600",
              )}
              onClick={press(true)}
            />
          }
        >
          {/* Not a thumb: a thumb says liked or not, and interest is another thing */}
          <span aria-hidden>🤔</span>
          {labels && <span>{t("InterestButtons.interesting")}</span>}
        </TooltipTrigger>
        <TooltipContent>
          {summary.interest === true ? t("InterestButtons.takeBackInteresting") : t("InterestButtons.interestingHint")}
        </TooltipContent>
      </Tooltip>
      <Tooltip>
        <TooltipTrigger
          render={
            <Button
              variant="outline"
              size={size}
              aria-pressed={summary.interest === false}
              aria-label={t("InterestButtons.dull")}
              className={cn(
                "min-w-0 px-2",
                summary.interest === false ? "border-grade-1! bg-grade-1! text-white! hover:bg-grade-1/90!" : "hover:border-grade-1 hover:text-grade-1",
              )}
              onClick={press(false)}
            />
          }
        >
          <span aria-hidden>🥱</span>
          {labels && <span>{t("InterestButtons.dull")}</span>}
        </TooltipTrigger>
        <TooltipContent>
          {summary.interest === false ? t("InterestButtons.takeBackDull") : t("InterestButtons.dullHint")}
        </TooltipContent>
      </Tooltip>
      {chance != null && (
        <Tooltip>
          <TooltipTrigger render={<span className="text-xs text-muted-foreground tabular-nums" />}>{percent(chance)}</TooltipTrigger>
          <TooltipContent>{t("InterestButtons.chance", { chance: percent(chance) })}</TooltipContent>
        </Tooltip>
      )}
    </div>
  );
}

const TONE: Record<Grade, string> = {
  1: "bg-grade-1 text-white",
  2: "bg-grade-2 text-black",
  3: "bg-grade-3 text-black",
  4: "bg-grade-4 text-black",
  5: "bg-grade-5 text-white",
};

/** Colour of a score: that of the grade whose place on the user's scale is nearest; evenly spread before training. */
export function scoreTone(value: number, ladder: Partial<Record<Grade, number>>): string {
  const places = ALL_GRADES.map((g) => [g, ladder[g] ?? ((g - 1) * 10) / (ALL_GRADES.length - 1)] as const);
  const nearest = places.reduce((a, b) => (Math.abs(b[1] - value) < Math.abs(a[1] - value) ? b : a));
  return TONE[nearest[0]];
}

/** A score shown on 0..10: above it the scale goes on, and the badge says so; below it is simply 0. */
export function shownScore(value: number): string {
  if (value > 10) return "10+";
  return score(Math.max(0, value));
}

export function ScoreBadge({ type, summary, className }: { type: ContentTypeInfo; summary: ItemSummary; className?: string }) {
  const { t } = useTranslation("item");
  const p = summary.prediction;
  const ladder = useLadder(type.id);
  return (
    <Tooltip>
      <TooltipTrigger
        render={
          <span
            className={cn(
              "inline-flex h-7 min-w-14 items-center justify-center gap-1 rounded-full px-2 text-sm font-semibold tabular-nums shadow-sm",
              p ? scoreTone(p.score, ladder) : "bg-background/80 text-muted-foreground",
              className,
            )}
          />
        }
      >
        <StarIcon className="size-3.5 fill-current" />
        {p ? shownScore(p.score) : "—"}
      </TooltipTrigger>
      <TooltipContent>
        {summary.heldOut ? (
          // A graded work: what the model that had not seen it says, and why — the model of every grade learnt it
          <div className="flex w-56 flex-col">
            <div className="font-semibold">{t("ScoreBadge.heldOut", { score: score(Math.max(0, summary.heldOut.score)) })}</div>
            <ScoreWhy summary={summary} />
          </div>
        ) : p ? (
          <div className="flex w-56 flex-col">
            <div className="font-semibold">{t("ScoreBadge.place", { score: score(Math.max(0, p.score)) })}</div>
            <ScoreWhy summary={summary} />
          </div>
        ) : (
          t("ScoreBadge.noPrediction")
        )}
      </TooltipContent>
    </Tooltip>
  );
}

/** How many parts of the explanation a score's tooltip names. */
const WHY_PARTS = 3;

/** Why in short, in a score's tooltip: the parts that move the work most; asked when the tooltip opens. */
function ScoreWhy({ summary }: { summary: ItemSummary }) {
  const { t } = useTranslation("item");
  const why = useQuery({
    queryKey: ["explanation", summary.source, summary.item],
    queryFn: async () =>
      unwrap(await api.GET("/api/items/{source}/{item}/explanation", { params: { path: { source: summary.source, item: summary.item } } })),
  });
  if (why.isLoading) return <div className="opacity-70">{t("ScoreWhy.loading")}</div>;
  const parts = (why.data ?? []).slice(0, WHY_PARTS);
  if (parts.length === 0) return null;
  return (
    <ul className="mt-1">
      {parts.map((g) => (
        <li key={g.part} className="flex justify-between gap-3">
          <span className="min-w-0 truncate">{label(g.labelKey)}</span>
          <span className="tabular-nums">
            {g.contribution > 0 ? "+" : ""}
            {g.contribution.toFixed(1)}
          </span>
        </li>
      ))}
    </ul>
  );
}

const CORRECTED_NOTE = {
  ADDED: "FacetBadges.added",
  CONFIRMED: "FacetBadges.confirmed",
  REMOVED: "FacetBadges.removed",
} as const satisfies Record<NonNullable<FacetValueInfo["corrected"]>, string>;

/** The title of a value's chip on a card: its facet, the user's word or the model's chance, and whether the model gave it. */
function facetValueTitle(t: TFunction<"item">, facet: ItemFacet, value: FacetValueInfo): string {
  const chance = shownChance(value);
  return (
    label(facet.labelKey) +
    (value.corrected ? ` · ${t(CORRECTED_NOTE[value.corrected])}` : "") +
    (chance !== undefined ? ` · ${t("FacetBadges.chance", { chance: percent(chance) })}` : "") +
    (unlikely(value) ? ` — ${t("FacetBadges.unlikely")}` : "") +
    (value.inferred ? ` · ${t("FacetBadges.inferred")}` : "") +
    (value.draftedFrom != null && !value.corrected ? ` · ${t("FacetBadges.drafted", { from: value.draftedFrom })}` : "") +
    (value.implied ? ` · ${t("FacetBadges.implied")}` : "")
  );
}

/**
 * The facets shown on a card, value by value; a value the user took away is crossed out, one the
 * user confirmed or added is filled with a ✓, one the model more likely says no to is
 * amber-edged. [limit] cuts the list, the rest go into "+N" with their names in its title.
 */
export function FacetBadges({ facets, limit, className }: { facets: ItemFacet[]; limit?: number; className?: string }) {
  const { t } = useTranslation("item");
  // A value only offered to answer on is not the work's: the cards leave it out
  const values = facets.flatMap((f) => f.values.filter((v) => !v.offered).map((v) => ({ facet: f, value: v })));
  const shown = limit === undefined ? values : values.slice(0, limit);
  const rest = values.slice(shown.length);
  if (values.length === 0) return null;
  return (
    <div className={cn("flex flex-wrap gap-1", className)}>
      {shown.map(({ facet, value }) => (
        <Badge
          key={`${facet.facet}=${value.key}`}
          variant="secondary"
          className={cn(
            "font-normal",
            value.corrected === "REMOVED" && "text-muted-foreground line-through",
            (value.corrected === "ADDED" || value.corrected === "CONFIRMED") && "border-yes/50 bg-yes/15",
            unlikely(value) && "border-maybe/60",
          )}
          title={facetValueTitle(t, facet, value)}
        >
          {(value.corrected === "ADDED" || value.corrected === "CONFIRMED") && <CheckIcon className="size-3 text-yes" />}
          {facet.tags ? <TagNameLink tag={value.key}>{valueName(value)}</TagNameLink> : valueName(value)}
        </Badge>
      ))}
      {rest.length > 0 && (
        <Badge variant="outline" className="font-normal text-muted-foreground" title={rest.map((r) => valueName(r.value)).join(", ")}>
          +{rest.length}
        </Badge>
      )}
    </div>
  );
}

/** The values of a yes/no signal: the signal's name says it all. */
const FLAG = new Set(["", "true", "1", "yes"]);

/**
 * "Liked", "Shelf: reading", "Reaction: funny, sad" — a signal and its values with the names
 * its source gives them; a value may be several joined by ",", a yes/no one shows the name only.
 */
function signalLabel(info: SignalInfo | undefined, key: string, value: string): string {
  const name = info ? label(info.labelKey) : key;
  const values = value
    .split(",")
    .map((v) => v.trim())
    .filter((v) => !FLAG.has(v))
    .map((v) => {
      const nameKey = info?.values[v];
      return nameKey ? label(nameKey) : v;
    });
  return values.length === 0 ? name : `${name}: ${values.join(", ")}`;
}

/** The user's own actions on the site: liked it, put it on a shelf. */
export function SignalBadges({ source, signals, className }: { source?: SourceInfo; signals?: Record<string, string>; className?: string }) {
  const { t } = useTranslation("item");
  const entries = Object.entries(signals ?? {});
  if (entries.length === 0) return null;
  return (
    <span className={cn("inline-flex flex-wrap gap-1", className)}>
      {entries.map(([key, value]) => (
        <Badge key={key} variant="outline" className="border-primary/40 font-normal" title={t("SignalBadges.title")}>
          {signalLabel(source?.signals.find((s) => s.key === key), key, value)}
        </Badge>
      ))}
    </span>
  );
}

/** Where the work comes from, when its content type has several sources. */
export function SourceBadge({ type, source, className }: { type: ContentTypeInfo; source: string; className?: string }) {
  if (type.sources.length < 2) return null;
  return (
    <Badge variant="outline" className={cn("font-normal", className)} title={sourceTitle(source)}>
      {sourceShort(source)}
    </Badge>
  );
}

/** The other items of the same work: the site of each, linking to its page there. */
export function LinkedBadges({ linked, className }: { linked: LinkedItem[]; className?: string }) {
  const { t } = useTranslation("item");
  if (linked.length === 0) return null;
  return (
    <span className={cn("inline-flex flex-wrap items-center gap-1", className)}>
      <span>{t("LinkedBadges.also")}</span>
      {linked.map((l) => (
        <a
          key={`${l.source}/${l.item}`}
          href={l.url}
          target="_blank"
          rel="noreferrer"
          onClick={(e) => e.stopPropagation()}
          title={`${sourceTitle(l.source)}: ${l.title}`}
          className="underline-offset-2 hover:underline"
        >
          {sourceShort(l.source)}
        </a>
      ))}
    </span>
  );
}

/** The numbers of a card with the labels its source gives them: "likes 1.2K". */
export function NumberLine({ type, summary, limit = 3 }: { type: ContentTypeInfo; summary: ItemSummary; limit?: number }) {
  const declared = type.sources.find((s) => s.id === summary.source)?.numbers ?? [];
  const numberLabel = (key: string) => {
    const labelKey = declared.find((n) => n.key === key)?.labelKey;
    return labelKey ? label(labelKey) : key;
  };
  const entries = Object.entries(summary.numbers).slice(0, limit);
  return (
    <>
      {entries.map(([key, value]) => (
        <span key={key} className="whitespace-nowrap" title={`${numberLabel(key)}: ${number(value)}`}>
          <span className="text-muted-foreground/80">{numberLabel(key)}</span> {compact(value)}
        </span>
      ))}
    </>
  );
}

/**
 * What a search found in the work: the field, and the piece of its text with the words found
 * marked; found by meaning alone, the phrase of the field nearest by meaning.
 */
export function SearchMatchLine({ match, className }: { match: SearchMatch; className?: string }) {
  const { t } = useTranslation("item");
  const parts: { text: string; found: boolean }[] = [];
  let at = 0;
  [...match.highlights]
    .sort((a, b) => a.start - b.start)
    .forEach(({ start, end }) => {
      if (start < at) return;
      if (start > at) parts.push({ text: match.text.slice(at, start), found: false });
      parts.push({ text: match.text.slice(start, end), found: true });
      at = end;
    });
  if (at < match.text.length) parts.push({ text: match.text.slice(at), found: false });
  const field = placeName(match);
  return (
    <p className={cn("text-xs leading-snug text-muted-foreground", className)}>
      <span className="font-medium text-foreground/70">{match.byMeaning ? t("SearchMatchLine.byMeaning", { field }) : field}: </span>
      {parts.map((p, i) =>
        p.found ? (
          <mark key={i} className="rounded-sm bg-maybe/35 px-0.5 text-foreground">
            {p.text}
          </mark>
        ) : (
          <span key={i}>{p.text}</span>
        ),
      )}
    </p>
  );
}

/** The grade of an earlier version, when the current one is not graded yet or differs. */
export function PreviousRating({ type, summary }: { type: ContentTypeInfo; summary: ItemSummary }) {
  const { t } = useTranslation("item");
  const g = summary.previousGrade as Grade | undefined;
  if (!g) return null;
  return (
    <Badge
      variant="outline"
      className={GRADE_TEXT[g]}
      title={t("PreviousRating.title", { version: summary.previousGradeVersion ?? "", grade: gradeLabel(type.id, g) })}
    >
      {t("PreviousRating.label", { grade: gradeLabel(type.id, g) })}
    </Badge>
  );
}

/** The cover — the preview, or the original where [full] — or the type's icon when there is none. */
export function Cover({
  typeId,
  summary,
  className,
  full = false,
}: {
  typeId: string;
  summary: ItemSummary;
  className?: string;
  full?: boolean;
}) {
  const [failed, setFailed] = useState(false);
  if (!summary.hasCover || failed) {
    const Icon = typeIcon(typeId);
    return (
      <div className={cn("flex items-center justify-center bg-muted text-muted-foreground", className)}>
        <Icon className="size-10 opacity-40" />
      </div>
    );
  }
  return (
    <img
      src={pictureSrc(summary, 0, full)}
      alt=""
      loading="lazy"
      decoding="async"
      referrerPolicy="no-referrer"
      onError={() => setFailed(true)}
      className={cn("bg-muted object-cover", className)}
    />
  );
}
