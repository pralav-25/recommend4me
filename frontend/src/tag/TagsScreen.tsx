import { useEffect, useState } from "react";
import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, CombineIcon, Loader2Icon, SearchIcon, SplitIcon, TagsIcon, XIcon } from "lucide-react";
import { api, ensureOk, unwrap, type ContentTypeInfo, type TagInfo, type TagNameRule } from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { plural } from "@/i18n";
import { cn } from "@/lib/utils";
import { followLink, navigate, screenHref } from "@/screen/screens";

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
 * THE SCREEN OF THE TAGS: the sites' free tags of a content type, every site's together. A tag of
 * the application is a site's tag by its name, any case; the user merges tags that are one —
 * "Angst" and "Ангст" — names a tag, takes a tag out of one, tells two apart. The application only
 * offers tags alike by the meaning of their names; it merges nothing by itself.
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
            Свободные теги всех сайтов вместе. Тег приложения — тег сайта по его названию без учёта регистра: «Магия» одного
            сайта и «магия» другого — один тег. Одинаковые по смыслу теги («Angst» и «Ангст», «Enemies to Lovers» и «От врагов к
            возлюбленным») объединяете вы: выберите тег слева — справа будут похожие по смыслу названия, с любого сайта.
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
              {t.members.length > 0 && (
                <Badge variant="secondary" title={`Объединены: ${t.members.join(", ")}`}>
                  <CombineIcon /> {t.members.length + 1}
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
          <Variants type={type} tag={t} />
          {t.members.length > 0 && <Members type={type} tag={t} />}
          <Similar type={type} tag={t} />
        </>
      )}
    </section>
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

