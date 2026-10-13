import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { BookOpenTextIcon, ChevronDownIcon, Loader2Icon } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import { api, unwrap, type ItemRef, type PartInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { formatDate, formatDay } from "@/i18n/format";
import { InfluenceBadge } from "@/mark/MarkButtons";
import { cn } from "@/lib/utils";

const FIRST_SHOWN = 20;

/**
 * The parts of the work — chapters — as one line: how many, from when to when, how many of them
 * read (their text kept), how many of those raise the prediction and how many lower it; opened,
 * the list in order, each read part with the points it moves the work by, opening its text in place.
 */
export function Parts({ item, label, parts }: { item: ItemRef; label: string; parts: PartInfo[] }) {
  const { t } = useTranslation("part");
  const [listed, setListed] = useState(false);
  const [all, setAll] = useState(false);
  const [open, setOpen] = useState<string | null>(null);
  if (parts.length === 0) return null;
  const sorted = [...parts].sort((a, b) => a.position - b.position);
  const shown = all ? sorted : sorted.slice(0, FIRST_SHOWN);
  const dates = sorted.map((p) => p.publishedAt).filter((d): d is string => d != null).sort();
  const read = parts.filter((p) => p.hasText).length;
  // What the chapters read do to the prediction, each by itself
  const up = parts.filter((p) => p.influence != null && p.influence > 0).length;
  const down = parts.filter((p) => p.influence != null && p.influence < 0).length;
  return (
    <section>
      <button className="inline-flex flex-wrap items-center gap-x-1 text-left text-sm hover:underline" onClick={() => setListed(!listed)} aria-expanded={listed}>
        <ChevronDownIcon className={cn("size-4 shrink-0", !listed && "-rotate-90")} />
        <span className="font-semibold">{label}</span>
        <span className="text-muted-foreground">
          {parts.length}
          {dates.length > 0 && ` · ${formatDay(dates[0])} — ${formatDay(dates[dates.length - 1])}`}
          {` · ${t("Parts.withText", { n: read })}`}
          {up + down > 0 && (
            <span title={t("Parts.upDownHint")}>
              {" · "}
              <Trans
                t={t}
                i18nKey="Parts.upDown"
                values={{ up, down }}
                components={{ up: <span className="text-yes" />, down: <span className="text-no" /> }}
              />
            </span>
          )}
        </span>
      </button>
      {listed && (
      <ol className="mt-1 flex flex-col divide-y rounded-lg border">
        {shown.map((p) => (
          <li key={p.partId} className="text-sm">
            <button
              className={cn("flex w-full items-center gap-2 px-3 py-1.5 text-left", p.hasText ? "hover:bg-muted/50" : "cursor-default")}
              onClick={() => p.hasText && setOpen(open === p.partId ? null : p.partId)}
              disabled={!p.hasText}
              title={p.hasText ? t("Parts.showText") : t("Parts.noText")}
            >
              <span className="w-8 shrink-0 text-right text-xs tabular-nums text-muted-foreground">{p.position}</span>
              <span className={cn("min-w-0 flex-1 truncate", !p.hasText && "text-muted-foreground")}>{p.title ?? `#${p.position}`}</span>
              {p.influence != null && (
                <InfluenceBadge
                  influence={p.influence}
                  className="shrink-0 shadow-none"
                  title={t("Parts.influence")}
                />
              )}
              {p.publishedAt && <span className="shrink-0 text-xs text-muted-foreground">{formatDate(p.publishedAt)}</span>}
              {p.hasText && <ChevronDownIcon className={cn("size-4 shrink-0 text-muted-foreground", open !== p.partId && "-rotate-90")} />}
            </button>
            {open === p.partId && <PartText item={item} partId={p.partId} />}
          </li>
        ))}
      </ol>
      )}
      {listed && sorted.length > shown.length && (
        <Button variant="ghost" size="sm" className="mt-1" onClick={() => setAll(true)}>
          {t("Parts.more", { n: sorted.length - shown.length })}
        </Button>
      )}
    </section>
  );
}

function PartText({ item, partId }: { item: ItemRef; partId: string }) {
  const { t } = useTranslation("part");
  const part = useQuery({
    queryKey: ["part", item.source, item.item, partId],
    queryFn: async () =>
      unwrap(await api.GET("/api/items/{source}/{item}/parts/{partId}", { params: { path: { source: item.source, item: item.item, partId } } })),
  });
  if (part.isLoading)
    return (
      <div className="flex justify-center p-4 text-muted-foreground">
        <Loader2Icon className="size-5 animate-spin" />
      </div>
    );
  if (!part.data) return <div className="px-3 pb-3 text-sm text-destructive">{t("PartText.failed")}</div>;
  return (
    <div className="mx-3 mb-3 max-h-[60vh] overflow-y-auto rounded-md bg-muted/40 p-3">
      <div className="mb-2 flex items-center gap-2 text-xs text-muted-foreground">
        <BookOpenTextIcon className="size-3.5" /> {part.data.title ?? `#${part.data.position}`}
      </div>
      <p className="text-sm leading-relaxed whitespace-pre-line text-foreground/90">{part.data.content}</p>
    </div>
  );
}
