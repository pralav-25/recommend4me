import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Loader2Icon, RotateCcwIcon, ScaleIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ContentTypeInfo, type ScorerComparison, type ScorerQuality } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { typeTitle } from "@/contenttype/types";
import { formatDate, number } from "@/i18n/format";
import { scorerTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";

const comparisonKey = (type: string) => ["scorer-comparison", type];

/** How often a comparison under way is asked for again. */
const POLL_MS = 1000;

/**
 * The scorers compared, in a window of their own: the comparison runs in the background, the
 * scorers one after another, and every scorer's line is filled in as soon as it is measured.
 * Closing the window does not stop it; opening it again shows it as it stands.
 */
export function ScorerComparisonDialog({
  type,
  current,
  onChoose,
  busy,
}: {
  type: ContentTypeInfo;
  current: string;
  onChoose: (scorer: string) => void;
  busy: boolean;
}) {
  const { t } = useTranslation("model");
  const [open, setOpen] = useState(false);
  const queryClient = useQueryClient();
  const comparison = useQuery({
    queryKey: comparisonKey(type.id),
    enabled: open,
    queryFn: async (): Promise<ScorerComparison | null> => {
      const { data, response } = await api.GET("/api/types/{type}/model/comparison", { params: { path: { type: type.id } } });
      if (response.status === 404) return null;
      return data ?? null;
    },
    refetchInterval: (q) => (q.state.data && !q.state.data.finishedAt ? POLL_MS : false),
  });
  const start = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/types/{type}/model/comparison", { params: { path: { type: type.id } } })),
    onSuccess: (data) => queryClient.setQueryData(comparisonKey(type.id), data),
  });
  const c = comparison.data;
  const running = c ? !c.finishedAt : false;

  const opened = (next: boolean) => {
    setOpen(next);
    // Opened with nothing measured yet: measured at once
    if (next && !queryClient.getQueryData(comparisonKey(type.id))) start.mutate();
  };

  return (
    <Dialog open={open} onOpenChange={opened}>
      <DialogTrigger render={<Button size="sm" variant="ghost" className="ml-auto" title={t("ScorerComparisonDialog.compareHint")} />}>
        <ScaleIcon /> {t("ScorerComparisonDialog.compare")}
      </DialogTrigger>
      <DialogContent className="max-h-[90vh] max-w-4xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle className="flex items-center gap-2">
            <ScaleIcon className="size-5" /> {t("ScorerComparisonDialog.title", { type: typeTitle(type.id).toLowerCase() })}
          </DialogTitle>
          <DialogDescription>{t("ScorerComparisonDialog.description")}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
          {c && (
            <span>
              {c.grades > 0
                ? t("ScorerComparisonDialog.grades", { count: c.grades })
                : c.finishedAt
                  ? t("ScorerComparisonDialog.tooFew")
                  : t("ScorerComparisonDialog.reading")}
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
        {start.isError && (
          <div className="text-sm text-destructive">{t("ScorerComparisonDialog.startFailed", { error: String(start.error.message) })}</div>
        )}
        {c && <Table comparison={c} current={current} onChoose={onChoose} busy={busy} />}
      </DialogContent>
    </Dialog>
  );
}

function Table({ comparison, current, onChoose, busy }: { comparison: ScorerComparison; current: string; onChoose: (s: string) => void; busy: boolean }) {
  const { t } = useTranslation("model");
  const best = Math.max(...comparison.scorers.map((s) => s.chosen?.metrics.concordance ?? -1));
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-muted/60 text-xs text-muted-foreground">
          <tr>
            <th className="px-3 py-2 text-left font-medium">{t("ScorerComparisonDialog.method")}</th>
            <th className="px-3 py-2 text-right font-medium">{t("ScorerComparisonDialog.parameter")}</th>
            <th className="px-3 py-2 text-right font-medium">{t("ScorerComparisonDialog.concordance")}</th>
            <th className="px-3 py-2 text-right font-medium">ρ</th>
            <th className="px-3 py-2 text-right font-medium">AUC</th>
            <th className="px-3 py-2 text-right font-medium">{t("ScorerComparisonDialog.time")}</th>
            <th className="px-3 py-2" />
          </tr>
        </thead>
        <tbody>
          {comparison.scorers.map((q) => (
            <Row key={q.scorer} q={q} current={q.scorer === current} best={q.chosen?.metrics.concordance === best} onChoose={onChoose} busy={busy} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Row({ q, current, best, onChoose, busy }: { q: ScorerQuality; current: boolean; best: boolean; onChoose: (s: string) => void; busy: boolean }) {
  const { t } = useTranslation("model");
  const m = q.chosen?.metrics;
  return (
    <tr className={cn("border-t", current && "bg-yes/10 font-medium")}>
      <td className="px-3 py-1.5">
        {scorerTitle(q.scorer)}
        {current && <span className="ml-1 text-xs text-muted-foreground">{t("ScorerComparisonDialog.main")}</span>}
      </td>
      {q.state === "DONE" && m ? (
        <>
          <td className="px-3 py-1.5 text-right tabular-nums">{q.chosen && number(q.chosen.parameter)}</td>
          <td className={cn("px-3 py-1.5 text-right tabular-nums", best && "font-semibold text-yes")}>
            {Math.round(m.concordance * 1000) / 10}
            {m.concordanceSe !== undefined && <span className="text-muted-foreground"> ± {Math.round(m.concordanceSe * 1000) / 10}</span>}
          </td>
          <td className="px-3 py-1.5 text-right tabular-nums">{m.spearman?.toFixed(2) ?? "—"}</td>
          <td className="px-3 py-1.5 text-right tabular-nums">{m.auc?.toFixed(3) ?? "—"}</td>
        </>
      ) : (
        <td colSpan={4} className="px-3 py-1.5 text-right text-muted-foreground">
          {q.state === "WAITING" && t("ScorerComparisonDialog.waiting")}
          {q.state === "MEASURING" && (
            <span className="inline-flex items-center gap-1">
              <Loader2Icon className="size-3 animate-spin" /> {t("ScorerComparisonDialog.measuring")}
            </span>
          )}
          {q.state === "FAILED" && <span className="text-destructive">{t("ScorerComparisonDialog.failed", { error: q.error })}</span>}
          {q.state === "DONE" && t("ScorerComparisonDialog.tooFewToCompare")}
        </td>
      )}
      <td className="px-3 py-1.5 text-right tabular-nums text-muted-foreground">
        {q.millis != null ? t("ScorerComparisonDialog.seconds", { value: Math.round(q.millis / 100) / 10 }) : ""}
      </td>
      <td className="px-3 py-1.5 text-right">
        {!current && q.state === "DONE" && m && (
          <Button size="xs" variant="outline" disabled={busy} onClick={() => onChoose(q.scorer)}>
            {t("ScorerComparisonDialog.makeMain")}
          </Button>
        )}
      </td>
    </tr>
  );
}
