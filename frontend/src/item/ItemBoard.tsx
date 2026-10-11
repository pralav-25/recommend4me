import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { keepPreviousData, useInfiniteQuery } from "@tanstack/react-query";
import { InboxIcon, Loader2Icon } from "lucide-react";
import { api, refKey, unwrap, type ContentTypeInfo, type Grade, type ItemSummary, type ItemView } from "@/api/client";
import { Skeleton } from "@/components/ui/skeleton";
import { facetQuery, isFiltered, useFacets, type FacetSelection } from "@/facet/FacetFilters";
import { rangeParams, type NumberRanges } from "@/number/NumberFilters";
import { ItemCard } from "./ItemCard";
import { ItemDialog } from "./ItemDialog";
import { ItemRow } from "./ItemRow";
import { ITEMS } from "./lists";
import { KEY_GRADES } from "./pieces";
import { useRateItem } from "./useRateItem";
import type { SortChoice } from "./sort";

const PAGE_SIZE = 48;

export type Layout = "grid" | "list";

type Props = {
  type: ContentTypeInfo;
  view: ItemView;
  sort: SortChoice;
  search: string;
  facets: FacetSelection;
  /** The sources shown; all when empty. */
  sources: string[];
  /** The ranges of numbers the list is limited to. */
  ranges: NumberRanges;
  layout: Layout;
  onTotal: (total: number | undefined) => void;
};

