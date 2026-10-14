import { type ReactNode, useEffect, useLayoutEffect, useRef, useState } from "react";
import { keepPreviousData, useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeftIcon, CombineIcon, ExternalLinkIcon, Link2Icon, Loader2Icon, RefreshCwIcon, SearchIcon, TagsIcon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import {
  api,
  unwrap,
  type ContentTypeInfo,
  type FoundTagEntry,
  type HierarchyOffer,
  type SimilarTag,
  type SimilarTagPage,
  type TagInfo,
  type TagNameRule,
  type TagRef,
  type TagRelation,
  type TagVariantInfo,
} from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { ButtonGroup } from "@/components/ui/button-group";
import { Input } from "@/components/ui/input";
import { typeTitle } from "@/contenttype/types";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { usePref } from "@/lib/usePref";
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

/** The key of the words saying how a tag's name was chosen. */
function ruleKey(rule: TagNameRule) {
  return `Rule.${rule}` as const;
}

/**
 * The names of the sites' tags merged into a tag other than the one it is shown by, each once, any
 * case, in the order the name is chosen by (TagInfo.names): without a name the user gave, the first
 * is the tag's name, the rest follow it; with one, the first leads the rest.
 */
function otherNames(tag: TagInfo): string[] {
  const seen = new Set([tag.name.toLowerCase()]);
  return tag.names.filter((n) => !seen.has(n.toLowerCase()) && seen.add(n.toLowerCase()));
}

/**
 * A tag's name, then the other names merged into it in brackets in a fainter type — cut with an
 * ellipsis where the row ends, every one of them in the title.
 */
function TagNames({ tag, className, children }: { tag: TagInfo; className?: string; children?: ReactNode }) {
  const others = otherNames(tag);
  return (
    <span className={cn("flex min-w-0 items-baseline gap-1", className)} title={[tag.name, ...others].join(" · ")}>
      {/* The name first, whole as far as the row allows; the others in what is left */}
      {children ?? <span className="max-w-full shrink-0 truncate">{tag.name}</span>}
      {others.length > 0 && <span className="min-w-0 truncate text-muted-foreground">({others.join(", ")})</span>}
    </span>
  );
}

/** The sites a tag is used on, by their short titles ("КФ, AO3"), the full ones in the title. */
function SiteNames({ tag, className }: { tag: TagInfo; className?: string }) {
  const sites = sitesOf(tag);
  return (
    <span className={cn("truncate text-xs text-muted-foreground", className)} title={sites.map(sourceTitle).join(", ")}>
      {sites.map(sourceShort).join(", ")}
    </span>
  );
}

/** The sites a tag is used on, each once. */
function sitesOf(tag: TagInfo): string[] {
  return [...new Set(tag.variants.map((v) => v.source))];
}

/**
 * THE SCREEN OF THE TAGS: the normalized tags of a content type's free tags. Every site's tag is
 * bound to a normalized tag — at first to the one of its name, any case; the user merges tags that
 * are one — "Angst" and its Russian spelling — names a tag, takes a site's tag out of one, puts tags under
 * others, tells two apart. The application only offers tags alike by the meaning of their names; it
 * merges nothing by itself.
 */
export function TagsScreen({ type, selected }: { type: ContentTypeInfo; selected?: string }) {
  const { t } = useTranslation("tag");
  return (
    <div className="mx-auto grid max-w-[1800px] grid-cols-[minmax(0,1fr)] gap-4 px-4 py-4 lg:grid-cols-[minmax(0,28rem)_minmax(0,1fr)]">
      <div className={cn("flex flex-col gap-6", selected && "hidden lg:flex")}>
        <TagList type={type} selected={selected} />
        <HierarchyOffers type={type} />
      </div>
      {selected ? (
        <TagDetails key={selected} type={type} tag={selected} />
      ) : (
        <div className="hidden flex-col gap-2 text-sm text-muted-foreground lg:flex">
          <h1 className="flex items-center gap-2 text-xl font-semibold text-foreground">
            <TagsIcon className="size-5" /> {t("TagsScreen.title", { type: typeTitle(type.id).toLowerCase() })}
          </h1>
          <p>{t("TagsScreen.intro")}</p>
          <p>{t("TagsScreen.nameRule")}</p>
        </div>
      )}
    </div>
  );
}

function TagList({ type, selected }: { type: ContentTypeInfo; selected?: string }) {
  const { t } = useTranslation("tag");
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  const [limit, setLimit] = useState(PAGE);
  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(input.trim());
      setLimit(PAGE);
    }, QUERY_SETTLE_MS);
    return () => clearTimeout(timer);
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
        <Input value={input} onChange={(e) => setInput(e.target.value)} placeholder={t("TagList.searchPlaceholder")} className="pl-8" type="search" />
      </div>
      <div className="flex items-center gap-2 text-xs text-muted-foreground">
        {page && <span>{t("TagList.total", { count: page.total })}</span>}
        {tags.isFetching && <Loader2Icon className="size-3.5 animate-spin" />}
      </div>
      {tags.isError && <p className="text-sm text-destructive">{t("TagList.loadError", { error: tags.error.message })}</p>}
      <ul className="flex flex-col divide-y rounded-lg border">
        {page?.tags.map((row) => (
          <li key={row.key}>
            <a
              href={screenHref({ kind: "tags", tag: row.key })}
              onClick={(e) => followLink(e, { kind: "tags", tag: row.key })}
              aria-current={row.key === selected ? "page" : undefined}
              className={cn("flex items-center gap-2 px-3 py-1.5 text-sm hover:bg-muted/50", row.key === selected && "bg-muted")}
            >
              {/* The names come first: at least half the row, the sites cut before them */}
              <TagNames tag={row} className="min-w-[50%] flex-1" />
              {row.variants.length > 1 && (
                <Badge variant="secondary" className="shrink-0" title={t("TagList.siteTags", { names: row.variants.map((v) => v.name).join(", ") })}>
                  <CombineIcon /> {row.variants.length}
                </Badge>
              )}
              <SiteNames tag={row} className="min-w-0" />
              <span className="w-10 text-right text-xs tabular-nums text-muted-foreground" title={t("TagList.works")}>
                {row.works}
              </span>
            </a>
          </li>
        ))}
      </ul>
      {page && page.tags.length < page.total && (
        <Button variant="outline" size="sm" onClick={() => setLimit(limit + PAGE)} disabled={tags.isFetching}>
          {t("TagList.more")}
        </Button>
      )}
    </section>
  );
}

