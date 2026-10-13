import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, CombineIcon, Loader2Icon, SearchIcon, TagsIcon, XIcon } from "lucide-react";
import { api, unwrap, type ContentTypeInfo, type TagInfo, type TagNameRule, type TagRef, type TagRelation, type TagVariantInfo } from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { plural } from "@/i18n";
import { cn } from "@/lib/utils";
import { followLink, screenHref } from "@/screen/screens";

/** The cache key of everything of the tags of a type: ["tags", type, …]. */
const TAGS = "tags";

/** How many tags a page of the list shows, and adds by "more". */
const PAGE = 100;

/** The pause in the typing after which the list is asked again. */
const QUERY_SETTLE_MS = 400;

/** How many alike tags are offered for a tag. */
const SIMILAR = 30;

const RULE_LABEL: Record<TagNameRule, string> = {
  EXPLICIT: "название задано вами",
  LIKED: "самое частое название у понравившихся работ (оценки выше середины)",
  GRADED: "самое частое название у оценённых работ",
  ALL: "самое частое название у всех работ",
  ALPHABET: "первое по алфавиту название",
};

function sourceTitle(type: ContentTypeInfo, id: string): string {
  return type.sources.find((s) => s.id === id)?.title ?? id;
}

/** The sites a tag is used on, each once. */
function sitesOf(tag: TagInfo): string[] {
  return [...new Set(tag.variants.map((v) => v.source))];
}

/**
 * THE SCREEN OF THE TAGS: the normalized tags of a content type's free tags. Every site's tag is
 * bound to a normalized tag — at first to the one of its name, any case; the user merges tags that
 * are one — "Angst" and "Ангст" — names a tag, takes a site's tag out of one, puts tags under
 * others, tells two apart. The application only offers tags alike by the meaning of their names; it
 * merges nothing by itself.
 */
export function TagsScreen({ type, selected }: { type: ContentTypeInfo; selected?: string }) {
  return (
    <div className="mx-auto grid max-w-[1800px] gap-4 px-4 py-4 lg:grid-cols-[minmax(0,28rem)_minmax(0,1fr)]">
      <div className={cn(selected && "hidden lg:block")}>
        <TagList type={type} selected={selected} />
      </div>
      {selected ? (
        <TagDetails key={selected} type={type} tag={selected} />
      ) : (
        <div className="hidden flex-col gap-2 text-sm text-muted-foreground lg:flex">
          <h1 className="flex items-center gap-2 text-xl font-semibold text-foreground">
            <TagsIcon className="size-5" /> Теги: {type.title.toLowerCase()}
          </h1>
          <p>
            Нормализованные теги: каждый тег каждого сайта привязан к одному из них — сначала к тегу своего названия без учёта
            регистра: «Магия» одного сайта и «магия» другого — один тег. Одинаковые по смыслу теги («Angst» и «Ангст», «Enemies to
            Lovers» и «От врагов к возлюбленным») объединяете вы: выберите тег слева — справа будут похожие по смыслу, с любого
            сайта, и теги сайтов, привязанные к нему.
          </p>
          <p>
            Название объединённого тега — заданное вами, иначе самое частое у понравившихся вам работ, у оценённых, у всех, иначе
            первое по алфавиту.
          </p>
        </div>
      )}
    </div>
  );
}

