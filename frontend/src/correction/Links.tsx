import { useEffect, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckIcon, ExternalLinkIcon, Link2Icon, Loader2Icon, SearchIcon, UnlinkIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, refKey, sameItem, unwrap, type ContentTypeInfo, type ItemRef, type ItemSummary } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { sourceTitle } from "@/contenttype/types";
import type { Corrections } from "./useCorrections";

const FOUND = 8;

/**
 * The other items that are this same work — on another site, or twice on one — and a search to
 * add one: the items of the same content type by a piece of the title or by the address of the
 * page, each item by itself, picked by a click.
 */
export function Links({ type, summary, corrections }: { type: ContentTypeInfo; summary: ItemSummary; corrections: Corrections }) {
  const { t } = useTranslation("correction");
  const [searching, setSearching] = useState(false);
  return (
    <section>
      <div className="mb-2 flex items-center gap-1">
        <h4 className="text-sm font-semibold">{t("Links.heading")}</h4>
        {!searching && (
          <Button variant="ghost" size="xs" className="text-muted-foreground" onClick={() => setSearching(true)}>
            <Link2Icon /> {t("Links.link")}
          </Button>
        )}
      </div>
      {summary.linked.length === 0 && !searching && (
        <p className="text-xs text-muted-foreground">
          {t("Links.hint")}
        </p>
      )}
      <ul className="flex flex-col gap-1 text-sm">
        {summary.linked.map((l) => (
          <li key={refKey(l)} className="flex items-center gap-2">
            <span className="shrink-0 text-xs text-muted-foreground">{sourceTitle(type, l.source)}</span>
            <a href={l.url} target="_blank" rel="noreferrer" className="min-w-0 flex-1 truncate underline-offset-2 hover:underline" title={l.title}>
              {l.title}
              <ExternalLinkIcon className="ml-1 inline size-3 opacity-60" />
            </a>
            <AsyncButton
              variant="ghost"
              size="icon-xs"
              title={t("Links.unlink")}
              onClick={() => corrections.unlink(l)}
              icon={<UnlinkIcon />}
              className="text-muted-foreground"
            />
          </li>
        ))}
      </ul>
      {searching && <LinkSearch type={type} summary={summary} corrections={corrections} onDone={() => setSearching(false)} />}
    </section>
  );
}

function LinkSearch({
  type,
  summary,
  corrections,
  onDone,
}: {
  type: ContentTypeInfo;
  summary: ItemSummary;
  corrections: Corrections;
  onDone: () => void;
}) {
  const { t } = useTranslation("correction");
  const [input, setInput] = useState(summary.title);
  const [search, setSearch] = useState(summary.title);
  useEffect(() => {
    const timer = setTimeout(() => setSearch(input.trim()), 300);
    return () => clearTimeout(timer);
  }, [input]);

  const found = useQuery({
    queryKey: ["link-lookup", type.id, search],
    enabled: search !== "",
    queryFn: async () =>
      unwrap(await api.GET("/api/types/{type}/lookup", { params: { path: { type: type.id }, query: { query: search, limit: FOUND + 1 } } })),
  });
  const candidates = (found.data ?? []).filter((s) => !sameItem(s, summary)).slice(0, FOUND);
  const linked = (s: ItemRef) => summary.linked.some((l) => sameItem(l, s));

  return (
    <div className="mt-2 flex flex-col gap-2 rounded-lg border border-dashed p-2" onKeyDown={(e) => e.stopPropagation()}>
      <div className="relative">
        <SearchIcon className="pointer-events-none absolute top-1/2 left-2.5 size-4 -translate-y-1/2 text-muted-foreground" />
        <Input
          autoFocus
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => e.key === "Escape" && (e.preventDefault(), onDone())}
          placeholder={t("LinkSearch.placeholder")}
          className="h-8 pl-8"
          type="search"
        />
      </div>
      {found.isFetching && candidates.length === 0 && (
        <div className="flex justify-center py-2 text-muted-foreground">
          <Loader2Icon className="size-4 animate-spin" />
        </div>
      )}
      {!found.isFetching && search && candidates.length === 0 && <div className="text-xs text-muted-foreground">{t("LinkSearch.nothingFound")}</div>}
      <ul className="flex flex-col gap-1">
        {candidates.map((s) => (
          <li key={refKey(s)} className="flex items-center gap-1">
            <AsyncButton
              variant="ghost"
              className="h-auto min-w-0 flex-1 justify-start gap-2 px-1 py-1 text-left font-normal"
              onClick={async () => {
                await corrections.link(s);
                onDone();
              }}
              disabled={linked(s)}
              title={linked(s) ? t("LinkSearch.alreadyLinkedHint") : t("LinkSearch.linkHint")}
              icon={linked(s) ? <CheckIcon /> : <Link2Icon />}
            >
              <span className="flex min-w-0 flex-1 flex-col">
                <span className="truncate text-sm">{s.title}</span>
                <span className="truncate text-xs text-muted-foreground">
                  {sourceTitle(type, s.source)}
                  {linked(s) && ` · ${t("LinkSearch.alreadyLinked")}`}
                </span>
              </span>
            </AsyncButton>
            <a href={s.url} target="_blank" rel="noreferrer" className="shrink-0 p-1 text-muted-foreground hover:text-foreground" title={t("LinkSearch.sitePage")}>
              <ExternalLinkIcon className="size-3.5" />
            </a>
          </li>
        ))}
      </ul>
      <Button variant="ghost" size="sm" onClick={onDone} className="self-end">
        {t("LinkSearch.done")}
      </Button>
    </div>
  );
}
