import { type ReactNode } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { BrainCircuitIcon, Loader2Icon, RefreshCwIcon, StarIcon, TriangleAlertIcon } from "lucide-react";
import { api, unwrap, type ContentTypeInfo, type Grade, type GradeOnScale, type ModelCandidate, type ModelInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { ALL_GRADES, gradeLabel } from "@/contenttype/types";
import { formatDate, number, percent, score } from "@/i18n";
import { ITEMS } from "@/item/lists";
import { cn } from "@/lib/utils";
import { FeatureBars } from "./FeatureBars";
import { ScorerComparisonDialog } from "./ScorerComparisonDialog";
import { useModel } from "./useModel";

/** The model's state in the header, opening the details. */
export function ModelButton({ type }: { type: ContentTypeInfo }) {
  const model = useModel(type.id);
  const m = model.data;
  const warn = m && (!m.textEncoderReady || !m.imageEncoderReady);
  return (
    <Dialog>
      <DialogTrigger render={<Button variant="ghost" className="gap-2" title="Модель рекомендаций" />}>
        {m?.training ? <Loader2Icon className="animate-spin" /> : <BrainCircuitIcon />}
        <span className="hidden sm:inline">
          {!m ? "Модель" : m.trained ? (m.metrics ? `порядок ${percent(m.metrics.concordance)}` : "Модель") : "Не обучена"}
        </span>
        {m?.stale && !m.training && <span className="size-2 rounded-full bg-maybe" title="Оценки изменились после обучения" />}
        {warn && <TriangleAlertIcon className="text-maybe" />}
      </DialogTrigger>
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
  const place = ladder.find((l) => l.grade === grade);
  if (!place) return null;
  return (
    <span
      className={cn("inline-flex shrink-0 items-center gap-1 rounded-full px-2 py-0.5 text-sm font-semibold whitespace-nowrap tabular-nums", GRADE_TONE[grade])}
      title="Место оценки на вашей шкале: прогноз работы сравнивается с этим числом"
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
  // Grades at one point are labelled together
  const points = new Map<string, GradeOnScale[]>();
  ladder.forEach((l) => {
    const key = l.score.toFixed(2);
    points.set(key, [...(points.get(key) ?? []), l]);
  });
  return (
    <section>
      <h4 className="mb-1 text-sm font-semibold">Ваша шкала</h4>
      <p className="mb-3 text-xs text-muted-foreground">
        Где на шкале 0–10 оказались середины ваших оценок. Числа задаёт не кнопка, а то, насколько по-разному модель видит
        работы разных оценок; оценки, которые она не различает, стоят в одной точке.
      </p>
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
  return (
    <div className="overflow-hidden rounded-lg border">
      <table className="w-full text-sm">
        <thead className="bg-muted/60 text-xs text-muted-foreground">
          <tr>
            <th className="px-3 py-2 text-left font-medium">Параметр</th>
            <th className="px-3 py-2 text-right font-medium">Порядок пар, %</th>
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
          <BrainCircuitIcon className="size-5" /> Модель рекомендаций: {type.title.toLowerCase()}
        </DialogTitle>
        <DialogDescription>
          Учится порядку, а не числам: какая из двух оценённых работ с разными оценками нравится вам больше. Оценки при этом
          не стоят никаких баллов. Шкала строится потом: середина работ с самой низкой оценкой — 0, с самой высокой — 10,
          остальные оценки встают туда, куда их поставила модель. Тексты, обложки и картинки превращаются в векторы; к ним
          добавляются признаки, числа сайтов и ваши действия на сайтах. Параметр выбирается кросс-валидацией по работам по
          доле верно упорядоченных пар; из почти равных берётся самый осторожный.
        </DialogDescription>
      </DialogHeader>

      {!m.textEncoderReady && (
        <Warning>Модель текстов не найдена в каталоге models — тексты не учитываются.</Warning>
      )}
      {!m.imageEncoderReady && (
        <Warning>Модель картинок не найдена в каталоге models — обложки и картинки не учитываются.</Warning>
      )}
      {m.stale && !m.training && (
        <Warning>Оценки изменились после обучения: прогнозы отстают. Модель переобучится сама через несколько секунд, или нажмите «Обучить заново».</Warning>
      )}

      {m.scorers.length > 0 && (
        <section className="flex flex-col gap-2">
          <div className="flex flex-wrap items-center gap-2">
            <h4 className="text-sm font-semibold">Способ ранжирования</h4>
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
          {choose.isError && <div className="text-sm text-destructive">Не удалось сменить способ: {String(choose.error.message)}</div>}
        </section>
      )}

      <div className="grid grid-cols-3 gap-2 sm:grid-cols-6">
        <Stat label="Оценок" value={String(rated)} />
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
              label="Порядок пар, %"
              value={m.metrics ? `${Math.round(m.metrics.concordance * 100)}${se(m.metrics.concordanceSe)}` : "—"}
              hint="Доля пар работ с разными оценками, которые модель упорядочила верно (на кросс-валидации): 50 — наугад, 100 — идеально. По ней выбирается параметр: из значений, что хуже лучшего меньше чем на «±», берётся самое осторожное"
            />
            <Stat
              label="Ранговая корреляция, ρ"
              value={m.metrics?.spearman?.toFixed(2) ?? "—"}
              hint="Насколько порядок работ по модели совпадает с порядком ваших оценок по всем пяти градациям: 0 — случайно, 1 — полностью"
            />
            <Stat
              label="AUC понравилось vs нет"
              value={m.metrics?.auc?.toFixed(3) ?? "—"}
              hint={`Насколько хорошо понравившиеся (выше «${gradeLabel(type, 2)}») отделены от непонравившихся: 0.5 — наугад, 1 — идеально`}
            />
          </div>
          <div className="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
            <span>{scorerTitle}</span>
            {m.parameter !== undefined && <span>· параметр {number(m.parameter)}</span>}
            <span>· {m.features} признаков</span>
            {m.metrics && <span>· {m.metrics.folds} фолдов</span>}
            {m.trainedAt && <span>· обучена {formatDate(m.trainedAt)}</span>}
          </div>

          {m.candidates.length > 0 && <Candidates candidates={m.candidates} chosen={m.parameter} />}

          <div className="grid gap-6 md:grid-cols-2">
            <section>
              <h4 className="mb-2 text-sm font-semibold text-yes">Поднимают на шкале</h4>
              <FeatureBars items={m.topPositive} />
            </section>
            <section>
              <h4 className="mb-2 text-sm font-semibold text-no">Опускают на шкале</h4>
              <FeatureBars items={m.topNegative} />
            </section>
          </div>
        </>
      ) : (
        <div className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          Модель ещё не обучена: нужно хотя бы 3 оценки минимум двух разных видов. Кросс-валидация включается с 6 оценённых
          работ.
        </div>
      )}

      <div className="flex items-center justify-end gap-2">
        {train.isError && <span className="text-sm text-destructive">Не удалось: {String(train.error.message)}</span>}
        <Button onClick={() => train.mutate()} disabled={busy}>
          {busy ? <Loader2Icon className="animate-spin" /> : <RefreshCwIcon />}
          {busy ? "Обучается…" : "Обучить заново"}
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