function TagList({ type, selected }: { type: ContentTypeInfo; selected?: string }) {
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  const [limit, setLimit] = useState(PAGE);
  useEffect(() => {
    const t = setTimeout(() => {
      setQuery(input.trim());
      setLimit(PAGE);
    }, QUERY_SETTLE_MS);
    return () => clearTimeout(t);
  }, [input]);
  const tags = useQuery({
    queryKey: [TAGS, type.id, "list", query, limit],
    placeholderData: keepPreviousData,
    queryFn: async () =>
      unwrap(await api.GET("/api/types/{type}/tags", { params: { path: { type: type.id }, query: { query: query || undefined, limit } } })),
  });
  const page = tags.data;
  return (
    <section className="flex flex-col gap-2">
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input value={input} onChange={(e) => setInput(e.target.value)} placeholder="Найти тег по названию на любом сайте" className="pl-8" type="search" />
      </div>
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        {page && <span>{plural(page.total, "тег", "тега", "тегов")}, чаще всего используемые первыми</span>}
        {tags.isFetching && <Loader2Icon className="size-3.5 animate-spin" />}
      </div>
      {tags.isError && <p className="text-sm text-destructive">Теги не загрузились: {tags.error.message}</p>}
      <ul className="flex flex-col divide-y rounded-lg border">
        {page?.tags.map((t) => (
          <li key={t.key}>
            <a
              href={screenHref({ kind: "tags", tag: t.key })}
              onClick={(e) => followLink(e, { kind: "tags", tag: t.key })}
              aria-current={t.key === selected ? "page" : undefined}
              className={cn("flex items-center gap-2 px-3 py-1.5 text-sm hover:bg-muted/50", t.key === selected && "bg-muted")}
            >
              <span className="min-w-0 flex-1 truncate" title={t.variants.map((v) => v.name).join(" · ")}>
                {t.name}
              </span>
              {t.variants.length > 1 && (
                <Badge variant="secondary" title={`Теги сайтов: ${t.variants.map((v) => v.name).join(", ")}`}>
                  <CombineIcon /> {t.variants.length}
                </Badge>
              )}
              <span className="text-xs text-muted-foreground">{sitesOf(t).map((s) => sourceTitle(type, s)).join(", ")}</span>
              <span className="w-10 text-right text-xs tabular-nums text-muted-foreground" title="Работ с тегом">
                {t.works}
              </span>
            </a>
          </li>
        ))}
      </ul>
      {page && page.tags.length < page.total && (
        <Button variant="outline" size="sm" onClick={() => setLimit(limit + PAGE)} disabled={tags.isFetching}>
          Показать ещё
        </Button>
      )}
    </section>
  );
}

/** What to ask again after the tags changed: their list, the tag, the alike ones, the works shown with them. */
function useTagsChanged(typeId: string) {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: [TAGS, typeId] }),
      queryClient.invalidateQueries({ queryKey: ["item"] }),
      queryClient.invalidateQueries({ queryKey: ["facets", typeId] }),
    ]);
}

function TagDetails({ type, tag }: { type: ContentTypeInfo; tag: string }) {
  const info = useQuery({
    queryKey: [TAGS, type.id, "one", tag],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/tags/one", { params: { path: { type: type.id }, query: { tag } } })),
  });
  const t = info.data;
  return (
    <section className="flex min-w-0 flex-col gap-4">
      <a
        href={screenHref({ kind: "tags" })}
        onClick={(e) => followLink(e, { kind: "tags" })}
        className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground lg:hidden"
      >
        <ArrowLeftIcon className="size-4" /> Все теги
      </a>
      {info.isLoading && <Loader2Icon className="size-5 animate-spin text-muted-foreground" />}
      {info.isError && <p className="text-sm text-destructive">Тег не загрузился: {info.error.message}</p>}
      {t && (
        <>
          <header className="flex flex-col gap-1">
            <h1 className="text-xl font-semibold">{t.name}</h1>
            <p className="text-sm text-muted-foreground">
              {RULE_LABEL[t.rule]} · {plural(t.works, "работа", "работы", "работ")} · оценено {t.graded} · понравилось {t.liked}
            </p>
          </header>
          <TagName type={type} tag={t} />
          <Related tag={t} />
          <Variants type={type} tag={t} />
          <Similar type={type} tag={t} />
        </>
      )}
    </section>
  );
}

/** The tags right above and under this one, each a link to its own page. */
function Related({ tag }: { tag: TagInfo }) {
  if (tag.parents.length === 0 && tag.children.length === 0) return null;
  const links = (list: TagRef[]) =>
    list.map((r, i) => (
      <span key={r.key}>
        {i > 0 && ", "}
        <a href={screenHref({ kind: "tags", tag: r.key })} onClick={(e) => followLink(e, { kind: "tags", tag: r.key })} className="underline-offset-2 hover:underline">
          {r.name}
        </a>
      </span>
    ));
  return (
    <div className="flex flex-col gap-0.5 text-sm">
      {tag.parents.length > 0 && (
        <div title="Работа с этим тегом получает и надтеги">
          <span className="text-muted-foreground">Надтеги: </span>
          {links(tag.parents)}
        </div>
      )}
      {tag.children.length > 0 && (
        <div title="Отрицание этого тега у работы отрицает и подтеги">
          <span className="text-muted-foreground">Подтеги: </span>
          {links(tag.children)}
        </div>
      )}
    </div>
  );
}

