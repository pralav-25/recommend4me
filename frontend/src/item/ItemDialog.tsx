import { useEffect, useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckIcon, ChevronLeftIcon, ChevronRightIcon, DownloadIcon, ExternalLinkIcon, Loader2Icon, PencilIcon, RefreshCwIcon, XIcon } from "lucide-react";
import { api, sameItem, unwrap, type ContentTypeInfo, type Grade, type ItemDetails, type ItemSummary, type RatingRecord, type SourceInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog";
import { Separator } from "@/components/ui/separator";
import { Skeleton } from "@/components/ui/skeleton";
import { gradeLabel, sourceTitle, useSource } from "@/contenttype/types";
import { FacetCorrections } from "@/correction/FacetCorrections";
import { CorrectedMark, EditButton, FieldEditor } from "@/correction/FieldEditor";
import { Links } from "@/correction/Links";
import { titleField, useCorrections, type Corrections } from "@/correction/useCorrections";
import { formatDate, score } from "@/i18n";
import { cn } from "@/lib/utils";
import { Explanation } from "@/model/Explanation";
import { Parts } from "@/part/Parts";
import { PictureMatches, Pictures } from "@/picture/Pictures";
import { ReviewMatches, Reviews } from "@/review/Reviews";
import { useSuggestions } from "@/suggestion/useSuggestions";
import { FANFIC_FACETS, FanficSection, FanficSummary } from "@/universe/FanficSection";
import { writeItemHash } from "./itemHash";
import { ItemNumbers } from "./ItemNumbers";
import { ItemTexts } from "./ItemTexts";
import { itemKey } from "./lists";
import { Cover, GRADE_TEXT, KEY_GRADES, LinkedBadges, PreviousRating, RatingButtons, ScoreBadge, SignalBadges } from "./pieces";
import { RelatedWorks } from "./RelatedWorks";
import { useHeldOut } from "./useHeldOut";
import { useTakeBackGrade } from "./useRateItem";
import { useRefreshItem } from "./useRefreshItem";
import { Byline, ContentFacts, FactLine } from "./WorkFacts";

type Props = {
  items: ItemSummary[];
  index: number | null;
  total: number;
  onIndex: (index: number | null) => void;
  onRate: (summary: ItemSummary, grade: Grade | null) => void;
  /** The graded work leaves the list, so the next one takes its place under the same index. */
  ratedLeaves: boolean;
};

/**
 * One work at a time, for going through the list: 1..5 grade and move on, ←/→ step back and
 * forth without answering, o opens the site, e switches the marking on and off, Esc closes.
 *
 * TWO WAYS TO LOOK AT A WORK. To decide — the facts it is chosen by, why the prediction is what
 * it is, the volumes before it, and the grade with its reasons: the 👍 and 👎 of the pictures and
 * reviews that would make the user pick the work or drop it. To mark — every value with the
 * model's chance and the user's yes and no, the texts and numbers to correct, what the marks are
 * like elsewhere, the work to link. The way chosen stays from work to work.
 */
export function ItemDialog({ items, index, total, onIndex, onRate, ratedLeaves }: Props) {
  const summary = index !== null ? items[index] : undefined;
  const of = useSource(summary?.source);
  const [marking, setMarking] = useState(false);

  useEffect(() => {
    if (index !== null && index >= items.length) onIndex(items.length > 0 ? items.length - 1 : null);
  }, [index, items.length, onIndex]);

  // The address names the open work, for a link to it; closing the dialog takes it out
  const source = summary?.source;
  const item = summary?.item;
  useEffect(() => {
    if (source === undefined || item === undefined) return;
    writeItemHash({ source, item });
    return () => writeItemHash(null);
  }, [source, item]);

  const rate = (grade: Grade | null) => {
    if (!summary || index === null) return;
    onRate(summary, grade);
    if (grade !== null && !ratedLeaves) onIndex(Math.min(index + 1, items.length - 1));
  };

  useEffect(() => {
    if (!summary || index === null) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if ((e.target as HTMLElement).closest("input, textarea, select")) return;
      if (e.key === "ArrowRight" || e.key === "j") {
        onIndex(Math.min(index + 1, items.length - 1));
        e.preventDefault();
      } else if (e.key === "ArrowLeft" || e.key === "k") {
        onIndex(Math.max(index - 1, 0));
        e.preventDefault();
      } else if (KEY_GRADES[e.key]) {
        rate(KEY_GRADES[e.key]);
        e.preventDefault();
      } else if (e.key === "o") {
        window.open(summary.url, "_blank", "noreferrer");
      } else if (e.key === "e") {
        setMarking((m) => !m);
        e.preventDefault();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  });

  return (
    <Dialog open={summary !== undefined} onOpenChange={(open) => !open && onIndex(null)}>
      <DialogContent className="flex h-[92vh] max-w-6xl flex-col gap-0 overflow-hidden p-0">
        {summary && index !== null && of && (
          // A work that fails to draw says so in the dialog, and the next one is drawn anew
          <ErrorBoundary resetKey={`${summary.source}/${summary.item}`} title="Карточка работы не открылась">
            <Shell
              // A new work is a new dialog: the editors of the last one do not stay open
              key={`${summary.source}/${summary.item}`}
              type={of.type}
              source={of.source}
              summary={summary}
              index={index}
              count={items.length}
              total={total}
              onIndex={onIndex}
              onRate={rate}
              marking={marking}
              onMarking={setMarking}
            />
          </ErrorBoundary>
        )}
      </DialogContent>
    </Dialog>
  );
}

function Shell({
  type,
  source,
  summary,
  index,
  count,
  total,
  onIndex,
  onRate,
  marking,
  onMarking,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  summary: ItemSummary;
  index: number;
  count: number;
  total: number;
  onIndex: (index: number | null) => void;
  onRate: (grade: Grade | null) => void;
  marking: boolean;
  onMarking: (marking: boolean) => void;
}) {
  const ref = { source: summary.source, item: summary.item };
  const details = useQuery({
    queryKey: itemKey(ref),
    queryFn: async () => unwrap(await api.GET("/api/items/{source}/{item}", { params: { path: ref } })),
  });
  const corrections = useCorrections(ref, type.id);
  const [editingTitle, setEditingTitle] = useState(false);
  const d = details.data;
  // The facets of the card until the work's own are in
  const facets = d?.allFacets ?? summary.facets;

  return (
    <>
      <div className="flex shrink-0 gap-4 border-b p-4 pr-12">
        <div className={cn("shrink-0 overflow-hidden rounded-md bg-muted", source.picturesTell ? "h-24 w-40 sm:h-28 sm:w-48" : "h-28 w-20 sm:h-32 sm:w-24")}>
          <Cover typeId={type.id} summary={summary} className="size-full" />
        </div>
        <div className="flex min-w-0 flex-1 flex-col gap-1">
          <div className="flex items-start gap-3">
            <div className="min-w-0 flex-1">
              {editingTitle ? (
                <FieldEditor
                  value={summary.title}
                  corrected={d?.titleCorrected ?? false}
                  onSave={(content) => corrections.setField(titleField, content)}
                  onReset={() => corrections.resetField(titleField)}
                  onClose={() => setEditingTitle(false)}
                  className="max-w-2xl"
                />
              ) : (
                <div className="group/title flex items-center gap-1">
                  <DialogTitle className="text-xl leading-tight font-semibold sm:text-2xl">{summary.title}</DialogTitle>
                  {d?.titleCorrected && <CorrectedMark />}
                  {marking && <EditButton onClick={() => setEditingTitle(true)} title="Исправить название" />}
                </div>
              )}
              <Byline source={source} facets={facets} className="text-sm text-muted-foreground" />
            </div>
            <ScoreBadge type={type} summary={summary} className="h-10 min-w-16 shrink-0 text-lg" />
          </div>
          <DialogDescription render={<div />} className="flex flex-col gap-1 text-sm text-muted-foreground">
            <FactLine source={source} summary={summary} facets={facets} />
            <span className="flex flex-wrap items-center gap-2">
              {type.sources.length > 1 && <span className="text-xs">{source.title}</span>}
              <SignalBadges source={source} signals={summary.signals} />
              <LinkedBadges type={type} linked={summary.linked} className="text-xs" />
            </span>
          </DialogDescription>
        </div>
      </div>

      <div className="min-h-0 flex-1 overflow-y-auto">
        {details.isLoading ? (
          <div className="grid gap-3 p-5">
            <Skeleton className="h-5 w-40" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-24 w-full" />
          </div>
        ) : d ? (
          <Details type={type} source={source} details={d} corrections={corrections} marking={marking} onMarking={onMarking} />
        ) : (
          <div className="p-5 text-destructive">Не удалось загрузить подробности</div>
        )}
      </div>

      <div className="flex shrink-0 flex-wrap items-center gap-3 border-t bg-muted/40 px-4 py-3">
        <Button variant="ghost" size="icon" onClick={() => onIndex(Math.max(index - 1, 0))} disabled={index === 0} title="Предыдущая (←)">
          <ChevronLeftIcon />
        </Button>
        <span className="text-sm tabular-nums text-muted-foreground">
          {index + 1} / {total}
        </span>
        <Button
          variant="ghost"
          size="icon"
          onClick={() => onIndex(Math.min(index + 1, count - 1))}
          disabled={index >= count - 1}
          title="Следующая (→)"
        >
          <ChevronRightIcon />
        </Button>
        <Button variant="outline" size="sm" nativeButton={false} render={<a href={summary.url} target="_blank" rel="noreferrer" />} title="Открыть на сайте (o)">
          <ExternalLinkIcon /> {source.title}
        </Button>
        {d?.canRefresh && <RefreshButton item={ref} />}
        <Button
          variant={marking ? "secondary" : "outline"}
          size="sm"
          aria-pressed={marking}
          onClick={() => onMarking(!marking)}
          title={marking ? "Закончить разметку (e)" : "Разметить: подтвердить или отклонить значения, исправить тексты и числа, связать с той же работой (e)"}
        >
          {marking ? <CheckIcon /> : <PencilIcon />}
          {marking ? "Готово" : "Разметить"}
        </Button>
        <PreviousRating type={type} summary={summary} />
        <RatingButtons
          type={type}
          value={summary.grade as Grade | undefined}
          onRate={onRate}
          size="lg"
          labels
          className="ml-auto w-full sm:w-[40rem]"
        />
      </div>
    </>
  );
}

/**
 * The work below its header: the column to decide by — what the work is, the volumes before it,
 * why the prediction, the user's grades — beside (on a narrow screen, above) the column of
 * the work itself: its pictures when they tell what it is, what it is about, the reviews, the
 * chapters. In the marking the first column holds every facet to answer on.
 */
function Details({
  type,
  source,
  details: d,
  corrections,
  marking,
  onMarking,
}: {
  type: ContentTypeInfo;
  source: SourceInfo;
  details: ItemDetails;
  corrections: Corrections;
  marking: boolean;
  onMarking: (marking: boolean) => void;
}) {
  const ref = { source: d.summary.source, item: d.summary.item };
  const nothing = d.texts.length === 0 && d.pictures.length === 0 && d.reviews.length === 0 && d.parts.length === 0;
  const except = type.universes ? FANFIC_FACETS : undefined;
  // A graded work is told by a model that had not seen it: fitted for the asking, read apart
  const graded = d.summary.grade != null;
  const heldOut = useHeldOut(ref, graded);

  return (
    <div className="grid gap-6 p-5 lg:grid-cols-[minmax(0,1fr)_24rem]">
      <aside className="flex min-w-0 flex-col gap-5 lg:order-2">
        {type.universes && (
          <Part title="Фанфик">
            {marking ? (
              <FanficSection type={type} source={source} details={d} corrections={corrections} />
            ) : (
              <FanficSummary source={source} details={d} onMark={() => onMarking(true)} />
            )}
          </Part>
        )}
        {marking ? (
          <>
            <Separator />
            <Part title="Признаки">
              <FacetCorrections item={ref} source={source} facets={d.allFacets} corrections={corrections} except={except} />
            </Part>
            <Part title="Числа">
              <ItemNumbers numbers={d.allNumbers} source={source} corrections={corrections} />
            </Part>
            <Separator />
            <Part title="Та же работа">
              <Links type={type} summary={d.summary} corrections={corrections} />
            </Part>
          </>
        ) : (
          <Part title="Признаки">
            <ContentFacts source={source} facets={d.allFacets} except={except} />
            <OpenSuggestions source={source} details={d} except={except ?? []} onMark={() => onMarking(true)} />
          </Part>
        )}
        <Part title="Цикл и автор">
          <RelatedWorks type={type} related={d.related} />
        </Part>
        {graded && heldOut.data === undefined && !heldOut.isError ? (
          <Part title="Без вашей оценки">
            <Skeleton className="h-16 w-full" />
          </Part>
        ) : heldOut.data ? (
          // A graded work: the model of every grade has seen it and partly learnt it by heart — this one has not
          <Part title="Без вашей оценки">
            <div className={cn("flex flex-col gap-1", heldOut.isFetching && "opacity-60")}>
              <div className="text-sm">
                Модель, не видевшая эту работу: <span className="font-semibold tabular-nums">{score(Math.max(0, heldOut.data.score))}</span>
                {heldOut.data.gradePlace != null && (
                  <span className="text-muted-foreground">
                    {" "}
                    · ваша оценка на шкале: <span className="tabular-nums">{score(heldOut.data.gradePlace)}</span>
                  </span>
                )}
              </div>
              <Explanation groups={heldOut.data.explanation} title="За счёт чего" />
            </div>
          </Part>
        ) : (
          <Part title="Почему такой прогноз">
            <Explanation groups={d.explanation} />
          </Part>
        )}
        {d.ratings.length > 0 && (
          <Part title="Мои оценки">
            <Ratings type={type} details={d} />
          </Part>
        )}
      </aside>

      <div className="flex min-w-0 flex-col gap-5 lg:order-1">
        {nothing && d.canRefresh && <NotFetched item={ref} />}
        {source.picturesTell && (
          <Part title="Картинки">
            <Pictures item={ref} pictures={d.pictures} explained={marking} />
          </Part>
        )}
        <Part title="Тексты">
          <ItemTexts texts={d.texts} source={source} corrections={corrections} editing={marking} />
        </Part>
        {!source.picturesTell && (
          <Part title="Картинки">
            <Pictures item={ref} pictures={d.pictures} large={false} explained={marking} />
          </Part>
        )}
        {marking && (
          <Part title="Похожие картинки">
            <PictureMatches item={ref} matches={d.pictureMatches} />
          </Part>
        )}
        <Part title={source.reviewsLabel ?? "Отзывы"}>
          <Reviews item={ref} label={source.reviewsLabel ?? "Отзывы"} reviews={d.reviews} total={d.reviewCount} explained={marking} />
        </Part>
        {marking && (
          <Part title="Похожие отзывы">
            <ReviewMatches item={ref} matches={d.reviewMatches} />
          </Part>
        )}
        <Part title={source.partsLabel ?? "Части"}>
          <Parts item={ref} label={source.partsLabel ?? "Части"} parts={d.parts} />
        </Part>
      </div>
    </div>
  );
}

/** A section of the card that fails to draw says so in its place; the grade and the rest of the card stay. */
function Part({ title, children }: { title: string; children: ReactNode }) {
  return (
    <ErrorBoundary title={`Не удалось показать: ${title.toLowerCase()}`} className="m-0 p-3">
      {children}
    </ErrorBoundary>
  );
}

/**
 * What the model says of the work and the user has not answered on, outside the fan fiction's
 * section: values it suggests adding, values it gave by itself — counted in a line that opens
 * the marking.
 */
function OpenSuggestions({
  source,
  details: d,
  except,
  onMark,
}: {
  source: SourceInfo;
  details: ItemDetails;
  except: readonly string[];
  onMark: () => void;
}) {
  const suggestions = useSuggestions({ source: d.summary.source, item: d.summary.item }, source);
  const suggested = (suggestions.data ?? []).filter((s) => !except.includes(s.facet)).reduce((n, s) => n + s.suggested.length, 0);
  const inferred = d.allFacets
    .filter((f) => !except.includes(f.facet))
    .flatMap((f) => f.values)
    .filter((v) => v.inferred && !v.corrected).length;
  if (suggested + inferred === 0) return null;
  return (
    <Button variant="link" size="sm" className="h-auto self-start p-0 text-xs" onClick={onMark}>
      Модель предлагает {suggested + inferred} без вашего ответа — разобрать
    </Button>
  );
}

/**
 * The grades of every item of the work, newest first: the item of each when it is not this one,
 * and a cross to take one back — an old version's grade, or one given to the work on another site.
 */
function Ratings({ type, details: d }: { type: ContentTypeInfo; details: ItemDetails }) {
  const takeBack = useTakeBackGrade(d.summary);
  const itemOf = (r: RatingRecord) =>
    sameItem(r, d.summary)
      ? undefined
      : `${sourceTitle(type, r.source)} · ${d.summary.linked.find((l) => sameItem(l, r))?.title ?? r.item}`;
  return (
    <section>
      <h4 className="mb-2 text-sm font-semibold">Мои оценки</h4>
      <ul className="flex flex-col gap-1 text-sm">
        {d.ratings.map((r) => (
          <li key={`${r.source}/${r.item}-${r.version}-${r.ratedAt}`} className="group/rating flex items-center gap-2">
            <span className="flex min-w-0 flex-1 flex-col">
              <span className={cn("truncate", !r.current && "text-muted-foreground")} title={formatDate(r.ratedAt)}>
                {r.version || formatDate(r.ratedAt)}
                {!r.current && r.version && " · прежняя версия"}
              </span>
              {itemOf(r) && <span className="truncate text-xs text-muted-foreground">{itemOf(r)}</span>}
            </span>
            <span className={cn("shrink-0", GRADE_TEXT[r.grade as Grade])}>
              {r.grade} · {gradeLabel(type, r.grade as Grade)}
            </span>
            <AsyncButton
              variant="ghost"
              size="icon-xs"
              title="Снять эту оценку"
              onClick={() => takeBack(r)}
              icon={<XIcon />}
              className="text-muted-foreground opacity-0 transition-opacity group-hover/rating:opacity-100 focus-visible:opacity-100 pointer-coarse:opacity-100"
            />
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Nothing of the work is here yet: its page has not come in — fetch it now. */
function NotFetched({ item }: { item: { source: string; item: string } }) {
  const refresh = useRefreshItem(item);
  return (
    <div className="flex flex-col items-start gap-3 rounded-lg border border-dashed p-4 text-sm text-muted-foreground">
      Страница этой работы ещё не загружена: нет ни текстов, ни картинок.
      <Button onClick={() => refresh.mutate()} disabled={refresh.isPending}>
        {refresh.isPending ? <Loader2Icon className="animate-spin" /> : <DownloadIcon />}
        {refresh.isPending ? "Загружаю…" : "Загрузить страницу"}
      </Button>
    </div>
  );
}

/** Downloads the page again: after a login was set, or to see a fresh update before the next job. */
function RefreshButton({ item }: { item: { source: string; item: string } }) {
  const refresh = useRefreshItem(item);
  return (
    <Button variant="outline" size="sm" onClick={() => refresh.mutate()} disabled={refresh.isPending} title="Скачать страницу работы заново">
      {refresh.isPending ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
      Обновить
    </Button>
  );
}
