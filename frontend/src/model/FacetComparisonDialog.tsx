import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, RotateCcwIcon, ScaleIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ContentTypeInfo, type FacetComparison, type MethodQuality } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { formatDate } from "@/i18n/format";
import { scorerTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";

const comparisonKey = (type: string, source: string, facet: string) => ["facet-comparison", type, source, facet];

/** How often a comparison under way is asked for again. */
const POLL_MS = 1500;

/**
 * The methods of a facet compared, in a window of their own: every method fitted on the facet from
 * scratch in the background, one after another, each judged on the same works held out by the same
 * folds — its line filled in as soon as it is fitted. Closing the window does not stop it; the facet
 * goes on with its method till another is made its own here.
 */
export function FacetComparisonDialog({
  type,
  source,
  facet,
  title,
  current,
  onChoose,
  busy,
}: {
  type: ContentTypeInfo;
  source: string;
  facet: string;
  /** The facet's name, as its card is titled. */
  title: string;
  current?: string;
  onChoose: (method: string) => void;
  busy: boolean;
}) {
  const { t } = useTranslation("model");
  const [open, setOpen] = useState(false);
  const queryClient = useQueryClient();
  const key = comparisonKey(type.id, source, facet);
  const path = { type: type.id, source, facet };
  const comparison = useQuery({
    queryKey: key,
    enabled: open,
    queryFn: async (): Promise<FacetComparison | null> => {
      const { data, response } = await api.GET("/api/types/{type}/models/facets/{source}/{facet}/comparison", { params: { path } });
      if (response.status === 404) return null;
      return data ?? null;
    },
    refetchInterval: (q) => (q.state.data && !q.state.data.finishedAt ? POLL_MS : false),
  });
  const start = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/types/{type}/models/facets/{source}/{facet}/comparison", { params: { path } })),
    onSuccess: (data) => queryClient.setQueryData(key, data),
  });
  const c = comparison.data;
  const running = c ? !c.finishedAt : false;

  const opened = (next: boolean) => {
    setOpen(next);
    // Opened with nothing measured yet: measured at once
    if (next && !queryClient.getQueryData(key)) start.mutate();
  };

  return (
    <Dialog open={open} onOpenChange={opened}>
      <DialogTrigger render={<Button size="sm" variant="ghost" title={t("FacetComparisonDialog.compareHint")} />}>
        <ScaleIcon /> {t("ScorerComparisonDialog.compare")}
      </DialogTrigger>
      <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ScaleIcon className="size-5" /> {t("FacetComparisonDialog.title", { facet: title })}
          </DialogTitle>
          <DialogDescription>{t("FacetComparisonDialog.description")}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          {c && (
            <span>
              {c.items > 0 ? t("FacetComparisonDialog.items", { count: c.items }) : t("ScorerComparisonDialog.reading")}
              {" · "}
              {t("ScorerComparisonDialog.started", { date: formatDate(c.startedAt) })}
              {c.finishedAt && ` · ${t("ScorerComparisonDialog.finished", { date: formatDate(c.finishedAt) })}`}
            </span>
          )}
          <Button size="sm" variant="outline" className="ml-auto" disabled={running || start.isPending} onClick={() => start.mutate()}>
            {running || start.isPending ? <Loader2Icon className="animate-spin" /> : <RotateCcwIcon />}
            {running ? t("ScorerComparisonDialog.computing") : t("ScorerComparisonDialog.recompute")}
          </Button>
        </div>
        {start.isError && <div className="text-sm text-destructive">{t("ScorerComparisonDialog.startFailed", { error: String(start.error.message) })}</div>}
        {c && <Table comparison={c} current={current} onChoose={onChoose} busy={busy} />}
      </DialogContent>
    </Dialog>
  );
}

function Table({ comparison, current, onChoose, busy }: { comparison: FacetComparison; current?: string; onChoose: (m: string) => void; busy: boolean }) {
  const { t } = useTranslation("model");
  // A loss: the least is the best
  const best = Math.min(...comparison.methods.map((m) => m.quality?.value ?? Infinity));
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-muted/60 text-xs text-muted-foreground">
          <tr>
            <th className="px-3 py-2 text-left font-medium">{t("ScorerComparisonDialog.method")}</th>
            <th className="px-3 py-2 text-left font-medium">{t("ModelsScreen.setting")}</th>
            <th className="px-3 py-2 text-right font-medium" title={t("ModelsScreen.quality.LOSS.hint")}>
              {t("ModelsScreen.quality.LOSS")}
            </th>
            <th className="px-3 py-2 text-right font-medium">{t("ScorerComparisonDialog.time")}</th>
            <th className="px-3 py-2" />
          </tr>
        </thead>
        <tbody>
          {comparison.methods.map((q) => (
            <Row key={q.method} q={q} current={q.method === current} best={q.quality?.value === best} onChoose={onChoose} busy={busy} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Row({ q, current, best, onChoose, busy }: { q: MethodQuality; current: boolean; best: boolean; onChoose: (m: string) => void; busy: boolean }) {
  const { t } = useTranslation("model");
  return (
    <tr className={cn("border-t", current && "bg-yes/10 font-medium")}>
      <td className="px-3 py-1.5">
        {scorerTitle(q.method)}
        {current && <span className="ml-1 text-xs text-muted-foreground">{t("ScorerComparisonDialog.main")}</span>}
      </td>
      {q.state === "DONE" && q.quality ? (
        <>
          <td className="px-3 py-1.5 tabular-nums text-muted-foreground">
            {Object.entries(q.settings)
              .map(([k, v]) => `${k} = ${Number.isFinite(Number(v)) ? String(Number(Number(v).toPrecision(4))) : v}`)
              .join(" · ")}
          </td>
          <td className={cn("px-3 py-1.5 text-right tabular-nums", best && "font-semibold text-yes")}>
            {q.quality.value.toFixed(4)}
            {q.quality.error !== undefined && <span className="text-muted-foreground"> ± {q.quality.error.toFixed(4)}</span>}
          </td>
        </>
      ) : (
        <td colSpan={2} className="px-3 py-1.5 text-right text-muted-foreground">
          {q.state === "WAITING" && t("ScorerComparisonDialog.waiting")}
          {q.state === "MEASURING" && (
            <span className="inline-flex items-center gap-1">
              <Loader2Icon className="size-3 animate-spin" /> {t("ScorerComparisonDialog.measuring")}
            </span>
          )}
          {q.state === "FAILED" && <span className="text-destructive">{t("ScorerComparisonDialog.failed", { error: q.error })}</span>}
          {q.state === "DONE" && t("FacetComparisonDialog.nothing")}
        </td>
      )}
      <td className="px-3 py-1.5 text-right tabular-nums text-muted-foreground">
        {q.millis != null ? t("ScorerComparisonDialog.seconds", { value: Math.round(q.millis / 100) / 10 }) : ""}
      </td>
      <td className="px-3 py-1.5 text-right">
        {!current && q.state === "DONE" && q.quality && (
          <Button size="xs" variant="outline" disabled={busy} onClick={() => onChoose(q.method)}>
            {t("ScorerComparisonDialog.makeMain")}
          </Button>
        )}
      </td>
    </tr>
  );
}