/** The name the user gives the tag, or none: then the sites' names decide. */
function TagName({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  const changed = useTagsChanged(type.id);
  const [name, setName] = useState(tag.explicitName ?? "");
  const save = useMutation({
    mutationFn: async (value: string | null) =>
      unwrap(await api.PUT("/api/types/{type}/tags/name", { params: { path: { type: type.id } }, body: { tag: tag.key, name: value ?? undefined } })),
    onSuccess: (t) => {
      setName(t.explicitName ?? "");
      return changed();
    },
  });
  return (
    <form
      className="flex flex-wrap items-center gap-2"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate(name.trim() || null);
      }}
    >
      <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tag.name} className="max-w-sm" aria-label="Своё название тега" />
      <Button type="submit" size="sm" disabled={save.isPending || name.trim() === (tag.explicitName ?? "")}>
        {save.isPending && <Loader2Icon className="animate-spin" />} Назвать
      </Button>
      {tag.explicitName && (
        <Button type="button" variant="ghost" size="sm" onClick={() => save.mutate(null)} disabled={save.isPending} title="Название снова выбирается по сайтам">
          <XIcon /> Сбросить своё название
        </Button>
      )}
      {save.isError && <span className="w-full text-sm text-destructive">Не сохранилось: {save.error.message}</span>}
    </form>
  );
}

