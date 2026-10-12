import { forwardRef } from "react";
import type { ContentTypeInfo, Grade, ItemSummary } from "@/api/client";
import { ago } from "@/i18n";
import { cn } from "@/lib/utils";
import { SearchJudge } from "@/search/SearchJudge";
import {
  Cover,
  FacetBadges,
  LinkedBadges,
  NumberLine,
  PreviousRating,
  RatingButtons,
  ScoreBadge,
  SearchMatchLine,
  SignalBadges,
  SourceBadge,
} from "./pieces";

export type ItemViewProps = {
  type: ContentTypeInfo;
  summary: ItemSummary;
  selected: boolean;
  onOpen: () => void;
  onSelect: () => void;
  onRate: (grade: Grade | null) => void;
  /** Found by a search: the user's word on whether it answers the line. */
  onJudge?: (relevant: boolean | null) => Promise<unknown>;
};

const SHOWN_FACET_VALUES = 8;

/** A work in the grid: the cover first, as the sites show their banners, with the answer under it. */
export const ItemCard = forwardRef<HTMLElement, ItemViewProps>(function ItemCard({ type, summary, selected, onOpen, onSelect, onRate, onJudge }, ref) {
  return (
    <article
      ref={ref}
      onClick={onOpen}
      onMouseEnter={onSelect}
      className={cn(
        "group flex cursor-pointer flex-col overflow-hidden rounded-xl border bg-card text-card-foreground shadow-xs transition-all hover:shadow-md",
        selected && "ring-2 ring-primary ring-offset-2 ring-offset-background",
      )}
    >
      <div className="relative aspect-[2/1] overflow-hidden">
        <Cover typeId={type.id} summary={summary} className="size-full transition-transform duration-300 group-hover:scale-[1.03]" />
        <div className="pointer-events-none absolute inset-0 bg-gradient-to-t from-black/85 via-black/20 to-transparent" />
        <SourceBadge type={type} source={summary.source} className="absolute top-2 left-2 border-0 bg-background/85 text-foreground shadow-sm" />
        <ScoreBadge type={type} summary={summary} className="absolute top-2 right-2" />
        <div className="absolute inset-x-0 bottom-0 p-3 text-white">
          <h3 className="line-clamp-2 text-base leading-tight font-semibold drop-shadow">{summary.title}</h3>
          {summary.version && (
            <div className="mt-0.5 flex items-baseline gap-2 text-xs text-white/80">
              <span className="truncate font-medium text-white/95">{summary.version}</span>
            </div>
          )}
        </div>
      </div>

      <div className="flex flex-1 flex-col gap-2.5 p-3">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-muted-foreground">
          <span title={summary.updatedAt}>{ago(summary.updatedAt)}</span>
          <NumberLine type={type} summary={summary} />
          <PreviousRating type={type} summary={summary} />
          <SignalBadges source={type.sources.find((s) => s.id === summary.source)} signals={summary.signals} />
        </div>

        {summary.searchMatch && <SearchMatchLine match={summary.searchMatch} className="line-clamp-3" />}
        {onJudge && <SearchJudge value={summary.searchRelevant} onJudge={onJudge} />}

        <FacetBadges facets={summary.facets} limit={SHOWN_FACET_VALUES} />

        <LinkedBadges type={type} linked={summary.linked} className="text-xs text-muted-foreground" />

        <RatingButtons type={type} value={summary.grade as Grade | undefined} onRate={onRate} size="sm" className="mt-auto" />
      </div>
    </article>
  );
});
