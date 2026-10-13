import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import {
  ArrowDownWideNarrowIcon,
  ChevronDownIcon,
  HashIcon,
  KeyboardIcon,
  LayoutGridIcon,
  ListIcon,
  Loader2Icon,
  RefreshCcwIcon,
  SearchIcon,
  SettingsIcon,
  SparklesIcon,
  TelescopeIcon,
  ZapIcon,
} from "lucide-react";
import type { ContentTypeInfo, ItemView } from "@/api/client";
import { CaptureDialog } from "@/capture/CaptureDialog";
import { ErrorBoundary } from "@/components/ErrorBoundary";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Tabs, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { useTypes } from "@/contenttype/types";
import { FacetFilters, NOTHING_HIDDEN, validSelection, type FacetSelection } from "@/facet/FacetFilters";
import { NumberFilters, validRanges, type NumberRanges } from "@/number/NumberFilters";
import { ItemBoard, type Layout } from "@/item/ItemBoard";
import { HashItemDialog } from "@/item/ItemRefDialog";
import { ITEMS } from "@/item/lists";
import { BY_SCORE, validSort, type SortChoice } from "@/item/sort";
import { JobControl } from "@/job/JobControl";
import { useJobs } from "@/job/useJobs";
import { cn } from "@/lib/utils";
import { usePref } from "@/lib/usePref";
import { ModelDialog } from "@/model/ModelDialog";
import { useModel } from "@/model/useModel";
import { SettingsDialog } from "@/settings/SettingsDialog";
import { SearchQualityDialog } from "@/search/SearchQualityDialog";
import { SourceFilter } from "@/source/SourceFilter";
import { WorkProgress } from "@/status/WorkProgress";
import { ThemeToggle } from "@/theme/ThemeToggle";
import { ScreenBar, ScreenMenuButton } from "@/screen/ScreenNav";
import { useScreen } from "@/screen/screens";
import { TagsScreen } from "@/tag/TagsScreen";
import { UniversesScreen } from "@/universe/Universes";

const VIEWS: { view: ItemView; label: string }[] = [
  { view: "UNRATED", label: "Без оценки" },
  { view: "RATED", label: "Оценённые" },
  { view: "ALL", label: "Все" },
];

const HOTKEYS: [string, string][] = [
  ["/", "поиск"],
  ["← → ↑ ↓ / h j k l", "выбор работы"],
  ["Enter / Пробел", "открыть работу"],
  ["1 … 5", "оценка: не нравится … хочу ещё"],
  ["в окне работы: ← →", "предыдущая · следующая"],
  ["в окне работы: o", "открыть на сайте"],
  ["Esc", "закрыть окно"],
];

/**
 * The pause in the typing after which the line is searched: the user's choice of a second — the
 * 300 ms usual for suggestions searched every few words cost a reranking each.
 */
const SEARCH_SETTLE_MS = 1000;

/** The content types as tabs; the chosen one is remembered, and the whole screen below is its own. */
export function App() {
  const types = useTypes();
  const [chosen, setChosen] = usePref<string>("type", "");
  const list = types.data ?? [];
  const type = list.find((t) => t.id === chosen) ?? list[0];

  if (types.isLoading) {
    return (
      <div className="flex min-h-screen items-center justify-center text-muted-foreground">
        <Loader2Icon className="size-8 animate-spin" />
      </div>
    );
  }
  if (!type) {
    return (
      <div className="flex min-h-screen flex-col items-center justify-center gap-2 text-muted-foreground">
        <TelescopeIcon className="size-12 opacity-50" />
        {types.isError ? (
          <div className="text-destructive">Приложение не отвечает: {types.error.message}</div>
        ) : (
          <div>Нет ни одного источника: установите плагин сайта и перезапустите приложение.</div>
        )}
      </div>
    );
  }
  // A new type is a new screen: its filters, its order, its search
  return <TypeScreen key={type.id} type={type} types={list} onType={setChosen} />;
}