/** The sites' tags bound to the tag, as the sites write them, with their works. */
function Variants({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">Теги сайтов</h2>
      <table className="w-full">
        <thead className="text-xs text-muted-foreground">
          <tr className="text-left">
            <th className="font-normal">Сайт</th>
            <th className="font-normal">Название</th>
            <th className="pl-3 text-right font-normal">Работ</th>
            <th className="hidden pl-3 text-right font-normal sm:table-cell" title="Оценено">Оцен.</th>
            <th className="hidden pl-3 text-right font-normal sm:table-cell" title="Понравилось">Понр.</th>
          </tr>
        </thead>
        <tbody>
          {tag.variants.map((v) => (
            <tr key={`${v.source}/${v.tag}`} className="border-t">
              <td className="py-1 pr-2 text-muted-foreground">{sourceTitle(type, v.source)}</td>
              <td className="py-1 pr-2">{v.name}</td>
              <td className="py-1 pl-3 text-right tabular-nums">{v.works}</td>
              <td className="hidden py-1 pl-3 text-right tabular-nums sm:table-cell">{v.graded}</td>
              <td className="hidden py-1 pl-3 text-right tabular-nums sm:table-cell">{v.liked}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

type Said = Exclude<TagRelation, "NONE">;

/** What the user may say another tag is to this one: a button of every row, and a filter of the rows said so. */
const RELATIONS: { relation: Said; label: string; title: string; filter: string }[] = [
  { relation: "MERGED", label: "Объединить", title: "Это тот же тег: его теги сайтов — теги этого", filter: "объединённые" },
  { relation: "CHILD", label: "Подтег", title: "Это подтег: работа с ним получает и этот тег; «нет» этого тега — «нет» и ему", filter: "подтеги" },
  { relation: "PARENT", label: "Надтег", title: "Это надтег: работа с этим тегом получает и его; «нет» ему — «нет» и этому", filter: "надтеги" },
  { relation: "DIFFERENT", label: "Разные", title: "Это другой тег, просто похоже назван", filter: "разные" },
];

/** The quiet after the last answer before the table is read anew. */
const REFRESH_AFTER_MS = 2000;

/** How long the rows move to their places once the table is read anew, and how long the buttons stay locked after that. */
const MOVE_MS = 1000;
const SETTLE_MS = 1000;

/** The key of a row: a normalized tag's, or a site's tag's bound to this one. */
function rowKey(tag: string, site?: TagVariantInfo): string {
  return site ? `site:${site.source}/${site.tag}` : tag;
}

/**
 * The tags alike this one by the meaning of their names, of one site or another — offered only —
 * every tag the user said something of to this one, and every site's tag bound to it. Every row of a
 * tag has the user's four words on it: the same tag (merged), a tag under it, a tag above it, a
 * different one; pressed again, taken back. A row of a site's tag is merged: unpressed, the site's
 * tag is taken out to a tag of its own. A word said keeps the row in its place among the others; the
 * filters above hide the rows said so.
 *
 * AN ANSWER IS SAVED AT ONCE, THE TABLE READ ANEW AFTER A QUIET: a press is sent at once — the
 * answers one after another, in the order pressed — and shown pressed at once; the table, whose
 * order a merge changes, is read anew two seconds after the last press, in the background, the
 * buttons free. Once it is here the buttons are locked, the rows move to their new places for a
 * second, and the buttons are free a second after — so a row that moved is never pressed by the
 * click meant for the one that was there.
 */
function Similar({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  const [shown, setShown] = useState<Record<Said, boolean>>({ MERGED: true, CHILD: true, PARENT: true, DIFFERENT: true });
  const [locked, setLocked] = useState(false);
  /** The answers given since the table was read, by tag: shown pressed before the table says so. */
  const [said, setSaid] = useState<Record<string, TagRelation>>({});
  const [error, setError] = useState<string | null>(null);
  const rows = useRef(new Map<string, HTMLLIElement>());
  /** Where every row was before the table was read anew, by tag: what the rows move from. */
  const before = useRef<Map<string, number> | null>(null);
  /** The answers sent, one after another. */
  const sending = useRef<Promise<void>>(Promise.resolve());
  /** How many presses there were: a reading planned before the last one is not the last. */
  const presses = useRef(0);
  const refreshTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const unlock = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  useEffect(
    () => () => {
      clearTimeout(unlock.current);
      clearTimeout(refreshTimer.current);
    },
    [],
  );
  const queryClient = useQueryClient();
  const similarQuery = async () =>
    unwrap(await api.GET("/api/types/{type}/tags/similar", { params: { path: { type: type.id }, query: { tag: tag.key, limit: SIMILAR } } }));
  const similar = useQuery({ queryKey: [TAGS, type.id, "similar", tag.key], queryFn: similarQuery });

  /**
   * The table read anew once every answer is saved — unless the user pressed again meanwhile — in the
   * background, the buttons free; only once it is here are they locked and the rows moved to it.
   */
  const refresh = async (asked: number) => {
    await sending.current;
    if (asked !== presses.current) return;
    const key = [TAGS, type.id, "similar", tag.key];
    let read;
    try {
      read = await queryClient.fetchQuery({ queryKey: [...key, "fresh"], queryFn: similarQuery, staleTime: 0 });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return;
    }
    queryClient.removeQueries({ queryKey: [...key, "fresh"], exact: true });
    // Pressed again while it was read: the reading after that press shows it
    if (asked !== presses.current) return;
    const shownBefore = queryClient.getQueryData(key);
    before.current = new Map([...rows.current].map(([k, el]) => [k, el.getBoundingClientRect().top]));
    queryClient.setQueryData(key, read);
    if (queryClient.getQueryData(key) === shownBefore) {
      // The same table: nothing moves, nothing is locked
      before.current = null;
      setSaid({});
    } else {
      setLocked(true);
    }
    // The tag itself, the list and the works read anew too; the table is current already — read again, it would move without the rows
    await Promise.all([
      queryClient.invalidateQueries({ queryKey: [TAGS, type.id], predicate: (q) => q.queryKey[2] !== "similar" }),
      queryClient.invalidateQueries({ queryKey: ["item"] }),
      queryClient.invalidateQueries({ queryKey: ["facets", type.id] }),
    ]);
  };

  /** The user's word on a row: of a tag, what it is to this one; of a site's tag, bound to this one or taken out to one of its own. */
  const answer = (other: string, relation: TagRelation, site?: TagVariantInfo) => {
    const row = rowKey(other, site);
    setSaid((s) => ({ ...s, [row]: relation }));
    setError(null);
    const asked = ++presses.current;
    const path = { params: { path: { type: type.id } } };
    sending.current = sending.current.then(async () => {
      try {
        if (site)
          unwrap(
            await api.PUT("/api/types/{type}/tags/binding", {
              ...path,
              body: { source: site.source, siteTag: site.tag, tag: relation === "MERGED" ? tag.key : undefined },
            }),
          );
        else unwrap(await api.PUT("/api/types/{type}/tags/relation", { ...path, body: { tag: tag.key, other, relation } }));
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        // Not saved: the row shows what the table says again
        setSaid((s) => Object.fromEntries(Object.entries(s).filter(([k]) => k !== row)));
      }
    });
    clearTimeout(refreshTimer.current);
    refreshTimer.current = setTimeout(() => void refresh(asked), REFRESH_AFTER_MS);
  };

  // The rows read anew: each moved from where it was to where it is, a new one faded in
  useLayoutEffect(() => {
    const was = before.current;
    if (!was || !similar.data) return;
    before.current = null;
    setSaid({});
    rows.current.forEach((el, key) => {
      const top = was.get(key);
      el.style.transition = "none";
      if (top === undefined) el.style.opacity = "0";
      else el.style.transform = `translateY(${top - el.getBoundingClientRect().top}px)`;
    });
    requestAnimationFrame(() =>
      rows.current.forEach((el) => {
        el.style.transition = `transform ${MOVE_MS}ms ease, opacity ${MOVE_MS}ms ease`;
        el.style.transform = "";
        el.style.opacity = "";
      }),
    );
    clearTimeout(unlock.current);
    unlock.current = setTimeout(() => setLocked(false), MOVE_MS + SETTLE_MS);
  }, [similar.data]);

  const visible = similar.data?.filter((s) => s.relation === "NONE" || shown[s.relation]);

  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">Похожие по смыслу названия</h2>
      <p className="text-xs text-muted-foreground">
        Только предложение: решаете вы. «Объединить» — это тот же тег; «Подтег» — более узкий, работа с ним получает и этот тег;
        «Надтег» — более широкий; «Разные» — просто похоже названы. Нажатая кнопка снимается повторным нажатием.
      </p>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
        <span className="text-muted-foreground">Показывать:</span>
        {RELATIONS.map((r) => (
          <label key={r.relation} className="flex items-center gap-1">
            <input type="checkbox" checked={shown[r.relation]} onChange={() => setShown({ ...shown, [r.relation]: !shown[r.relation] })} className="size-3.5" />
            {r.filter}
          </label>
        ))}
        {locked && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
      </div>
      {similar.isLoading && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" /> Ищу похожие — в первый раз названия всех тегов переводятся в векторы, это
          может занять минуту
        </p>
      )}
      {similar.isError && <p className="text-destructive">Похожие не нашлись: {similar.error.message}</p>}
      {similar.data && similar.data.length === 0 && <p className="text-muted-foreground">Похожих нет.</p>}
      {error && <p className="text-destructive">Не сохранилось: {error}</p>}
      {visible && visible.length > 0 && (
        <ul className={cn("flex flex-col divide-y rounded-lg border", locked && "pointer-events-none")} aria-busy={locked}>
          {visible.map(({ tag: s, similarity, relation: read, site }) => {
            const row = rowKey(s.key, site);
            // Pressed as the user said it, before the table is read anew
            const relation = said[row] ?? read;
            // The only site's tag of this one is not taken out: the tag would be left with none
            const only = site !== undefined && tag.variants.length === 1;
            return (
            <li
              key={row}
              ref={(el) => {
                if (el) rows.current.set(row, el);
                else rows.current.delete(row);
              }}
              className={cn("flex flex-wrap items-center gap-x-3 gap-y-1.5 bg-background px-3 py-2", relation !== "NONE" && "bg-muted/40")}
            >
              {/* The name in full and the sites under it; on a narrow screen the buttons take a line of their own */}
              <div className="flex min-w-0 flex-1 basis-48 flex-col">
                {site ? (
                  <span className="break-words" title="Тег сайта, привязанный к этому тегу">
                    {site.name}
                  </span>
                ) : (
                  <a
                    href={screenHref({ kind: "tags", tag: s.key })}
                    onClick={(e) => followLink(e, { kind: "tags", tag: s.key })}
                    className="break-words underline-offset-2 hover:underline"
                    title={s.variants.map((v) => `${sourceTitle(type, v.source)}: ${v.name}`).join(" · ")}
                  >
                    {s.name}
                  </a>
                )}
                <span className="truncate text-xs text-muted-foreground">
                  {site ? `тег сайта ${sourceTitle(type, site.source)}` : sitesOf(s).map((x) => sourceTitle(type, x)).join(", ")}
                </span>
              </div>
              <span className="shrink-0 text-right text-xs tabular-nums text-muted-foreground">
                <span title="Работ с тегом">{site ? site.works : s.works}</span>
                {" · "}
                <span title="Сходство смысла названий">{Math.round(similarity * 100)}%</span>
              </span>
              <div className="flex w-full gap-1 sm:w-auto">
                {RELATIONS.map((r) => {
                  const pressed = relation === r.relation;
                  // A site's tag is bound to a tag or not: under, above, different are said of the tag it is taken out to
                  const ofTags = site !== undefined && r.relation !== "MERGED";
                  return (
                    <Button
                      key={r.relation}
                      variant={pressed ? "default" : "outline"}
                      size="xs"
                      className="flex-1 sm:flex-none"
                      aria-pressed={pressed}
                      disabled={locked || ofTags || (only && pressed)}
                      onClick={() => answer(s.key, pressed ? "NONE" : r.relation, site)}
                      title={
                        ofTags
                          ? "Тег сайта: сначала выньте его в отдельный тег (отожмите «Объединить»)"
                          : only && pressed
                            ? "Единственный тег сайта у этого тега"
                            : pressed
                              ? `${r.title}. Нажмите ещё раз, чтобы снять`
                              : r.title
                      }
                    >
                      {r.label}
                    </Button>
                  );
                })}
              </div>
            </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
