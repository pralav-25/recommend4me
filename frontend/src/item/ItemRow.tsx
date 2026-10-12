import { forwardRef } from "react";
import type { Grade } from "@/api/client";
import { ago } from "@/i18n";
import { cn } from "@/lib/utils";
import { SearchJudge } from "@/search/SearchJudge";
import type { ItemViewProps } from "./ItemCard";
import {
  Cover,
  LinkedBadges,
  NumberLine,
  PreviousRating,
  RatingButtons,
  ScoreBadge,
  SearchMatchLine,
  SignalBadges,
  SourceBadge,
} from "./pieces";

/**
 * A work in the compact list: more works on a screen, for going through many quickly. On a
 * phone the grades go under the work, on a line of their own; there is no room for them beside it.
 */
export const ItemRow = forwardRef<HTMLElement, ItemViewProps>(function ItemRow({ type, summary, selected, onOpen, onSelect, onRate, onJudge }, ref) {
  const facetLine = summary.facets
    .flatMap((f) => f.values.filter((v) => v.corrected !== "REMOVED").map((v) => v.name))
    .join(" · ");
  return (
    <article
      ref={ref}
      onClick={onOpen}
      onMouseEnter={onSelect}
      className={cn(
        "flex cursor-pointer flex-wrap items-center gap-2 rounded-lg border bg-card p-2 transition-colors hover:bg-muted/50 md:flex-nowrap md:gap-3",
        selected && "ring-2 ring-primary",
      )}
    >
      <Cover typeId={type.id} summary={summary} className="aspect-[2/1] w-28 shrink-0 rounded-md md:w-40" />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1 md:flex-nowrap">
          <SourceBadge type={type} source={summary.source} />
          {/* On a phone the title has a line of its own, above the badges */}
          <h3 className="order-first min-w-0 basis-full truncate font-semibold md:order-none md:basis-auto">{summary.title}</h3>
          {summary.version && <span className="shrink-0 text-sm text-muted-foreground">{summary.version}</span>}
        </div>
        <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <span title={summary.updatedAt}>{ago(summary.updatedAt)}</span>
          <NumberLine type={type} summary={summary} />
          <PreviousRating type={type} summary={summary} />
          <SignalBadges source={type.sources.find((s) => s.id === summary.source)} signals={summary.signals} />
          <LinkedBadges type={type} linked={summary.linked} />
        </div>
        {summary.searchMatch ? (
          <SearchMatchLine match={summary.searchMatch} className="mt-1 line-clamp-2" />
        ) : (
          facetLine && <div className="mt-1 hidden truncate text-xs text-muted-foreground/80 sm:block">{facetLine}</div>
        )}
        {onJudge && <SearchJudge value={summary.searchRelevant} onJudge={onJudge} />}
      </div>
      <ScoreBadge type={type} summary={summary} />
      <RatingButtons type={type} value={summary.grade as Grade | undefined} onRate={onRate} size="sm" className="w-full shrink-0 md:w-80" />
    </article>
  );
});