function TypeScreen({ type, types, onType }: { type: ContentTypeInfo; types: ContentTypeInfo[]; onType: (id: string) => void }) {
  const queryClient = useQueryClient();
  const [view, setView] = usePref<ItemView>("view", "UNRATED");
  const [storedSort, setSort] = usePref<SortChoice>(`sort:${type.id}`, BY_SCORE);
  const sort = validSort(storedSort, type);
  const [layout, setLayout] = usePref<Layout>("layout", "grid");
  const [storedFacets, setFacets] = usePref<FacetSelection>(`hidden:${type.id}`, NOTHING_HIDDEN);
  const facets = useMemo(() => validSelection(storedFacets), [storedFacets]);
  const [hiddenSources, setHiddenSources] = usePref<string[]>(`hiddenSources:${type.id}`, []);
  const [storedRanges, setRanges] = usePref<NumberRanges>(`ranges:${type.id}`, {});
  const ranges = useMemo(() => validRanges(type, storedRanges), [type, storedRanges]);
  const [searchInput, setSearchInput] = useState("");
  const [search, setSearch] = useState("");
  const [total, setTotal] = useState<number | undefined>();
  const [settingsOpen, setSettingsOpen] = useState(false);
  const jobs = useJobs();
  const screen = useScreen();
  const [modelOpen, setModelOpen] = useState(false);
  const [captureOpen, setCaptureOpen] = useState(false);
  const menu = { types, type, screen, onType, onModel: () => setModelOpen(true), onCapture: () => setCaptureOpen(true) };

  // The list asks for the shown sources by name, and for none when all are shown
  const sources = useMemo(() => {
    const hidden = type.sources.filter((s) => hiddenSources.includes(s.id));
    return hidden.length === 0 ? [] : type.sources.filter((s) => !hiddenSources.includes(s.id)).map((s) => s.id);
  }, [type, hiddenSources]);

  // The line searched once the typing has paused for SEARCH_SETTLE_MS — at once on Enter: a line
  // searched is a second or two of the reranker reading every work found, and every half-typed word
  // was one ("г", "голы", "голыш" — each read whole)
  useEffect(() => {
    const t = setTimeout(() => setSearch(searchInput.trim()), SEARCH_SETTLE_MS);
    return () => clearTimeout(t);
  }, [searchInput]);

  // After a retraining the list is NOT re-sorted under the cursor; a button offers it instead
  const model = useModel(type.id);
  const shownTrainedAt = useRef<string | undefined>(undefined);
  const [fresh, setFresh] = useState(false);
  useEffect(() => {
    if (!model.data) return;
    const trainedAt = model.data.trainedAt ?? "";
    if (shownTrainedAt.current === undefined) shownTrainedAt.current = trainedAt;
    else if (trainedAt !== shownTrainedAt.current && !model.data.training) setFresh(true);
  }, [model.data]);
  const resort = () => {
    shownTrainedAt.current = model.data?.trainedAt ?? "";
    setFresh(false);
    queryClient.invalidateQueries({ queryKey: [ITEMS, type.id] });
  };

  const onTotal = useCallback((t: number | undefined) => setTotal(t), []);

  // "/" jumps to the search from anywhere but a text field
  const searchRef = useRef<HTMLInputElement>(null);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== "/" || (e.target as HTMLElement).closest("input, textarea, select, [role=dialog]")) return;
      e.preventDefault();
      searchRef.current?.focus();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const notLoggedIn = jobs.data?.some((j) => j.loggedIn === false);
  const sortTab = search ? "RELEVANCE" : sort.sort === "NUMBER" ? `NUMBER:${sort.number}` : sort.sort;
  const chosenNumber = type.numbers.find((n) => n.id === sort.number);
  // One or two numbers are tabs of their own; more go into a list
  const numberTabs = type.numbers.length <= 2 ? type.numbers : [];
  const numberMenu = type.numbers.length > 2 ? type.numbers : [];

  return (
    <div className="min-h-screen">
      <header className="sticky top-0 z-40 border-b bg-background/85 backdrop-blur supports-backdrop-filter:bg-background/70">
        <div className="mx-auto flex h-14 max-w-[1800px] items-center gap-1 px-4 sm:gap-3">
          <ScreenMenuButton {...menu} />
          <div className="flex items-center gap-2 font-semibold">
            <TelescopeIcon className="hidden size-5 sm:block" />
            <span className="hidden 2xl:inline">recommend4me</span>
          </div>
          <ErrorBoundary compact resetKey={type.id} title="Меню">
            <ScreenBar {...menu} />
          </ErrorBoundary>
          {/* On a phone the progress takes what the icons leave; on a wide screen it sits in the middle */}
          <div className="mx-1 min-w-0 flex-1 sm:mx-auto sm:flex-none">
            <ErrorBoundary compact resetKey={type.id} title="Задания не показываются">
              <JobControl type={type} onNeedSettings={() => setSettingsOpen(true)} />
            </ErrorBoundary>
          </div>
          <ErrorBoundary compact title="Ход работы не показывается">
            <WorkProgress />
          </ErrorBoundary>
          <ThemeToggle />
          <Button variant="ghost" size="icon" onClick={() => setSettingsOpen(true)} aria-label="Настройки" title="Настройки" className="relative">
            <SettingsIcon />
            {notLoggedIn && <span className="absolute top-1.5 right-1.5 size-2 rounded-full bg-maybe" />}
          </Button>
        </div>
      </header>

      {screen.kind === "universes" && type.universes && (
        <ErrorBoundary resetKey={type.id} title="Вселенные не показываются">
          <UniversesScreen type={type} selected={screen.catalogue && screen.universe ? { catalogue: screen.catalogue, universe: screen.universe } : undefined} />
        </ErrorBoundary>
      )}
      {screen.kind === "tags" && (
        <ErrorBoundary resetKey={type.id} title="Теги не показываются">
          <TagsScreen type={type} selected={screen.tag} />
        </ErrorBoundary>
      )}
      {(screen.kind === "works" || (screen.kind === "universes" && !type.universes)) && (
      <div className="mx-auto max-w-[1800px] px-4">
        <div className="flex flex-wrap items-center gap-2 py-4">
          <Tabs value={view} onValueChange={(v) => setView(v as ItemView)}>
            <TabsList>
              {VIEWS.map((v) => (
                <TabsTrigger key={v.view} value={v.view} className="px-3">
                  {v.label}
                  {view === v.view && total !== undefined && <span className="ml-1 text-xs tabular-nums text-muted-foreground">{total}</span>}
                </TabsTrigger>
              ))}
            </TabsList>
          </Tabs>

          <div className="relative w-full sm:w-96">
            <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
            <Input
              ref={searchRef}
              value={searchInput}
              onChange={(e) => setSearchInput(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === "Escape") {
                  setSearchInput("");
                  e.currentTarget.blur();
                }
                // Enter: the line as typed, not waiting for the pause
                if (e.key === "Enter") setSearch(searchInput.trim());
              }}
              placeholder="Поиск по смыслу и словам: «космос и романтика», имя автора…"
              className="pr-8 pl-8"
              type="search"
            />
            <kbd className="pointer-events-none absolute top-1/2 right-2.5 hidden -translate-y-1/2 rounded border px-1 font-mono text-[10px] text-muted-foreground pointer-fine:block">
              /
            </kbd>
          </div>
          <SearchQualityDialog type={type.id} />

          <div className="flex items-center gap-1">
            <Tabs
              value={sortTab}
              onValueChange={(v) => {
                const value = String(v);
                if (value === "RELEVANCE") return;
                if (value.startsWith("NUMBER:")) setSort({ sort: "NUMBER", number: value.slice("NUMBER:".length) });
                else setSort({ sort: value as SortChoice["sort"] });
              }}
            >
              <TabsList>
                {search && (
                  <TabsTrigger value="RELEVANCE" className="px-3">
                    <SearchIcon /> По релевантности
                  </TabsTrigger>
                )}
                {/* A phone's width holds them only with short names; while searching, the others are off and hidden there */}
                <TabsTrigger value="SCORE" className={cn("px-2 sm:px-3", search && "hidden sm:inline-flex")} disabled={!!search}>
                  <SparklesIcon /> <span className="sm:hidden">Прогноз</span>
                  <span className="hidden sm:inline">По прогнозу</span>
                </TabsTrigger>
                <TabsTrigger value="UPDATED" className={cn("px-2 sm:px-3", search && "hidden sm:inline-flex")} disabled={!!search}>
                  <ArrowDownWideNarrowIcon /> <span className="sm:hidden">Дата</span>
                  <span className="hidden sm:inline">По дате</span>
                </TabsTrigger>
                <TabsTrigger
                  value="SURPRISE"
                  className={cn("px-2 sm:px-3", search && "hidden sm:inline-flex")}
                  disabled={!!search}
                  title="Сначала оценённые работы, которые модель, не видевшая их, ставит дальше всего от вашей оценки"
                >
                  <ZapIcon /> <span className="sm:hidden">Сюрпризы</span>
                  <span className="hidden sm:inline">Неожиданные</span>
                </TabsTrigger>
                {numberTabs.map((n) => (
                  <TabsTrigger
                    key={n.id}
                    value={`NUMBER:${n.id}`}
                    className={cn("px-2 sm:px-3", search && "hidden sm:inline-flex")}
                    disabled={!!search}
                    title={`Сначала больше: ${n.label}`}
                  >
                    <HashIcon /> {n.label}
                  </TabsTrigger>
                ))}
              </TabsList>
            </Tabs>
            {numberMenu.length > 0 && (
              <DropdownMenu>
                <DropdownMenuTrigger
                  render={
                    <Button
                      variant={sort.sort === "NUMBER" && !search ? "secondary" : "ghost"}
                      size="sm"
                      disabled={!!search}
                      className={cn(sort.sort === "NUMBER" && !search && "ring-1 ring-border")}
                    />
                  }
                >
                  <HashIcon /> {sort.sort === "NUMBER" && chosenNumber ? chosenNumber.label : "По числу"}
                  <ChevronDownIcon className="opacity-60" />
                </DropdownMenuTrigger>
                <DropdownMenuContent className="w-auto min-w-48">
                  <DropdownMenuRadioGroup
                    value={sort.sort === "NUMBER" ? (sort.number ?? "") : ""}
                    onValueChange={(v) => setSort({ sort: "NUMBER", number: String(v) })}
                  >
                    {numberMenu.map((n) => (
                      <DropdownMenuRadioItem key={n.id} value={n.id}>
                        {n.label}
                      </DropdownMenuRadioItem>
                    ))}
                  </DropdownMenuRadioGroup>
                </DropdownMenuContent>
              </DropdownMenu>
            )}
          </div>

          {fresh && (
            <Button variant="secondary" size="sm" onClick={resort}>
              <RefreshCcwIcon /> Прогнозы обновились — пересортировать
            </Button>
          )}

          {/* The filters flow on with everything else, one by one, not as a block of their own */}
          <ErrorBoundary compact resetKey={type.id} title="Фильтры не показываются">
            <SourceFilter type={type} hidden={hiddenSources} onChange={setHiddenSources} />
            <FacetFilters type={type.id} value={facets} onChange={setFacets} />
            <NumberFilters type={type} value={storedRanges} onChange={setRanges} />
          </ErrorBoundary>

          <div className="ml-auto flex items-center gap-1">
            <Tooltip>
              {/* No keyboard on a touch screen, no hotkeys to show */}
              <TooltipTrigger render={<Button variant="ghost" size="icon" aria-label="Горячие клавиши" className="hidden pointer-fine:inline-flex" />}>
                <KeyboardIcon />
              </TooltipTrigger>
              <TooltipContent side="bottom" className="block">
                <table>
                  <tbody>
                    {HOTKEYS.map(([keys, what]) => (
                      <tr key={keys}>
                        <td className="pr-3 font-mono">{keys}</td>
                        <td>{what}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </TooltipContent>
            </Tooltip>
            <Tabs value={layout} onValueChange={(v) => setLayout(v as Layout)}>
              <TabsList>
                <TabsTrigger value="grid" aria-label="Карточки" title="Карточки">
                  <LayoutGridIcon />
                </TabsTrigger>
                <TabsTrigger value="list" aria-label="Список" title="Список">
                  <ListIcon />
                </TabsTrigger>
              </TabsList>
            </Tabs>
          </div>
        </div>

        <main className="pb-10">
          {/* Drawn anew when what it lists changes: another type, view, sorting, search or filter */}
          <ErrorBoundary resetKey={JSON.stringify([type.id, view, sort, search, facets, sources, layout])} title="Список работ не отрисовался">
            <ItemBoard type={type} view={view} sort={sort} search={search} facets={facets} sources={sources} ranges={ranges} layout={layout} onTotal={onTotal} />
          </ErrorBoundary>
        </main>
      </div>
      )}

      <SettingsDialog open={settingsOpen} onOpenChange={setSettingsOpen} />
      <ModelDialog type={type} open={modelOpen} onOpenChange={setModelOpen} />
      <CaptureDialog open={captureOpen} onOpenChange={setCaptureOpen} />
      <HashItemDialog />
    </div>
  );
}