/** How many offers of the tags' graph are shown, and added by "more". */
const OFFERS_PAGE = 20;

/** A tag's name, a link to its page. */
function TagLink({ tag }: { tag: TagRef }) {
  return (
    <a href={screenHref({ kind: "tags", tag: tag.key })} onClick={(e) => followLink(e, { kind: "tags", tag: tag.key })} className="font-medium underline-offset-2 hover:underline">
      {tag.name}
    </a>
  );
}

/**
 * THE TOP OFFERS OF THE TAGS' GRAPH by the catalogues, the ones that change most of the graded works
 * first: a tag under another, two tags of one entry merged, a new tag above two or more. Read apart
 * from the list, the old shown while read anew; each said by its own button, or left as it is.
 */
function HierarchyOffers({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged(type.id);
  const [limit, setLimit] = useState(OFFERS_PAGE);
  const offers = useQuery({
    queryKey: [TAGS, type.id, "hierarchy-offers"],
    placeholderData: keepPreviousData,
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/tags/hierarchy-offers", { params: { path: { type: type.id } } })),
  });
  const path = { params: { path: { type: type.id } } };
  const act = async (o: HierarchyOffer) => {
    const [first, second] = o.tags;
    if (o.kind === "LINK") unwrap(await api.PUT("/api/types/{type}/tags/relation", { ...path, body: { tag: first.key, other: second.key, relation: "PARENT" } }));
    else if (o.kind === "MERGE") unwrap(await api.PUT("/api/types/{type}/tags/relation", { ...path, body: { tag: first.key, other: second.key, relation: "MERGED" } }));
    else
      unwrap(
        await api.POST("/api/types/{type}/tags/above", {
          ...path,
          body: { catalogue: o.catalogue, entry: o.entry!, children: o.tags.map((r) => r.key), tag: o.existing?.key },
        }),
      );
    await changed();
  };
  const list = offers.data ?? [];
  return (
    <section className="flex flex-col gap-2 text-sm">
      <div className="flex items-center gap-2">
        <h2 className="font-semibold">{t("Hierarchy.title")}</h2>
        {offers.isFetching && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
        <AsyncButton variant="ghost" size="xs" className="ml-auto" onClick={() => offers.refetch()} title={t("Hierarchy.refreshTitle")} icon={<RefreshCwIcon />}>
          {t("Hierarchy.refresh")}
        </AsyncButton>
      </div>
      <p className="text-xs text-muted-foreground">{t("Hierarchy.hint")}</p>
      {offers.isError && <p className="text-destructive">{t("Hierarchy.loadError", { error: offers.error.message })}</p>}
      {offers.data && list.length === 0 && <p className="text-muted-foreground">{t("Hierarchy.none")}</p>}
      {list.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {list.slice(0, limit).map((o) => (
            <li key={`${o.kind}/${o.tags.map((r) => r.key).join("/")}/${o.entry ?? ""}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-1.5">
              <div className="flex min-w-0 flex-1 flex-col">
                <span>
                  {o.kind === "LINK" && (
                    <>
                      <TagLink tag={o.tags[0]} /> {t("Hierarchy.under")} <TagLink tag={o.tags[1]} />
                    </>
                  )}
                  {o.kind === "MERGE" && (
                    <>
                      <TagLink tag={o.tags[1]} /> {t("Hierarchy.into")} <TagLink tag={o.tags[0]} />
                    </>
                  )}
                  {o.kind === "BIND_PARENT" && (
                    <>
                      <TagLink tag={o.existing!} /> {t("Hierarchy.as")}{" "}
                      <a href={o.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">
                        «{o.path[0]}» ({o.entry})
                      </a>{" "}
                      {t("Hierarchy.above")}{" "}
                      {o.tags.map((r, i) => (
                        <span key={r.key}>
                          {i > 0 && ", "}
                          <TagLink tag={r} />
                        </span>
                      ))}
                    </>
                  )}
                  {o.kind === "NEW_PARENT" && (
                    <>
                      {t("Hierarchy.newTag")}{" "}
                      <a href={o.url} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline">
                        «{o.path[0]}»
                      </a>{" "}
                      {t("Hierarchy.above")}{" "}
                      {o.tags.map((r, i) => (
                        <span key={r.key}>
                          {i > 0 && ", "}
                          <TagLink tag={r} />
                        </span>
                      ))}
                    </>
                  )}
                </span>
                <span className="text-xs text-muted-foreground">
                  {sourceTitle(o.catalogue)}: {o.kind === "MERGE" ? t("Hierarchy.sameEntry", { entry: `${o.path[0]} (${o.entry})` }) : o.path.join(" → ")} ·{" "}
                  {o.kind !== "MERGE" && <>{t("Hierarchy.levels", { count: o.levels })} · </>}
                  {t("Hierarchy.graded", { count: o.graded })}
                </span>
              </div>
              <AsyncButton variant="outline" size="sm" onClick={() => act(o)} title={t(`Hierarchy.${o.kind}.title`)}>
                {t(`Hierarchy.${o.kind}.label`)}
              </AsyncButton>
            </li>
          ))}
        </ul>
      )}
      {list.length > limit && (
        <Button variant="outline" size="sm" onClick={() => setLimit(limit + OFFERS_PAGE)}>
          {t("Hierarchy.more", { count: list.length - limit })}
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
  const { t } = useTranslation("tag");
  const info = useQuery({
    queryKey: [TAGS, type.id, "one", tag],
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/tags/one", { params: { path: { type: type.id }, query: { tag } } })),
  });
  const found = info.data;
  return (
    <section className="flex min-w-0 flex-col gap-4">
      <a
        href={screenHref({ kind: "tags" })}
        onClick={(e) => followLink(e, { kind: "tags" })}
        className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground lg:hidden"
      >
        <ArrowLeftIcon className="size-4" /> {t("TagDetails.allTags")}
      </a>
      {info.isLoading && <Loader2Icon className="size-5 animate-spin text-muted-foreground" />}
      {info.isError && <p className="text-sm text-destructive">{t("TagDetails.loadError", { error: info.error.message })}</p>}
      {found && (
        <>
          <header className="flex flex-col gap-1">
            <h1 className="text-xl font-semibold">{found.name}</h1>
            <p className="text-sm text-muted-foreground">
              {t(ruleKey(found.rule))} · {t("TagDetails.works", { count: found.works })} · {t("TagDetails.graded", { graded: found.graded })} ·{" "}
              {t("TagDetails.liked", { liked: found.liked })}
            </p>
          </header>
          <TagDescriptions tag={found} />
          <TagName type={type} tag={found} />
          <TagEntry type={type} tag={found} />
          <Related tag={found} />
          <CatalogueOffers type={type} tag={found} />
          <Variants tag={found} />
          <Similar type={type} tag={found} />
        </>
      )}
    </section>
  );
}

/** The tags right above and under this one, each a link to its own page. */
function Related({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
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
        <div title={t("Related.parentsTitle")}>
          <span className="text-muted-foreground">{t("Related.parents")} </span>
          {links(tag.parents)}
        </div>
      )}
      {tag.children.length > 0 && (
        <div title={t("Related.childrenTitle")}>
          <span className="text-muted-foreground">{t("Related.children")} </span>
          {links(tag.children)}
        </div>
      )}
    </div>
  );
}

/**
 * Other tags the catalogues offer above or under this one — by what the entries they are bound to
 * are a kind of, the way up shown — each said so by its own button, or left as it is.
 */
function CatalogueOffers({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged(type.id);
  if (tag.catalogueOffers.length === 0) return null;
  const relate = async (other: string, relation: TagRelation) => {
    unwrap(await api.PUT("/api/types/{type}/tags/relation", { params: { path: { type: type.id } }, body: { tag: tag.key, other, relation } }));
    await changed();
  };
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">{t("Offers.title")}</h2>
      <ul className="flex flex-col divide-y rounded-lg border">
        {tag.catalogueOffers.map((o) => (
          <li key={o.tag.key} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-1.5">
            <div className="flex min-w-0 flex-1 flex-col">
              <span>
                <span className="text-muted-foreground">{t(`Relation.${o.relation as Said}.label`)} </span>
                <a href={screenHref({ kind: "tags", tag: o.tag.key })} onClick={(e) => followLink(e, { kind: "tags", tag: o.tag.key })} className="underline-offset-2 hover:underline">
                  {o.tag.name}
                </a>
              </span>
              <span className="text-xs text-muted-foreground">
                {sourceTitle(o.catalogue)}: {o.path.join(" → ")}
              </span>
            </div>
            <AsyncButton variant="outline" size="sm" onClick={() => relate(o.tag.key, o.relation)} title={t(`Relation.${o.relation as Said}.title`)}>
              {t(o.relation === "PARENT" ? "Offers.makeParent" : "Offers.makeChild")}
            </AsyncButton>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The name the user gives the tag, or none: then the sites' names decide. */
function TagName({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged(type.id);
  const [name, setName] = useState(tag.explicitName ?? "");
  const save = useMutation({
    mutationFn: async (value: string | null) =>
      unwrap(await api.PUT("/api/types/{type}/tags/name", { params: { path: { type: type.id } }, body: { tag: tag.key, name: value ?? undefined } })),
    onSuccess: (saved) => {
      setName(saved.explicitName ?? "");
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
      <Input value={name} onChange={(e) => setName(e.target.value)} placeholder={tag.name} className="max-w-sm" aria-label={t("TagName.label")} />
      <Button type="submit" size="sm" disabled={save.isPending || name.trim() === (tag.explicitName ?? "")}>
        {save.isPending && <Loader2Icon className="animate-spin" />} {t("TagName.save")}
      </Button>
      {tag.explicitName && (
        <Button type="button" variant="ghost" size="sm" onClick={() => save.mutate(null)} disabled={save.isPending} title={t("TagName.resetTitle")}>
          <XIcon /> {t("TagName.reset")}
        </Button>
      )}
      {save.isError && <span className="w-full text-sm text-destructive">{t("TagName.saveError", { error: save.error.message })}</span>}
    </form>
  );
}

/**
 * What the tag means: as its sites' tags' own pages say, then as the entries it is bound to do —
 * each with who says it.
 */
function TagDescriptions({ tag }: { tag: TagInfo }) {
  if (tag.descriptions.length === 0) return null;
  return (
    <ul className="flex flex-col gap-1 text-sm">
      {tag.descriptions.map((d, i) => (
        <li key={i}>
          {d.text}{" "}
          <span className="text-xs text-muted-foreground">
            — {sourceTitle(d.origin)}
            {d.name && d.name !== tag.name ? `, «${d.name}»` : ""}
          </span>
        </li>
      ))}
    </ul>
  );
}

/** Every name the tag goes by, its name first: what the search of an entry is offered to start from. */
function searchNames(tag: TagInfo): string[] {
  return [tag.name, ...otherNames(tag)];
}

/**
 * The entries of the catalogues (Wikidata) the tag is bound to — the label of the one bound first
 * names the tag in the user's language, every name of them finds it — and the search of more: by
 * any of the tag's names offered at once, or by a name, a Q-id or an entry's address typed. A tag
 * bound to none is searched for by its name as it opens: what is found is only offered.
 */
function TagEntry({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const changed = useTagsChanged(type.id);
  const [text, setText] = useState("");
  const [found, setFound] = useState<FoundTagEntry[] | null>(null);
  const search = async (query: string) =>
    unwrap(await api.GET("/api/types/{type}/tags/entries", { params: { path: { type: type.id }, query: { query } } }));
  // The search is the press of its button: busy while it is ([AsyncButton])
  const find = async (query: string) => {
    if (!query.trim()) return;
    setText(query);
    setFound(await search(query.trim()));
  };
  // A tag bound to none: its name searched for once, as the tag opens
  const unbound = tag.catalogueEntries.length === 0;
  const [autoFailed, setAutoFailed] = useState<string | null>(null);
  useEffect(() => {
    if (!unbound) return;
    let alive = true;
    search(tag.name).then(
      (list) => alive && setFound((was) => was ?? list),
      (e: unknown) => alive && setAutoFailed(e instanceof Error ? e.message : String(e)),
    );
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once a tag, as it opens
  }, [tag.key]);
  const bind = async (catalogue: string, entry: string, bound: boolean) => {
    unwrap(await api.PUT("/api/types/{type}/tags/entry", { params: { path: { type: type.id } }, body: { tag: tag.key, catalogue, entry, bound } }));
    await changed();
  };
  const isBound = (f: FoundTagEntry) => tag.catalogueEntries.some((b) => b.catalogue === f.catalogue && b.entry === f.entry);
  // Read of the catalogues again: what was kept is shown till the new comes
  const refresh = async () => {
    unwrap(await api.POST("/api/types/{type}/tags/entries/refresh", { params: { path: { type: type.id }, query: { tag: tag.key } } }));
    await changed();
  };
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <div className="flex items-center gap-2">
        <h2 className="font-semibold">{t("Entry.title")}</h2>
        {!unbound && (
          <AsyncButton variant="ghost" size="xs" onClick={refresh} title={t("Entry.refreshTitle")} icon={<RefreshCwIcon />}>
            {t("Entry.refresh")}
          </AsyncButton>
        )}
      </div>
      {tag.catalogueEntries.map((b) => (
        <div key={`${b.catalogue}/${b.entry}`} className="flex flex-wrap items-start gap-x-3 gap-y-1">
          <div className="flex min-w-0 flex-1 flex-col">
            <a href={b.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">
              {b.label ?? b.entry} <span className="text-muted-foreground">({sourceTitle(b.catalogue)} {b.entry})</span>
              <ExternalLinkIcon className="ml-1 inline size-3 opacity-60" />
            </a>
            {b.description && <span className="text-muted-foreground">{b.description}</span>}
            {b.names.length > 1 && (
              <span className="truncate text-xs text-muted-foreground" title={b.names.join(", ")}>
                {t("Entry.names", { names: b.names.filter((n) => n !== b.label).join(", ") })}
              </span>
            )}
          </div>
          <AsyncButton variant="ghost" size="sm" onClick={() => bind(b.catalogue, b.entry, false)} title={t("Entry.unbindTitle")} icon={<XIcon />}>
            {t("Entry.unbind")}
          </AsyncButton>
        </div>
      ))}
      {unbound && <p className="text-xs text-muted-foreground">{t("Entry.hint")}</p>}
      <div className="flex flex-wrap items-center gap-1.5">
        <span className="text-xs text-muted-foreground">{t("Entry.byName")}</span>
        {searchNames(tag).map((name) => (
          <AsyncButton key={name} variant="outline" size="xs" onClick={() => find(name)} title={t("Entry.byNameTitle", { name })}>
            {name}
          </AsyncButton>
        ))}
      </div>
      <form
        className="flex flex-wrap items-center gap-2"
        onKeyDown={(e) => e.stopPropagation()}
        onSubmit={(e) => e.preventDefault()}
      >
        <Input value={text} onChange={(e) => setText(e.target.value)} placeholder={t("Entry.placeholder")} className="max-w-sm" aria-label={t("Entry.placeholder")} />
        <AsyncButton type="submit" size="sm" onClick={() => find(text)} disabled={!text.trim()} icon={<SearchIcon />}>
          {t("Entry.find")}
        </AsyncButton>
      </form>
      {autoFailed && !found && <p className="text-xs text-destructive">{t("Entry.findError", { error: autoFailed })}</p>}
      {found && found.length === 0 && <p className="text-muted-foreground">{t("Entry.nothing")}</p>}
      {found && found.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {found.map((f) => (
            <li key={`${f.catalogue}/${f.entry}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-1.5">
              <div className="flex min-w-0 flex-1 flex-col">
                <a href={f.url} target="_blank" rel="noreferrer" className="underline-offset-2 hover:underline">
                  {f.label ?? f.entry} <span className="text-muted-foreground">({f.entry})</span>
                </a>
                {f.description && <span className="text-xs text-muted-foreground">{f.description}</span>}
                {f.tags
                  .filter((r) => r.key !== tag.key)
                  .map((r) => (
                    <a
                      key={r.key}
                      href={screenHref({ kind: "tags", tag: r.key })}
                      onClick={(e) => followLink(e, { kind: "tags", tag: r.key })}
                      className="text-xs text-maybe underline-offset-2 hover:underline"
                    >
                      {t("Entry.boundAlso", { name: r.name })}
                    </a>
                  ))}
              </div>
              {isBound(f) ? (
                <span className="text-xs text-muted-foreground">{t("Entry.boundHere")}</span>
              ) : (
                <AsyncButton variant="outline" size="sm" onClick={() => bind(f.catalogue, f.entry, true)} title={t("Entry.bindTitle")} icon={<Link2Icon />}>
                  {t("Entry.bind")}
                </AsyncButton>
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

/** The sites' tags bound to the tag, as the sites write them, with their works. */
function Variants({ tag }: { tag: TagInfo }) {
  const { t } = useTranslation("tag");
  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">{t("Variants.title")}</h2>
      <table className="w-full">
        <thead className="text-xs text-muted-foreground">
          <tr className="text-left">
            <th className="font-normal">{t("Variants.site")}</th>
            <th className="font-normal">{t("Variants.name")}</th>
            <th className="pl-3 text-right font-normal">{t("Variants.works")}</th>
            <th className="hidden pl-3 text-right font-normal sm:table-cell" title={t("Variants.gradedTitle")}>
              {t("Variants.graded")}
            </th>
            <th className="hidden pl-3 text-right font-normal sm:table-cell" title={t("Variants.likedTitle")}>
              {t("Variants.liked")}
            </th>
          </tr>
        </thead>
        <tbody>
          {tag.variants.map((v) => (
            <tr key={`${v.source}/${v.tag}`} className="border-t">
              <td className="py-1 pr-2 text-muted-foreground" title={sourceTitle(v.source)}>
                {sourceShort(v.source)}
              </td>
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

/**
 * What the user may say another tag is to this one: a button of every row, and a filter of the rows
 * said so; the words of each are the keys "Relation.<relation>.label", ".title" and ".filter".
 */
const RELATIONS: Said[] = ["MERGED", "CHILD", "PARENT", "DIFFERENT"];

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
 * tag is taken out to a tag of its own. A word said keeps the row in its place among the others.
 *
 * The server sorts and filters: the rows of the relations unticked above are left out (the choice
 * kept in the browser), alike the text searched for instead of the tag when one is typed, a page at
 * a time; a page read after the table was sorted anew may repeat a row, shown once.
 *
 * AN ANSWER IS SAVED AT ONCE, THE TABLE READ ANEW AFTER A QUIET: a press is sent at once — the
 * answers one after another, in the order pressed — and shown pressed at once; the table, whose
 * order a merge changes, is read anew two seconds after the last press, in the background, the
 * buttons free. Once it is here the buttons are locked, the rows move to their new places for a
 * second, and the buttons are free a second after — so a row that moved is never pressed by the
 * click meant for the one that was there.
 */
function Similar({ type, tag }: { type: ContentTypeInfo; tag: TagInfo }) {
  const { t } = useTranslation("tag");
  const [stored, setShown] = usePref<Partial<Record<Said, boolean>>>("tags.similar.shown", {});
  const shown: Record<Said, boolean> = { MERGED: true, CHILD: true, PARENT: true, DIFFERENT: true, ...stored };
  const hidden = RELATIONS.filter((r) => !shown[r]);
  const [input, setInput] = useState("");
  const [query, setQuery] = useState("");
  useEffect(() => {
    const timer = setTimeout(() => setQuery(input.trim()), QUERY_SETTLE_MS);
    return () => clearTimeout(timer);
  }, [input]);
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
  /** A page of the table as the filters and the search are now, read from [offset]. */
  const readPage = async (offset: number, limit: number, asked: { query: string; hidden: Said[] }) =>
    unwrap(
      await api.GET("/api/types/{type}/tags/similar", {
        params: {
          path: { type: type.id },
          query: { tag: tag.key, query: asked.query || undefined, hidden: asked.hidden, offset, limit },
        },
      }),
    );
  const key = [TAGS, type.id, "similar", tag.key, query, hidden.join(",")];
  const similar = useInfiniteQuery({
    queryKey: key,
    initialPageParam: 0,
    queryFn: ({ pageParam }) => readPage(pageParam, SIMILAR, { query, hidden }),
    // The next page from the rows read so far: a table sorted anew meanwhile repeats a row or two, left out below
    getNextPageParam: (last, pages) => {
      const read = pages.reduce((n, p) => n + p.tags.length, 0);
      return read < last.total ? read : undefined;
    },
  });
  /** The rows of every page read, each once: the first place it was read in stays. */
  const loaded = similar.data?.pages.flatMap((p) => p.tags);
  const seen = new Set<string>();
  const unique = loaded?.filter((s) => {
    const k = rowKey(s.tag.key, s.site ?? undefined);
    if (seen.has(k)) return false;
    seen.add(k);
    return true;
  });
  /** What the table is read with now: a reading planned before is made with what is current when it is made. */
  const current = useRef({ key, query, hidden, rows: 0 });
  current.current = { key, query, hidden, rows: loaded?.length ?? 0 };

  /**
   * The table read anew once every answer is saved — unless the user pressed again meanwhile — in the
   * background, the buttons free; as many rows as were read, in one page; only once it is here are
   * the buttons locked and the rows moved to it.
   */
  const refresh = async (asked: number) => {
    await sending.current;
    if (asked !== presses.current) return;
    const { key, query, hidden, rows: count } = current.current;
    let fresh;
    try {
      fresh = await readPage(0, Math.max(SIMILAR, count), { query, hidden });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return;
    }
    // Pressed again while it was read, or the filters changed: the reading after that shows it
    if (asked !== presses.current || current.current.key.join("\u0001") !== key.join("\u0001")) return;
    const signature = (rows: SimilarTag[] | undefined) => rows?.map((s) => `${rowKey(s.tag.key, s.site ?? undefined)}=${s.relation}`).join("|");
    const shownBefore = signature(queryClient.getQueryData<{ pages: SimilarTagPage[] }>(key)?.pages.flatMap((p) => p.tags));
    before.current = new Map([...rows.current].map(([k, el]) => [k, el.getBoundingClientRect().top]));
    queryClient.setQueryData(key, { pages: [fresh], pageParams: [0] });
    if (signature(fresh.tags) === shownBefore) {
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

  const visible = unique;
  const total = similar.data?.pages.at(-1)?.total;

  return (
    <section className="flex flex-col gap-1.5 text-sm">
      <h2 className="font-semibold">{t("Similar.title")}</h2>
      <p className="text-xs text-muted-foreground">{t("Similar.help")}</p>
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder={t("Similar.searchPlaceholder")}
          className="pl-8"
          type="search"
        />
      </div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs">
        <span className="text-muted-foreground">{t("Similar.show")}</span>
        {RELATIONS.map((r) => (
          <label key={r} className="flex items-center gap-1">
            <input type="checkbox" checked={shown[r]} onChange={() => setShown({ ...shown, [r]: !shown[r] })} className="size-3.5" />
            {t(`Relation.${r}.filter`)}
          </label>
        ))}
        {(locked || similar.isFetching) && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
      </div>
      {similar.isLoading && (
        <p className="flex items-center gap-2 text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" /> {t("Similar.searching")}
        </p>
      )}
      {similar.isError && <p className="text-destructive">{t("Similar.loadError", { error: similar.error.message })}</p>}
      {visible && visible.length === 0 && <p className="text-muted-foreground">{t("Similar.none")}</p>}
      {error && <p className="text-destructive">{t("Similar.saveError", { error })}</p>}
      {visible && visible.length > 0 && (
        <ul className={cn("flex flex-col divide-y rounded-lg border", locked && "pointer-events-none")} aria-busy={locked}>
          {visible.map(({ tag: s, similarity, relation: read, site: given }) => {
            // A row of a tag comes with no site's tag: null, not left out
            const site = given ?? undefined;
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
                  <span className="break-words" title={t("Similar.siteTagTitle")}>
                    {site.name}
                  </span>
                ) : (
                  <TagNames tag={s}>
                    <a
                      href={screenHref({ kind: "tags", tag: s.key })}
                      onClick={(e) => followLink(e, { kind: "tags", tag: s.key })}
                      className="max-w-full shrink-0 break-words underline-offset-2 hover:underline"
                      title={s.variants.map((v) => `${sourceTitle(v.source)}: ${v.name}`).join(" · ")}
                    >
                      {s.name}
                    </a>
                  </TagNames>
                )}
                {site ? (
                  <span className="truncate text-xs text-muted-foreground">{t("Similar.siteTagOf", { site: sourceTitle(site.source) })}</span>
                ) : (
                  <SiteNames tag={s} />
                )}
              </div>
              <span className="shrink-0 text-right text-xs tabular-nums text-muted-foreground">
                <span title={t("Similar.works")}>{site ? site.works : s.works}</span>
                {" · "}
                <span title={t("Similar.similarity")}>{Math.round(similarity * 100)}%</span>
              </span>
              <ButtonGroup className="w-full sm:w-fit">
                {RELATIONS.map((r) => {
                  const pressed = relation === r;
                  // A site's tag is bound to a tag or not: under, above, different are said of the tag it is taken out to
                  const ofTags = site !== undefined && r !== "MERGED";
                  const title = t(`Relation.${r}.title`);
                  return (
                    <Button
                      key={r}
                      variant={pressed ? "default" : "outline"}
                      size="xs"
                      className="flex-1 sm:flex-none"
                      aria-pressed={pressed}
                      disabled={locked || ofTags || (only && pressed)}
                      onClick={() => answer(s.key, pressed ? "NONE" : r, site)}
                      title={
                        ofTags
                          ? t("Similar.siteTagFirst")
                          : only && pressed
                            ? t("Similar.onlySiteTag")
                            : pressed
                              ? t("Similar.pressAgain", { title })
                              : title
                      }
                    >
                      {t(`Relation.${r}.label`)}
                    </Button>
                  );
                })}
              </ButtonGroup>
            </li>
            );
          })}
        </ul>
      )}
      {similar.hasNextPage && (
        <Button variant="outline" size="sm" onClick={() => void similar.fetchNextPage()} disabled={similar.isFetchingNextPage || locked}>
          {similar.isFetchingNextPage && <Loader2Icon className="animate-spin" />} {t("Similar.more")}
          {total !== undefined && visible && <span className="text-muted-foreground">{t("Similar.shownOf", { shown: visible.length, total })}</span>}
        </Button>
      )}
    </section>
  );
}