/** The tag's names as the sites write them, with their works. */
function Variants({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">Как пишут сайты</h2>
      <table className="w-full">
        <thead className="text-xs text-muted-foreground">
          <tr className="text-left">
            <th className="font-normal">Сайт</th>
            <th className="font-normal">Название</th>
            <th className="text-right font-normal">Работ</th>
            <th className="text-right font-normal">Оценено</th>
            <th className="text-right font-normal">Понравилось</th>
          </tr>
        </thead>
        <tbody>
          {tag.variants.map((v) => (
            <tr key={`${v.source}/${v.name}`} className="border-t">
              <td className="py-1 pr-2 text-muted-foreground">{sourceTitle(type, v.source)}</td>
              <td className="py-1 pr-2">{v.name}</td>
              <td className="py-1 text-right tabular-nums">{v.works}</td>
              <td className="py-1 text-right tabular-nums">{v.graded}</td>
              <td className="py-1 text-right tabular-nums">{v.liked}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  );
}

/** The tags merged into this one, each to take out again. */
function Members({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  const changed = useTagsChanged(type.id);
  const split = useMutation({
    mutationFn: async (member: string) => ensureOk(await api.POST("/api/types/{type}/tags/split", { params: { path: { type: type.id } }, body: { member } })),
    onSuccess: () => changed(),
  });
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">Объединены в этот тег</h2>
      <ul className="flex flex-wrap gap-1.5">
        {tag.members.map((m) => (
          <li key={m}>
            <Badge variant="outline" className="gap-1 pr-0.5">
              {m}
              <Button
                variant="ghost"
                size="icon-xs"
                onClick={() => split.mutate(m)}
                disabled={split.isPending}
                title="Вынуть: снова отдельный тег; ваши ответы остаются у этого"
                aria-label={`Вынуть ${m}`}
              >
                <SplitIcon />
              </Button>
            </Badge>
          </li>
        ))}
      </ul>
      {split.isError && <p className="text-destructive">Не получилось: {split.error.message}</p>}
    </section>
  );
}

/**
 * The tags alike this one by the meaning of their names, of one site or another — offered only: the
 * user ticks the ones that are this tag and merges them, or says a tag is a different one.
 */
function Similar({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  const changed = useTagsChanged(type.id);
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [name, setName] = useState("");
  const similar = useQuery({
    queryKey: [TAGS, type.id, "similar", tag.key],
    queryFn: async () =>
      unwrap(await api.GET("/api/types/{type}/tags/similar", { params: { path: { type: type.id }, query: { tag: tag.key, limit: SIMILAR } } })),
  });
  const merge = useMutation({
    mutationFn: async () =>
      unwrap(
        await api.POST("/api/types/{type}/tags/merge", {
          params: { path: { type: type.id } },
          body: { tags: [tag.key, ...chosen], name: name.trim() || undefined },
        }),
      ),
    onSuccess: async (t) => {
      setChosen(new Set());
      setName("");
      await changed();
      navigate({ kind: "tags", tag: t.key });
    },
  });
  const distinct = useMutation({
    mutationFn: async (other: string) =>
      ensureOk(
        await api.PUT("/api/types/{type}/tags/distinct", { params: { path: { type: type.id } }, body: { first: tag.key, other, different: true } }),
      ),
    onSuccess: (_, other) => {
      setChosen((c) => new Set([...c].filter((k) => k !== other)));
      return similar.refetch();
    },
  });
  const toggle = (key: string) => setChosen((c) => (c.has(key) ? new Set([...c].filter((k) => k !== key)) : new Set([...c, key])));

  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">Похожие по смыслу названия</h2>
      <p className="text-xs text-muted-foreground">
        Только предложение: объединяете вы. Отметьте теги, которые означают то же самое, — или нажмите «Разные», и тег больше
        не будет предлагаться для этого.
      </p>
      {similar.isLoading && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" /> Ищу похожие — в первый раз названия всех тегов переводятся в векторы, это
          может занять минуту
        </p>
      )}
      {similar.isError && <p className="text-destructive">Похожие не нашлись: {similar.error.message}</p>}
      {similar.data && similar.data.length === 0 && <p className="text-muted-foreground">Похожих нет.</p>}
      {similar.data && similar.data.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {similar.data.map(({ tag: s, similarity }) => (
            <li key={s.key} className="flex items-center gap-2 px-3 py-1.5">
              <input type="checkbox" checked={chosen.has(s.key)} onChange={() => toggle(s.key)} aria-label={`Объединить с «${s.name}»`} className="size-4" />
              <a
                href={screenHref({ kind: "tags", tag: s.key })}
                onClick={(e) => followLink(e, { kind: "tags", tag: s.key })}
                className="min-w-0 flex-1 truncate underline-offset-2 hover:underline"
                title={s.variants.map((v) => `${sourceTitle(type, v.source)}: ${v.name}`).join(" · ")}
              >
                {s.name}
              </a>
              <span className="text-xs text-muted-foreground">{sitesOf(s).map((x) => sourceTitle(type, x)).join(", ")}</span>
              <span className="w-10 text-right text-xs tabular-nums text-muted-foreground" title="Работ с тегом">
                {s.works}
              </span>
              <span className="w-12 text-right text-xs tabular-nums text-muted-foreground" title="Сходство смысла названий">
                {Math.round(similarity * 100)}%
              </span>
              <Button variant="ghost" size="xs" onClick={() => distinct.mutate(s.key)} disabled={distinct.isPending} title="Это другой тег: больше не предлагать">
                Разные
              </Button>
            </li>
          ))}
        </ul>
      )}
      {chosen.size > 0 && (
        <form
          className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/40 p-2"
          onSubmit={(e) => {
            e.preventDefault();
            merge.mutate();
          }}
        >
          <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Своё название (необязательно)" className="max-w-xs" />
          <Button type="submit" size="sm" disabled={merge.isPending}>
            {merge.isPending ? <Loader2Icon className="animate-spin" /> : <CombineIcon />}
            Объединить {chosen.size + 1} в «{name.trim() || tag.name}»
          </Button>
          {merge.isError && <span className="w-full text-destructive">Не объединилось: {merge.error.message}</span>}
        </form>
      )}
      {distinct.isError && <p className="text-destructive">Не сохранилось: {distinct.error.message}</p>}
    </section>
  );
}
