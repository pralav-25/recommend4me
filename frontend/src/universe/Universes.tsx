import { useEffect, useRef, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, ChevronRightIcon, ExternalLinkIcon, LinkIcon, Loader2Icon, OrbitIcon, PlusIcon, RefreshCwIcon, SearchIcon, Trash2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type CharacterInfo, type ContentTypeInfo, type FoundUniverse, type UniverseClassInfo, type UniverseInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { ago, formatDate } from "@/i18n/format";
import { ITEMS } from "@/item/lists";
import { cn } from "@/lib/utils";
import { followLink, navigate, screenHref } from "@/screen/screens";
import { LocalNameEdit } from "./LocalName";

/** The cache key of the dictionary: ["universes", type]; a universe's characters lie under it. */
const UNIVERSES = "universes";

/** The dictionary of universes of a content type. */
export function useUniverses(typeId: string, enabled = true) {
  return useQuery({
    queryKey: [UNIVERSES, typeId],
    enabled,
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/universes", { params: { path: { type: typeId } } })),
  });
}

/** The query of the characters of a universe of the dictionary, as the catalogue gave them. */
export function charactersQuery(typeId: string, u: { catalogue: string; universe: string }) {
  return {
    queryKey: [UNIVERSES, typeId, u.catalogue, u.universe, "characters"],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/types/{type}/universes/{catalogue}/{universe}/characters", {
          params: { path: { type: typeId, catalogue: u.catalogue, universe: u.universe } },
        }),
      ),
  };
}

const sameUniverse =(a: { catalogue: string; universe: string }, b: { catalogue: string; universe: string }) =>
  a.catalogue === b.catalogue && a.universe === b.universe;

type UniverseRef = { catalogue: string; universe: string };

/**
 * What to ask again after the dictionary changed. The universes are the values of the facet
 * "universe", so its filter and its offers change with them; a removed universe takes the works'
 * links with it, so the works are asked again too.
 */
function useDictionaryChanged(typeId: string) {
  const queryClient = useQueryClient();
  return (worksChanged: boolean) =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: [UNIVERSES, typeId] }),
      queryClient.invalidateQueries({ queryKey: ["facets", typeId] }),
      queryClient.invalidateQueries({ queryKey: ["facet-values"] }),
      queryClient.invalidateQueries({ queryKey: ["item"] }),
      worksChanged && queryClient.invalidateQueries({ queryKey: [ITEMS, typeId] }),
    ]);
}

/**
 * THE SCREEN OF THE DICTIONARY OF UNIVERSES of a content type whose works may be fan fiction: the
 * universes the user agreed on, each with its characters as the catalogue gave them. The catalogue
 * is asked only by the user's buttons. A universe opened is an address of its own
 * ("/universes/<catalogue>/<id>"): a link to it opens it, its characters shown.
 */
