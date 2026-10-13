import { type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { BrainCircuitIcon, Loader2Icon, RefreshCwIcon, StarIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type ContentTypeInfo, type Grade, type GradeOnScale, type ModelCandidate, type ModelInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ALL_GRADES, gradeLabel } from "@/contenttype/types";
import { formatDate, number, percent, score } from "@/i18n/format";
import { ITEMS } from "@/item/lists";
import { cn } from "@/lib/utils";
import { FeatureBars } from "./FeatureBars";
import { ScorerComparisonDialog } from "./ScorerComparisonDialog";
import { useModel } from "./useModel";

/** The model's state as an entry of the menu shows it: training or not, how well it orders, whether it is stale. */
export function ModelLabel({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("model");
  const model = useModel(type.id);
  const m = model.data;
  const warn = m && (!m.textEncoderReady || !m.imageEncoderReady);
  return (
    <>
      {m?.training ? <Loader2Icon className="animate-spin" /> : <BrainCircuitIcon />}
      <span>
        {!m
          ? t("ModelLabel.model")
          : m.trained
            ? m.metrics
              ? t("ModelLabel.order", { percent: percent(m.metrics.concordance) })
              : t("ModelLabel.model")
            : t("ModelLabel.untrained")}
      </span>
      {m?.stale && !m.training && <span className="size-2 rounded-full bg-maybe" title={t("ModelLabel.stale")} />}
      {warn && <TriangleAlertIcon className="text-maybe" />}
    </>
  );
}

/** The details of the model of the type, opened from the menu. */
export function ModelDialog({ type, open, onOpenChange }: { type: ContentTypeInfo; open: boolean; onOpenChange: (open: boolean) => void }) {
  const model = useModel(type.id);
  const m = model.data;
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-4xl overflow-y-auto">{m && <ModelDetails type={type} m={m} />}</DialogContent>
    </Dialog>
  );
}

const GRADE_BORDER: Record<number, string> = {
  1: "border-grade-1/50",
  2: "border-grade-2/50",
  3: "border-grade-3/50",
  4: "border-grade-4/50",
  5: "border-grade-5/50",
};

const GRADE_DOT: Record<number, string> = {
  1: "bg-grade-1",
  2: "bg-grade-2",
  3: "bg-grade-3",
  4: "bg-grade-4",
  5: "bg-grade-5",
};

function Stat({ label, value, hint, className, aside }: { label: string; value: string; hint?: string; className?: string; aside?: ReactNode }) {
  return (
    <div className={cn("rounded-lg border bg-card p-3", className)} title={hint}>
      <div className="truncate text-xs text-muted-foreground">{label}</div>
      <div className="mt-1 flex items-center justify-between gap-2">
        <span className="text-xl font-semibold tabular-nums">{value}</span>
        {aside}
      </div>
    </div>
  );
}

const GRADE_TONE: Record<number, string> = {
  1: "bg-grade-1 text-white",
  2: "bg-grade-2 text-black",
  3: "bg-grade-3 text-black",
  4: "bg-grade-4 text-black",
  5: "bg-grade-5 text-white",
};

/** Where a grade stands on the user's scale, as the score badges of the works show it. */
function GradePlace({ grade, ladder }: { grade: Grade; ladder: GradeOnScale[] }) {
  const { t } = useTranslation("model");
  const place = ladder.find((l) => l.grade === grade);
  if (!place) return null;
  return (
    <span
      className={cn("inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-sm font-semibold whitespace-nowrap tabular-nums", GRADE_TONE[grade])}
      title={t("GradePlace.hint")}
    >
      <StarIcon className="size-3.5 fill-current" />
      {score(place.score)}
    </span>
  );
}

const se = (v?: number) => (v === undefined ? "" : ` ± ${Math.round(v * 100)}`);

/**
 * The grades on the user's scale: where the centre of each grade's works fell on 0..10. Grades
 * the model cannot tell apart stand at one point.
 */
function Ladder({ type, ladder }: { type: ContentTypeInfo; ladder: GradeOnScale[] }) {
  const { t } = useTranslation("model");
  // Grades at one point are labelled together
  const points = new Map<string, GradeOnScale[]>();
  ladder.forEach((l) => {
    const key = l.score.toFixed(2);
    points.set(key, [...(points.get(key) ?? []), l]);
  });
  return (
    <section>
      <h4 className="mb-1 text-sm font-semibold">{t("Ladder.title")}</h4>
      <p className="mb-3 text-xs text-muted-foreground">{t("Ladder.about")}</p>
      <div className="relative mx-3 mt-2 mb-14 h-1.5 rounded-full bg-muted">
        {[...points.values()].map((group, i) => {
          const at = Math.min(100, Math.max(0, group[0].score * 10));
          return (
            <div key={i} className="absolute top-1/2 -translate-x-1/2 -translate-y-1/2" style={{ left: `${at}%` }}>
              <div className="flex -space-x-1">
                {group.map((l) => (
                  <span key={l.grade} className={cn("size-3.5 rounded-full ring-2 ring-background", GRADE_DOT[l.grade])} />
                ))}
              </div>
              <div
                className={cn(
                  "absolute w-max text-xs leading-tight",
                  // A label at an end of the scale stands inside it, not beyond the dialog's edge
                  at < 15 ? "left-0 text-left" : at > 85 ? "right-0 text-right" : "left-1/2 -translate-x-1/2 text-center",
                  i % 2 === 0 ? "top-5" : "bottom-5",
                )}
              >
                <div className="font-semibold tabular-nums">{score(group[0].score)}</div>
                <div className="text-muted-foreground">{group.map((l) => `${l.grade} · ${gradeLabel(type, l.grade as Grade)}`).join(", ")}</div>
              </div>
            </div>
          );
        })}
      </div>
    </section>
  );
}

function Candidates({ candidates, chosen }: { candidates: ModelCandidate[]; chosen?: number }) {
  const { t } = useTranslation("model");
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-muted/60 text-xs text-muted-foreground">
          <tr>
            <th className="px-3 py-2 text-left font-medium">{t("Candidates.parameter")}</th>
            <th className="px-3 py-2 text-right font-medium">{t("Candidates.concordance")}</th>
            <th className="px-3 py-2 text-right font-medium">ρ</th>
            <th className="px-3 py-2 text-right font-medium">AUC</th>
          </tr>
        </thead>
        <tbody>
          {candidates.map((c) => (
            <tr key={c.parameter} className={cn("border-t", c.parameter === chosen && "bg-yes/10 font-medium")}>
              <td className="px-3 py-1.5">{number(c.parameter)}</td>
              <td className="px-3 py-1.5 text-right tabular-nums">
                {Math.round(c.metrics.concordance * 100)}
                {se(c.metrics.concordanceSe)}
              </td>
              <td className="px-3 py-1.5 text-right tabular-nums">{c.metrics.spearman?.toFixed(2) ?? "—"}</td>
              <td className="px-3 py-1.5 text-right tabular-nums">{c.metrics.auc?.toFixed(3) ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function ModelDetails({ type, m }: { type: ContentTypeInfo; m: ModelInfo }) {
  const { t } = useTranslation("model");
  const queryClient = useQueryClient();
  const trained = (data: ModelInfo) => {
    queryClient.setQueryData(["model", type.id], data);
    queryClient.invalidateQueries({ queryKey: [ITEMS, type.id] });
  };
  const train = useMutation({
    mutationFn: async () => unwrap(await api.POST("/api/types/{type}/model/train", { params: { path: { type: type.id } } })),
    onSuccess: trained,
  });
  const choose = useMutation({
    mutationFn: async (scorer: string) => unwrap(await api.PUT("/api/types/{type}/model/scorer", { params: { path: { type: type.id } }, body: { scorer } })),
    onSuccess: trained,
  });

  const busy = m.training || train.isPending || choose.isPending;
  const rated = m.ratingCounts.reduce((a, b) => a + b, 0);
  const scorerTitle = m.scorers.find((s) => s.id === m.scorer)?.title ?? m.scorer;

  return (
    <>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <BrainCircuitIcon className="size-5" /> {t("ModelDetails.title", { type: type.title.toLowerCase() })}
        </DialogTitle>
        <DialogDescription>{t("ModelDetails.description")}</DialogDescription>
      </DialogHeader>

      {!m.textEncoderReady && <Warning>{t("ModelDetails.noTextEncoder")}</Warning>}
      {!m.imageEncoderReady && <Warning>{t("ModelDetails.noImageEncoder")}</Warning>}
      {m.stale && !m.training && <Warning>{t("ModelDetails.stale")}</Warning>}

      {m.scorers.length > 0 && (
        <section className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h4 className="text-sm font-semibold">{t("ModelDetails.scorer")}</h4>
            <div className="flex flex-wrap gap-1">
              {m.scorers.map((s) => (
                <Button
                  key={s.id}
                  size="sm"
                  variant={s.id === m.scorer ? "default" : "outline"}
                  disabled={busy}
                  onClick={() => s.id !== m.scorer && choose.mutate(s.id)}
                >
                  {choose.isPending && choose.variables === s.id && <Loader2Icon className="animate-spin" />}
                  {s.title}
                </Button>
              ))}
            </div>
            {m.scorers.length > 1 && <ScorerComparisonDialog type={type} current={m.scorer} onChoose={(s) => choose.mutate(s)} busy={busy} />}
          </div>
          {choose.isError && (
            <div className="text-sm text-destructive">{t("ModelDetails.scorerFailed", { error: String(choose.error.message) })}</div>
          )}
        </section>
      )}

      <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
        <Stat label={t("ModelDetails.grades")} value={String(rated)} />
        {ALL_GRADES.map((g) => (
          <Stat
            key={g}
            label={`${g} · ${gradeLabel(type, g)}`}
            value={String(m.ratingCounts[g - 1] ?? 0)}
            hint={gradeLabel(type, g)}
            className={GRADE_BORDER[g]}
            aside={m.trained && <GradePlace grade={g} ladder={m.ladder} />}
          />
        ))}
      </div>

      {m.trained ? (
        <>
          {m.ladder.length > 1 && <Ladder type={type} ladder={m.ladder} />}

          <div className="grid grid-cols-1 gap-2 sm:grid-cols-3">
            <Stat
              label={t("ModelDetails.concordance")}
              value={m.metrics ? `${Math.round(m.metrics.concordance * 100)}${se(m.metrics.concordanceSe)}` : "—"}
              hint={t("ModelDetails.concordanceHint")}
            />
            <Stat label={t("ModelDetails.spearman")} value={m.metrics?.spearman?.toFixed(2) ?? "—"} hint={t("ModelDetails.spearmanHint")} />
            <Stat
              label={t("ModelDetails.auc")}
              value={m.metrics?.auc?.toFixed(3) ?? "—"}
              hint={t("ModelDetails.aucHint", { grade: gradeLabel(type, 2) })}
            />
          </div>
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span>{scorerTitle}</span>
            {m.parameter !== undefined && <span>· {t("ModelDetails.parameter", { value: number(m.parameter) })}</span>}
            <span>· {t("ModelDetails.features", { count: m.features })}</span>
            {m.metrics && <span>· {t("ModelDetails.folds", { count: m.metrics.folds })}</span>}
            {m.trainedAt && <span>· {t("ModelDetails.trainedAt", { date: formatDate(m.trainedAt) })}</span>}
          </div>

          {m.candidates.length > 0 && <Candidates candidates={m.candidates} chosen={m.parameter} />}

          <div className="grid gap-6 md:grid-cols-2">
            <section>
              <h4 className="mb-2 text-sm font-semibold text-yes">{t("ModelDetails.raise")}</h4>
              <FeatureBars items={m.topPositive} />
            </section>
            <section>
              <h4 className="mb-2 text-sm font-semibold text-no">{t("ModelDetails.lower")}</h4>
              <FeatureBars items={m.topNegative} />
            </section>
          </div>
        </>
      ) : (
        <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">{t("ModelDetails.untrained")}</div>
      )}

      <div className="flex items-center justify-end gap-2">
        {train.isError && <span className="text-sm text-destructive">{t("ModelDetails.trainFailed", { error: String(train.error.message) })}</span>}
        <Button onClick={() => train.mutate()} disabled={busy}>
          {busy ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
          {busy ? t("ModelDetails.training") : t("ModelDetails.retrain")}
        </Button>
      </div>
    </>
  );
}

function Warning({ children }: { children: ReactNode }) {
  return (
    <div className="flex gap-2 rounded-lg border border-maybe/50 bg-maybe/10 p-3 text-sm">
      <TriangleAlertIcon className="mt-0.5 size-4 shrink-0 text-maybe" />
      <div>{children}</div>
    </div>
  );
}