export function ItemBoard({ type, view, sort, search, facets, sources, ranges, layout, onTotal }: Props) {
  const [selected, setSelected] = useState(0);
  const [opened, setOpened] = useState<number | null>(null);
  const cards = useRef(new Map<string, HTMLElement>());
  const grid = useRef<HTMLDivElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const rate = useRateItem();

  // Every facet's choice the shorter list of the values it has now: two fandoms checked are sent as two
  const facetValues = useFacets(type.id).data;
  const facetChoices = useMemo(() => facetQuery(facets, facetValues), [facets, facetValues]);
  const list = useInfiniteQuery({
    queryKey: [ITEMS, type.id, view, sort, search, facetChoices, sources, ranges],
    initialPageParam: 0,
    queryFn: async ({ pageParam }) =>
      unwrap(
        await api.POST("/api/types/{type}/items", {
          params: { path: { type: type.id } },
          body: {
            view,
            sort: sort.sort,
            sortNumber: sort.sort === "NUMBER" ? sort.number : undefined,
            search: search || undefined,
            facets: facetChoices.length ? facetChoices : undefined,
            sources: sources.length ? sources : undefined,
            ranges: Object.keys(ranges).length ? rangeParams(ranges) : undefined,
            offset: pageParam,
            limit: PAGE_SIZE,
          },
        }),
      ),
    // A new filter or order keeps the list it changes until the new one comes: a checkbox ticked
    // after another blanked the whole board each time
    placeholderData: keepPreviousData,
    getNextPageParam: (last, pages) => {
      const loaded = pages.reduce((n, p) => n + p.items.length, 0);
      return loaded < last.total && last.items.length > 0 ? loaded : undefined;
    },
  });

  // An item may come twice: pages are asked for by offset, and between two pages the order can
  // change under them — a job adds works and re-scores them as it goes. Two items of one work
  // are two cards: a link hides neither
  const items: ItemSummary[] = useMemo(() => {
    const seen = new Set<string>();
    return (list.data?.pages.flatMap((p) => p.items) ?? []).filter((s) => {
      const key = refKey(s);
      return !seen.has(key) && !!seen.add(key);
    });
  }, [list.data]);
  const total = list.data?.pages[0]?.total;
  useEffect(() => onTotal(total), [total, onTotal]);

  const { hasNextPage, isFetchingNextPage, fetchNextPage } = list;
  const more = useCallback(() => {
    if (hasNextPage && !isFetchingNextPage) fetchNextPage();
  }, [hasNextPage, isFetchingNextPage, fetchNextPage]);

  // Endless scrolling: the next page is asked for when the end of the list comes into view
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const observer = new IntersectionObserver((entries) => entries[0].isIntersecting && more(), { rootMargin: "800px" });
    observer.observe(el);
    return () => observer.disconnect();
  }, [more]);

  // The dialog walks the list too: keep a page ahead of it
  useEffect(() => {
    const at = opened ?? selected;
    if (at >= items.length - 6) more();
  }, [opened, selected, items.length, more]);

  useEffect(() => {
    if (selected >= items.length && items.length > 0) setSelected(items.length - 1);
  }, [items.length, selected]);

  useEffect(() => {
    setSelected(0);
    setOpened(null);
  }, [view, sort, search, facets, sources]);

  const onRate = useCallback((summary: ItemSummary, grade: Grade | null) => rate.mutate({ summary, grade }), [rate]);

  /** How many cards stand in a row of the grid, for the up and down arrows. */
  const columns = () => {
    if (layout === "list" || !grid.current) return 1;
    return getComputedStyle(grid.current).gridTemplateColumns.split(" ").length;
  };

  // Keys of the board; the dialog has its own while it is open
  useEffect(() => {
    if (opened !== null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if ((e.target as HTMLElement).closest("input, textarea, select, [role=dialog], [role=menu]")) return;
      const move = (to: number) => {
        e.preventDefault();
        const next = Math.max(0, Math.min(items.length - 1, to));
        setSelected(next);
        const summary = items[next];
        if (summary) cards.current.get(refKey(summary))?.scrollIntoView({ block: "nearest", behavior: "smooth" });
      };
      switch (e.key) {
        case "ArrowRight":
        case "l":
          return move(selected + 1);
        case "ArrowLeft":
        case "h":
          return move(selected - 1);
        case "ArrowDown":
        case "j":
          return move(selected + columns());
        case "ArrowUp":
        case "k":
          return move(selected - columns());
        case "Enter":
        case " ":
          e.preventDefault();
          if (items[selected]) setOpened(selected);
          return;
        default:
          if (KEY_GRADES[e.key] && items[selected]) {
            e.preventDefault();
            onRate(items[selected], KEY_GRADES[e.key]);
          }
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  if (list.isLoading) {
    return (
      <div className="grid grid-cols-[repeat(auto-fill,minmax(20rem,1fr))] gap-4">
        {Array.from({ length: 8 }, (_, i) => (
          <Skeleton key={i} className="aspect-[4/3] rounded-xl" />
        ))}
      </div>
    );
  }
  if (list.isError) return <div className="py-20 text-center text-destructive">Ошибка загрузки: {list.error.message}</div>;
  if (items.length === 0) {
    const allRated = view === "UNRATED" && !search && !isFiltered(facets) && sources.length === 0 && Object.keys(ranges).length === 0;
    return (
      <div className="flex flex-col items-center gap-3 py-24 text-muted-foreground">
        <InboxIcon className="size-12 opacity-50" />
        <div className="text-lg">{allRated ? "Всё оценено" : "Ничего не найдено"}</div>
        {allRated && <div className="text-sm">Загрузите новое кнопкой вверху или откройте страницы сайтов в браузере со слежением</div>}
      </div>
    );
  }

  const View = layout === "grid" ? ItemCard : ItemRow;
  return (
    <>
      <div
        ref={grid}
        className={layout === "grid" ? "grid grid-cols-[repeat(auto-fill,minmax(20rem,1fr))] gap-4" : "flex flex-col gap-2"}
      >
        {items.map((summary, i) => {
          const key = refKey(summary);
          return (
            <View
              key={key}
              ref={(el: HTMLElement | null) => {
                if (el) cards.current.set(key, el);
                else cards.current.delete(key);
              }}
              type={type}
              summary={summary}
              selected={i === selected}
              onSelect={() => setSelected(i)}
              onOpen={() => {
                setSelected(i);
                setOpened(i);
              }}
              onRate={(grade) => onRate(summary, grade)}
            />
          );
        })}
      </div>
      <div ref={sentinel} className="flex justify-center py-8 text-muted-foreground">
        {isFetchingNextPage && <Loader2Icon className="size-6 animate-spin" />}
      </div>
      <ItemDialog
        items={items}
        index={opened}
        total={total ?? items.length}
        onIndex={(i) => {
          setOpened(i);
          if (i !== null) setSelected(i);
        }}
        onRate={onRate}
        ratedLeaves={view === "UNRATED"}
      />
    </>
  );
}