export function UniversesScreen({ type, selected }: { type: ContentTypeInfo; selected?: UniverseRef }) {
  const { t } = useTranslation("universe");
  const universes = useUniverses(type.id);

  return (
    <div className="mx-auto flex max-w-5xl flex-col gap-4 px-4 py-4">
      <header className="flex flex-col gap-1">
        <h1 className="flex items-center gap-2 text-xl font-semibold">
          <OrbitIcon className="size-5" /> {t("UniversesScreen.title", { type: type.title.toLowerCase() })}
        </h1>
        <p className="text-sm text-muted-foreground">{t("UniversesScreen.description")}</p>
      </header>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="font-semibold">{t("UniversesScreen.addFromWikidata")}</h4>
        <CatalogueSearch type={type} dictionary={universes.data} />
      </section>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="flex items-center gap-2 font-semibold">
          {t("UniversesScreen.inDictionary")}
          {universes.data && <span className="font-normal text-muted-foreground tabular-nums">{universes.data.length}</span>}
          {universes.isFetching && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
        </h4>
        {universes.isError && <p className="text-destructive">{t("UniversesScreen.loadFailed", { error: universes.error.message })}</p>}
        {universes.data && universes.data.length === 0 && (
          <p className="text-muted-foreground">{t("UniversesScreen.empty")}</p>
        )}
        {universes.data && universes.data.length > 0 && (
          <ul className="flex flex-col divide-y rounded-lg border">
            {/* The universes of the most works first; of as many, by name */}
            {[...universes.data].sort((a, b) => b.works - a.works || a.name.localeCompare(b.name, "ru")).map((u) => (
              <UniverseRow key={u.value} type={type} universe={u} expanded={!!selected && sameUniverse(selected, u)} />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

/** A universe of the dictionary: its numbers, its buttons, and its characters when it is the one opened. */
function UniverseRow({ type, universe: u, expanded }: { type: ContentTypeInfo; universe: UniverseInfo; expanded: boolean }) {
  const { t } = useTranslation("universe");
  const changed = useDictionaryChanged(type.id);
  const [confirming, setConfirming] = useState(false);
  const ref = { catalogue: u.catalogue, universe: u.universe };
  const row = useRef<HTMLLIElement>(null);
  // Opened by a link: brought into view
  useEffect(() => {
    if (expanded) row.current?.scrollIntoView({ block: "nearest" });
  }, [expanded]);
  const toggle = () => navigate(expanded ? { kind: "universes" } : { kind: "universes", ...ref });
  // The spinner stays until the list shows what the catalogue gave
  const refresh = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/types/{type}/universes", { params: { path: { type: type.id } }, body: ref })),
    onSuccess: () => changed(false),
  });
  const remove = useMutation({
    mutationFn: async () =>
      ensureOk(await api.DELETE("/api/types/{type}/universes/{catalogue}/{universe}", { params: { path: { type: type.id, ...ref } } })),
    onSuccess: () => changed(true),
  });

  return (
    <li ref={row} className={cn("flex flex-col gap-2 px-3 py-2", expanded && "bg-muted/30")}>
      <div className="flex flex-wrap items-start gap-2">
        <Button
          variant="ghost"
          size="icon-sm"
          onClick={toggle}
          aria-expanded={expanded}
          aria-label={expanded ? t("UniverseRow.hideCharacters") : t("UniverseRow.showCharacters")}
          title={expanded ? t("UniverseRow.hideCharacters") : t("UniverseRow.showCharacters")}
        >
          <ChevronRightIcon className={cn("transition-transform", expanded && "rotate-90")} />
        </Button>
        {/* At least so wide: on a narrow screen the buttons go to a line of their own, not squeeze the name */}
        <div className="min-w-0 flex-1 basis-60">
          <a
            href={screenHref({ kind: "universes", ...ref })}
            onClick={(e) => followLink(e, expanded ? { kind: "universes" } : { kind: "universes", ...ref })}
            className="font-medium underline-offset-2 hover:underline"
            title={t("UniverseRow.openCharacters")}
          >
            {u.name}
          </a>
          {u.localName && <span className="ml-1.5 text-xs text-muted-foreground">{t("UniverseRow.catalogueName", { name: u.catalogueName })}</span>}
          <a href={u.url} target="_blank" rel="noreferrer" className="ml-1.5 inline-flex align-middle text-muted-foreground hover:text-foreground" title={t("UniverseRow.wikidataPage")}>
            <ExternalLinkIcon className="size-3.5" />
          </a>
          <LocalNameEdit
            typeId={type.id}
            catalogue={u.catalogue}
            entry={u.universe}
            localName={u.localName}
            catalogueName={u.catalogueName}
            onSaved={() => changed(true)}
          />
          {u.description && <div className="text-muted-foreground">{u.description}</div>}
          <div className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
            <span title={t("UniverseRow.charactersHint")}>
              {u.total !== u.characters
                ? t("UniverseRow.charactersOfTotal", { count: u.characters, total: u.total })
                : t("UniverseRow.characters", { count: u.characters })}
            </span>
            <span>· {t("UniverseRow.works", { count: u.works })}</span>
            <span title={t("UniverseRow.refreshedAtHint", { date: formatDate(u.refreshedAt) })}>· {t("UniverseRow.refreshed", { ago: ago(u.refreshedAt) })}</span>
            <span title={formatDate(u.addedAt)}>· {t("UniverseRow.added", { ago: ago(u.addedAt) })}</span>
          </div>
        </div>
        <div className="ml-auto flex flex-wrap items-center gap-1">
          <Button
            size="sm"
            variant="outline"
            onClick={() => refresh.mutate()}
            disabled={refresh.isPending || remove.isPending}
            title={t("UniverseRow.refreshHint")}
          >
            {refresh.isPending ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
            {refresh.isPending ? t("UniverseRow.refreshing") : t("UniverseRow.refresh")}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirming(true)} disabled={confirming || remove.isPending} title={t("UniverseRow.removeHint")}>
            <Trash2Icon /> {t("UniverseRow.remove")}
          </Button>
        </div>
      </div>

      {refresh.isError && <p className="text-destructive">{t("UniverseRow.refreshFailed", { error: refresh.error.message })}</p>}
      {confirming && (
        <div className="flex flex-wrap items-center gap-2 rounded-lg border border-destructive/40 bg-destructive/5 p-2">
          <span className="min-w-0 flex-1">{t("UniverseRow.confirmRemove")}</span>
          <Button size="sm" variant="destructive" onClick={() => remove.mutate()} disabled={remove.isPending}>
            {remove.isPending ? <Loader2Icon className="animate-spin" /> : <Trash2Icon />} {t("UniverseRow.remove")}
          </Button>
          <Button size="sm" variant="ghost" onClick={() => setConfirming(false)} disabled={remove.isPending}>
            {t("UniverseRow.cancel")}
          </Button>
          {remove.isError && <p className="w-full text-destructive">{t("UniverseRow.removeFailed", { error: remove.error.message })}</p>}
        </div>
      )}
      {expanded && <Characters type={type} universe={u} />}
    </li>
  );
}

/** The query of the classes the entries of a universe are of, the most entries first. */
export function classesQuery(typeId: string, u: { catalogue: string; universe: string }) {
  return {
    queryKey: [UNIVERSES, typeId, u.catalogue, u.universe, "classes"],
    queryFn: async () =>
      unwrap(
        await api.GET("/api/types/{type}/universes/{catalogue}/{universe}/classes", {
          params: { path: { type: typeId, catalogue: u.catalogue, universe: u.universe } },
        }),
      ),
  };
}

/**
 * The user's words on what of a universe is kept as characters: a class of its entries, or one
 * entry over its classes. A word that says what would be anyway is taken back instead of kept, so
 * a refresh of the catalogue still decides for everything the user did not set apart.
 */
export function useInclusion(typeId: string, u: { catalogue: string; universe: string }) {
  const changed = useDictionaryChanged(typeId);
  const path = { type: typeId, catalogue: u.catalogue, universe: u.universe };
  return {
    setClass: async (cls: UniverseClassInfo) => {
      const included = !cls.included;
      const params = { path: { ...path, class: cls.class } };
      ensureOk(
        included === cls.character
          ? await api.DELETE("/api/types/{type}/universes/{catalogue}/{universe}/classes/{class}", { params })
          : await api.PUT("/api/types/{type}/universes/{catalogue}/{universe}/classes/{class}", { params, body: { included } }),
      );
      await changed(false);
    },
    /** [byClasses]: whether the entry's classes keep it. */
    setEntry: async (c: CharacterInfo, byClasses: boolean) => {
      const included = !c.included;
      const params = { path: { ...path, character: c.character } };
      ensureOk(
        included === byClasses
          ? await api.DELETE("/api/types/{type}/universes/{catalogue}/{universe}/characters/{character}", { params })
          : await api.PUT("/api/types/{type}/universes/{catalogue}/{universe}/characters/{character}", { params, body: { included } }),
      );
      await changed(false);
    },
  };
}

/** What the entry's classes say of it: kept when any of them is, or when it has none. */
export const keptByClasses = (c: CharacterInfo, classes: Map<string, UniverseClassInfo>) =>
  c.classes.length === 0 || c.classes.some((id) => classes.get(id)?.included === true);

/** Which entries the list shows. */
type EntriesShown = "kept" | "left" | "all";

/**
 * What a universe holds, as the dictionary keeps it: the classes of its entries — those of
 * characters kept, the rest (places, groups, spells, things) left out unless the user keeps them
 * — and every entry, each kept or left out by its classes or by the user's word on it alone.
 */
function Characters({ type, universe: u }: { type: ContentTypeInfo; universe: UniverseInfo }) {
  const { t } = useTranslation("universe");
  const [filter, setFilter] = useState("");
  const [shownKind, setShownKind] = useState<EntriesShown>("kept");
  const characters = useQuery(charactersQuery(type.id, u));
  const classes = useQuery(classesQuery(type.id, u));
  const inclusion = useInclusion(type.id, u);
  const changed = useDictionaryChanged(type.id);

  if (characters.isLoading || classes.isLoading) return <Loader2Icon className="ml-9 size-4 animate-spin text-muted-foreground" />;
  if (characters.isError) return <p className="ml-9 text-destructive">{t("Characters.loadFailed", { error: characters.error.message })}</p>;
  if (classes.isError) return <p className="ml-9 text-destructive">{t("Characters.loadClassesFailed", { error: classes.error.message })}</p>;
  const all = characters.data ?? [];
  if (all.length === 0) return <p className="ml-9 text-muted-foreground">{t("Characters.empty")}</p>;

  const byId = new Map((classes.data ?? []).map((c) => [c.class, c]));
  const byClasses = (c: CharacterInfo) => keptByClasses(c, byId);
  const needle = filter.trim().toLocaleLowerCase("ru-RU");
  const shown = all
    .filter((c) => shownKind === "all" || c.included === (shownKind === "kept"))
    .filter((c) => needle === "" || c.names.some((n) => n.toLocaleLowerCase("ru-RU").includes(needle)));
  const kept = all.filter((c) => c.included).length;

  return (
    <div className="ml-9 flex flex-col gap-3">
      <Classes classes={classes.data ?? []} onToggle={inclusion.setClass} />
      <div className="flex flex-wrap items-center gap-2">
        <Input value={filter} onChange={(e) => setFilter(e.target.value)} placeholder={t("Characters.filterPlaceholder")} className="max-w-xs" type="search" />
        <div className="flex gap-1" role="group" aria-label={t("Characters.shown")}>
          {(
            [
              ["kept", t("Characters.kept", { n: kept })],
              ["left", t("Characters.left", { n: all.length - kept })],
              ["all", t("Characters.all", { n: all.length })],
            ] as const
          ).map(([kind, label]) => (
            <Button key={kind} size="sm" variant={shownKind === kind ? "secondary" : "ghost"} aria-pressed={shownKind === kind} onClick={() => setShownKind(kind)}>
              {label}
            </Button>
          ))}
        </div>
        {needle !== "" && <span className="text-xs text-muted-foreground tabular-nums">{t("Characters.found", { n: shown.length })}</span>}
      </div>
      <ul className="flex max-h-96 flex-col divide-y overflow-y-auto rounded-lg border">
        {shown.map((c) => (
          <CharacterRow
            key={c.character}
            character={c}
            classNames={c.classes.map((id) => byId.get(id)?.name ?? id)}
            onToggle={() => inclusion.setEntry(c, byClasses(c))}
            naming={
              <LocalNameEdit
                typeId={type.id}
                catalogue={u.catalogue}
                entry={c.character}
                localName={c.localName}
                catalogueName={(c.localName ? c.names[1] : c.names[0]) ?? c.character}
                onSaved={() => changed(false)}
              />
            }
          />
        ))}
      </ul>
    </div>
  );
}

/**
 * The classes of a universe's entries as chips, those of characters first: a kept class pressed,
 * one the user set apart from what the catalogue says marked. Pressing a chip keeps or leaves out
 * every entry of the class.
 */
function Classes({ classes, onToggle }: { classes: UniverseClassInfo[]; onToggle: (cls: UniverseClassInfo) => Promise<unknown> }) {
  const { t } = useTranslation("universe");
  if (classes.length === 0) return null;
  const group = (character: boolean, label: string, hint: string) => {
    const list = classes.filter((c) => c.character === character);
    if (list.length === 0) return null;
    return (
      <div className="flex flex-col gap-1">
        <div className="text-xs text-muted-foreground" title={hint}>
          {label}
        </div>
        <div className="flex flex-wrap gap-1">
          {list.map((c) => (
            <AsyncButton
              key={c.class}
              size="xs"
              variant={c.included ? "secondary" : "outline"}
              aria-pressed={c.included}
              onClick={() => onToggle(c)}
              title={t(
                c.included
                  ? c.choice != null ? "Classes.keptByChoiceHint" : "Classes.keptHint"
                  : c.choice != null ? "Classes.leftByChoiceHint" : "Classes.leftHint",
                { n: c.count },
              )}
              className={cn("h-auto min-h-6 max-w-full whitespace-normal text-left", !c.included && "text-muted-foreground line-through decoration-muted-foreground/60")}
            >
              {c.choice != null && <span className="text-primary">•</span>}
              {c.name} <span className="tabular-nums">{c.count}</span>
            </AsyncButton>
          ))}
        </div>
      </div>
    );
  };
  return (
    <div className="flex flex-col gap-2 rounded-lg border p-2">
      {group(true, t("Classes.characterClasses"), t("Classes.characterClassesHint"))}
      {group(false, t("Classes.other"), t("Classes.otherHint"))}
    </div>
  );
}

function CharacterRow({
  character: c,
  classNames,
  onToggle,
  naming,
}: {
  character: CharacterInfo;
  classNames: string[];
  onToggle: () => Promise<unknown>;
  /** The user's own name of the character over the catalogue's, to give or take back. */
  naming?: ReactNode;
}) {
  const { t } = useTranslation("universe");
  const [main, ...others] = c.names;
  return (
    <li className={cn("flex items-start gap-2 px-3 py-1.5", !c.included && "text-muted-foreground")}>
      <AsyncButton
        size="icon-xs"
        variant={c.included ? "secondary" : "outline"}
        aria-pressed={c.included}
        onClick={onToggle}
        title={t(
          c.included
            ? c.choice != null ? "CharacterRow.keptByChoiceHint" : "CharacterRow.keptByClassHint"
            : c.choice != null ? "CharacterRow.leftByChoiceHint" : "CharacterRow.leftByClassHint",
        )}
        icon={c.included ? <CheckIcon /> : <XIcon />}
        className="mt-0.5"
      />
      <div className="min-w-0 flex-1">
        <span className={cn("font-medium", !c.included && "line-through decoration-muted-foreground/60")}>{main ?? c.character}</span>
        {naming}
        {others.length > 0 && <span className="text-muted-foreground"> · {others.join(", ")}</span>}
        {c.choice != null && <span className="ml-1 text-xs text-primary">{t("CharacterRow.yourChoice")}</span>}
        {(classNames.length > 0 || c.description) && (
          <div className="text-xs text-muted-foreground">{[classNames.join(", "), c.description].filter(Boolean).join(" — ")}</div>
        )}
      </div>
      <a href={c.url} target="_blank" rel="noreferrer" className="mt-0.5 shrink-0 text-muted-foreground hover:text-foreground" title={t("CharacterRow.wikidataPage")}>
        <ExternalLinkIcon className="size-3.5" />
      </a>
    </li>
  );
}

/**
 * A work the universes found are for: a universe found is added to the dictionary and linked to
 * the work at once, and one of the dictionary already is linked by a button of its own.
 */
export type UniverseForWork = {
  /** The work has the universe of the value (and the user did not take it away). */
  linked: (value: string) => boolean;
  /** Links the work to the universe of the value. */
  link: (value: string) => Promise<unknown>;
};

/**
 * The search of the catalogue by a name: on the button or Enter only, never as the user types.
 * Each universe found is added to the dictionary by its button — and, with [forWork], linked to
 * that work as well.
 */
export function CatalogueSearch({ type, dictionary, forWork }: { type: ContentTypeInfo; dictionary?: UniverseInfo[]; forWork?: UniverseForWork }) {
  const { t } = useTranslation("universe");
  const [query, setQuery] = useState("");
  const search = useMutation({
    mutationFn: async (q: string) =>
      unwrap(await api.GET("/api/types/{type}/universes/search", { params: { path: { type: type.id }, query: { query: q } } })),
  });
  const submit = () => {
    const q = query.trim();
    if (q !== "") search.mutate(q);
  };

  return (
    <div className="flex flex-col gap-2 text-sm">
      <form
        className="flex gap-2"
        onSubmit={(e) => {
          e.preventDefault();
          submit();
        }}
      >
        <Input value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t("CatalogueSearch.placeholder")} />
        <Button type="submit" disabled={search.isPending || query.trim() === ""}>
          {search.isPending ? <Loader2Icon className="animate-spin" /> : <SearchIcon />} {t("CatalogueSearch.search")}
        </Button>
      </form>
      {search.isError && <p className="text-destructive">{t("CatalogueSearch.searchFailed", { error: search.error.message })}</p>}
      {search.data && search.data.length === 0 && <p className="text-muted-foreground">{t("CatalogueSearch.nothingFound", { query: search.variables })}</p>}
      {search.data && search.data.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {search.data.map((f) => {
            // The dictionary as it is now, not as it was at the search: a universe added or removed since shows so
            const entry = dictionary?.find((u) => sameUniverse(u, f));
            return (
              <FoundRow
                key={`${f.catalogue}:${f.universe}`}
                type={type}
                found={f}
                added={dictionary ? entry !== undefined : f.added}
                entry={entry}
                forWork={forWork}
              />
            );
          })}
        </ul>
      )}
    </div>
  );
}

function FoundRow({
  type,
  found: f,
  added,
  entry,
  forWork,
}: {
  type: ContentTypeInfo;
  found: FoundUniverse;
  added: boolean;
  /** The universe as the dictionary has it, when it has it. */
  entry?: UniverseInfo;
  forWork?: UniverseForWork;
}) {
  const { t } = useTranslation("universe");
  const changed = useDictionaryChanged(type.id);
  // The spinner stays until the dictionary below shows the universe (and the work its link)
  const add = useMutation({
    mutationFn: async () => {
      const universe = unwrap(
        await api.POST("/api/types/{type}/universes", { params: { path: { type: type.id } }, body: { catalogue: f.catalogue, universe: f.universe } }),
      );
      if (forWork) await forWork.link(universe.value);
      return universe;
    },
    onSettled: () => changed(false),
  });

  if (forWork)
    return (
      <li className="flex flex-col gap-1 px-3 py-2">
        <div className="flex items-start gap-2">
          <FoundName found={f} />
          {entry && forWork.linked(entry.value) ? (
            <span className="shrink-0 text-xs text-muted-foreground">{t("FoundRow.linked")}</span>
          ) : entry ? (
            <AsyncButton size="sm" variant="outline" onClick={() => forWork.link(entry.value)} title={t("FoundRow.linkHint")} icon={<LinkIcon />}>
              {t("FoundRow.link")}
            </AsyncButton>
          ) : added ? (
            <span className="shrink-0 text-xs text-muted-foreground">{t("FoundRow.inDictionary")}</span>
          ) : (
            <Button
              size="sm"
              variant="outline"
              onClick={() => add.mutate()}
              disabled={add.isPending}
              title={t("FoundRow.addAndLinkHint")}
            >
              {add.isPending ? <Loader2Icon className="animate-spin" /> : <PlusIcon />}
              {add.isPending ? t("FoundRow.adding") : t("FoundRow.addAndLink")}
            </Button>
          )}
        </div>
        {add.isError && <p className="text-destructive">{t("FoundRow.addFailed", { error: add.error.message })}</p>}
      </li>
    );

  return (
    <li className="flex flex-col gap-1 px-3 py-2">
      <div className="flex items-start gap-2">
        <FoundName found={f} />
        {added ? (
          <span className="shrink-0 text-xs text-muted-foreground">{t("FoundRow.inDictionary")}</span>
        ) : (
          <Button size="sm" variant="outline" onClick={() => add.mutate()} disabled={add.isPending} title={t("FoundRow.addHint")}>
            {add.isPending ? <Loader2Icon className="animate-spin" /> : <PlusIcon />}
            {add.isPending ? t("FoundRow.adding") : t("FoundRow.add")}
          </Button>
        )}
      </div>
      {add.isError && <p className="text-destructive">{t("FoundRow.addFailed", { error: add.error.message })}</p>}
    </li>
  );
}

function FoundName({ found: f }: { found: FoundUniverse }) {
  const { t } = useTranslation("universe");
  return (
    <div className="min-w-0 flex-1">
      <a href={f.url} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline" title={t("FoundName.wikidataPage")}>
        {f.name}
      </a>
      <span className="ml-2 font-mono text-xs text-muted-foreground">{f.universe}</span>
      {f.description && <div className="text-muted-foreground">{f.description}</div>}
    </div>
  );
}
