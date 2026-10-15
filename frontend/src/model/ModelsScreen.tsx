import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { BrainCircuitIcon, Loader2Icon, RefreshCwIcon, SearchCheckIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, unwrap, type ContentTypeInfo, type ModelState, type ModelTrial } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { typeTitle } from "@/contenttype/types";
import { duration, formatDate, number } from "@/i18n/format";
import { label, scorerTitle, sourceTitle } from "@/i18n/label";
import { usePluginNamespaces } from "@/i18n/usePluginNamespaces";
import { ITEMS } from "@/item/lists";
import { cn } from "@/lib/utils";
import { GradeModelDetails } from "./GradeModel";
import { ScorerComparisonDialog } from "./ScorerComparisonDialog";
import { useModel } from "./useModel";

const modelsKey = (type: string) => ["models", type];

/** How often the models are asked for again while one is being fitted, and otherwise. */
const RUNNING_MS = 2000;
const IDLE_MS = 30_000;

/**
 * THE MODELS OF A CONTENT TYPE, ONE CARD EACH: the model of the grades, the model of interest, and
 * the model of every facet the application works out of every source. Each tells how it was last
 * fitted — when, how long it took, with what quality, which settings were chosen among which tried
 * — and lets the user fit it again, or search its method and settings anew, for this model alone.
 */
export function ModelsScreen({ type }: { type: ContentTypeInfo }) {
  const { t } = useTranslation("model");
  const models = useQuery({
    queryKey: modelsKey(type.id),
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/models", { params: { path: { type: type.id } } })),
    refetchInterval: (q) => (q.state.data?.some((m) => m.running) ? RUNNING_MS : IDLE_MS),
  });
  const list = models.data ?? [];
  // The methods' and the sources' titles are their own words: drawn anew once they are read
  usePluginNamespaces([...new Set(list.flatMap((m) => [...(m.methods ?? []), ...(m.source ? [m.source] : [])]))]);
  const grade = list.find((m) => m.kind === "GRADE");
  const interest = list.find((m) => m.kind === "INTEREST");
  const facets = list.filter((m) => m.kind === "FACET");
  const sources = [...new Set(facets.map((m) => m.source!))];

  return (
    <main className="mx-auto flex max-w-6xl flex-col gap-6 px-4 py-6">
      <header className="flex flex-col gap-1">
        <h1 className="flex items-center gap-2 text-xl font-semibold">
          <BrainCircuitIcon className="size-5" /> {t("ModelsScreen.title", { type: typeTitle(type.id).toLowerCase() })}
        </h1>
        <p className="text-sm text-muted-foreground">{t("ModelsScreen.description")}</p>
      </header>
      {models.isLoading && <Loader2Icon className="size-5 animate-spin text-muted-foreground" />}
      {models.isError && <p className="text-sm text-destructive">{t("ModelsScreen.loadError", { error: models.error.message })}</p>}
      {grade && <GradeCard type={type} m={grade} />}
      {interest && <InterestCard type={type} m={interest} />}
      {sources.map((source) => (
        <section key={source} className="flex flex-col gap-2">
          <h2 className="text-base font-semibold">{t("ModelsScreen.facetsOf", { source: sourceTitle(source) })}</h2>
          <div className="grid gap-3 lg:grid-cols-2">
            {facets
              .filter((m) => m.source === source)
              .map((m) => (
                <FacetCard key={m.id} type={type} m={m} />
              ))}
          </div>
        </section>
      ))}
    </main>
  );
}

/** The models asked for again, and the lists the model's scores sort: after a model is fitted or its method chosen. */
function useModelsChanged(type: ContentTypeInfo) {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: modelsKey(type.id) }),
      queryClient.invalidateQueries({ queryKey: ["model", type.id] }),
      queryClient.invalidateQueries({ queryKey: [ITEMS, type.id] }),
    ]);
}

/** The model of the grades: its card, and the details the dialog of the model showed, unfolded on asking. */
function GradeCard({ type, m }: { type: ContentTypeInfo; m: ModelState }) {
  const { t } = useTranslation("model");
  const changed = useModelsChanged(type);
  const model = useModel(type.id);
  const train = async () => {
    unwrap(await api.POST("/api/types/{type}/model/train", { params: { path: { type: type.id } } }));
    await changed();
  };
  const choose = useMutation({
    mutationFn: async (scorer: string) => unwrap(await api.PUT("/api/types/{type}/model/scorer", { params: { path: { type: type.id } }, body: { scorer } })),
    onSuccess: () => changed(),
  });
  return (
    <ModelCard
      title={t("ModelsScreen.grade")}
      hint={t("ModelsScreen.gradeHint")}
      m={m}
      method={m.method ? scorerTitle(m.method) : undefined}
      examples={m.items !== undefined ? t("ModelsScreen.grades", { count: m.items }) : undefined}
      actions={
        <>
          {(m.methods ?? []).length > 1 && (
            <ScorerComparisonDialog type={type} current={m.method ?? ""} onChoose={(s) => choose.mutate(s)} busy={m.running || choose.isPending} />
          )}
          <AsyncButton size="sm" variant="outline" onClick={train} disabled={m.running} icon={<RefreshCwIcon />}>
            {t("ModelsScreen.retrain")}
          </AsyncButton>
        </>
      }
    >
      {model.data && (
        <details className="group">
          <summary className="cursor-pointer text-sm text-muted-foreground hover:text-foreground">{t("ModelsScreen.details")}</summary>
          <div className="mt-3 flex flex-col gap-4">
            <GradeModelDetails type={type} m={model.data} />
          </div>
        </details>
      )}
    </ModelCard>
  );
}

/** The model of interest: learnt from the marks of interest, its method its own, apart from the grades'. */
function InterestCard({ type, m }: { type: ContentTypeInfo; m: ModelState }) {
  const { t } = useTranslation("model");
  const changed = useModelsChanged(type);
  const train = async () => {
    unwrap(await api.POST("/api/types/{type}/model/train", { params: { path: { type: type.id } } }));
    await changed();
  };
  const choose = useMutation({
    mutationFn: async (scorer: string) => {
      ensureOk(await api.PUT("/api/types/{type}/model/interest-scorer", { params: { path: { type: type.id } }, body: { scorer } }));
    },
    onSuccess: () => changed(),
  });
  return (
    <ModelCard
      title={t("ModelsScreen.interest")}
      hint={t("ModelsScreen.interestHint")}
      m={m}
      method={m.method ? scorerTitle(m.method) : undefined}
      examples={
        m.items !== undefined ? t("ModelsScreen.marks", { count: m.items, interesting: m.positives ?? 0, dull: m.items - (m.positives ?? 0) }) : undefined
      }
      actions={
        <>
          {(m.methods ?? []).length > 1 && (
            <ScorerComparisonDialog type={type} interest current={m.method ?? ""} onChoose={(s) => choose.mutate(s)} busy={m.running || choose.isPending} />
          )}
          <AsyncButton size="sm" variant="outline" onClick={train} disabled={m.running} icon={<RefreshCwIcon />}>
            {t("ModelsScreen.retrain")}
          </AsyncButton>
        </>
      }
    />
  );
}

/** The model of a facet of a source: its suggester, fitted on its own; searched anew from scratch on asking. */
function FacetCard({ type, m }: { type: ContentTypeInfo; m: ModelState }) {
  const { t } = useTranslation("model");
  const queryClient = useQueryClient();
  const search = async () => {
    ensureOk(
      await api.POST("/api/types/{type}/models/facets/{source}/{facet}/search", {
        params: { path: { type: type.id, source: m.source!, facet: m.facet! } },
      }),
    );
    await queryClient.invalidateQueries({ queryKey: modelsKey(type.id) });
  };
  return (
    <ModelCard
      title={m.labelKey ? label(m.labelKey) : m.facet!}
      m={m}
      method={m.method ? scorerTitle(m.method) : undefined}
      examples={
        m.items !== undefined ? `${t("ModelsScreen.works", { count: m.items })}, ${t("ModelsScreen.values", { count: m.values ?? 0 })}` : undefined
      }
      note={m.fromScratch === false ? t("ModelsScreen.walkedOn") : undefined}
      actions={
        <AsyncButton size="sm" variant="outline" onClick={search} disabled={m.running} icon={<SearchCheckIcon />} title={t("ModelsScreen.searchHint")}>
          {t("ModelsScreen.search")}
        </AsyncButton>
      }
    />
  );
}

/**
 * A model's card: what it is, its method, how it was last fitted — when, how long, the quality its
 * held-out folds give with its error, the settings chosen, how much it learnt from — and every
 * setting the search tried, unfolded on asking.
 */
function ModelCard({
  title,
  hint,
  m,
  method,
  examples,
  note,
  actions,
  children,
}: {
  title: string;
  hint?: string;
  m: ModelState;
  method?: string;
  examples?: string;
  note?: string;
  actions: React.ReactNode;
  children?: React.ReactNode;
}) {
  const { t } = useTranslation("model");
  const fitted = m.fittedAt !== undefined;
  return (
    <section className="flex flex-col gap-2 rounded-lg border bg-card p-4">
      <div className="flex flex-wrap items-start gap-2">
        <div className="flex min-w-0 flex-1 flex-col">
          <h3 className="flex items-center gap-2 font-semibold">
            {title}
            {m.running && <Loader2Icon className="size-4 animate-spin text-muted-foreground" aria-label={t("ModelsScreen.running")} />}
          </h3>
          {hint && <p className="text-xs text-muted-foreground">{hint}</p>}
        </div>
        <div className="flex flex-wrap items-center gap-1">{actions}</div>
      </div>
      {fitted ? (
        <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1 text-sm">
          {m.quality && <Quality q={m.quality} />}
          {Object.keys(m.settings).length > 0 && <span className="tabular-nums">{settingsText(m.settings, t("ModelsScreen.parameter"))}</span>}
          {method && <span className="text-muted-foreground">{method}</span>}
          {examples && <span className="text-muted-foreground">{examples}</span>}
          <span className="text-muted-foreground">{t("ModelsScreen.fittedAt", { date: formatDate(m.fittedAt) })}</span>
          {m.millis !== undefined && <span className="text-muted-foreground">{t("ModelsScreen.took", { duration: took(m.millis, t) })}</span>}
          {note && <span className="text-muted-foreground">{note}</span>}
        </div>
      ) : (
        <p className="text-sm text-muted-foreground">{m.running ? t("ModelsScreen.fitting") : t("ModelsScreen.notFitted")}</p>
      )}
      {m.stale && !m.running && <p className="text-sm text-maybe">{t("ModelsScreen.stale")}</p>}
      {m.tried.length > 0 && <Tried m={m} />}
      {children}
    </section>
  );
}

/** How long a fitting took, to the second — a minute or more as minutes and seconds, a long one as the coarse duration. */
function took(millis: number, t: (key: "ModelsScreen.seconds" | "ModelsScreen.minutesSeconds", options: Record<string, unknown>) => string): string {
  const seconds = millis / 1000;
  if (seconds < 60) return t("ModelsScreen.seconds", { value: number(Number(seconds.toPrecision(seconds < 10 ? 2 : 3))) });
  if (seconds < 3600) return t("ModelsScreen.minutesSeconds", { minutes: Math.floor(seconds / 60), seconds: Math.round(seconds % 60) });
  return duration(seconds);
}

/** The settings by their names: "parameter 0.01", "r = 16 · λ = 0.1" — the scorer's one parameter by the interface's word. */
function settingsText(settings: Record<string, string>, parameter: string): string {
  return Object.entries(settings)
    .map(([k, v]) => `${k === "parameter" ? parameter : k} = ${shortNumber(v)}`)
    .join(" · ");
}

/** A setting's value as a number reads: 1.0E-4 as 0.0001, 0.30000000000000004 as 0.3. */
function shortNumber(v: string): string {
  const n = Number(v);
  return Number.isFinite(n) ? String(Number(n.toPrecision(4))) : v;
}

/** The quality the held-out folds give: the share of pairs in order (of the grades, of interest) as a percent, a facet's loss as it is. */
function Quality({ q }: { q: NonNullable<ModelState["quality"]> }) {
  const { t } = useTranslation("model");
  const words = QUALITY[q.measure];
  const text = q.measure === "LOSS" ? qualityNumber(q.value, q.error, 4) : qualityPercent(q.value, q.error);
  return (
    <span className="font-semibold tabular-nums" title={t(words.hint)}>
      {t(words.name)} {text}
    </span>
  );
}

/** The words of every measure of quality: its name and what it tells. */
const QUALITY = {
  CONCORDANCE: { name: "ModelsScreen.quality.CONCORDANCE", hint: "ModelsScreen.quality.CONCORDANCE.hint" },
  AUC: { name: "ModelsScreen.quality.AUC", hint: "ModelsScreen.quality.AUC.hint" },
  LOSS: { name: "ModelsScreen.quality.LOSS", hint: "ModelsScreen.quality.LOSS.hint" },
} as const;

const qualityPercent = (value: number, error?: number) => `${Math.round(value * 100)}${error !== undefined ? ` ± ${Math.round(error * 100)}` : ""}%`;
const qualityNumber = (value: number, error: number | undefined, digits: number) =>
  `${value.toFixed(digits)}${error !== undefined ? ` ± ${error.toFixed(digits)}` : ""}`;

/** Every setting the search tried, the chosen one marked: its quality, how long it took. */
function Tried({ m }: { m: ModelState }) {
  const { t } = useTranslation("model");
  const loss = m.quality?.measure === "LOSS";
  const chosen = (trial: ModelTrial) => Object.entries(m.settings).every(([k, v]) => trial.settings[k] === v);
  return (
    <details>
      <summary className="cursor-pointer text-sm text-muted-foreground hover:text-foreground">{t("ModelsScreen.tried", { count: m.tried.length })}</summary>
      <div className="mt-2 overflow-hidden rounded-lg border">
        <table className="w-full text-sm">
          <thead className="bg-muted/60 text-xs text-muted-foreground">
            <tr>
              <th className="px-3 py-1.5 text-left font-medium">{t("ModelsScreen.setting")}</th>
              <th className="px-3 py-1.5 text-right font-medium">{t(QUALITY[m.quality?.measure ?? "CONCORDANCE"].name)}</th>
              {m.tried.some((r) => r.millis !== undefined) && <th className="px-3 py-1.5 text-right font-medium">{t("ModelsScreen.time")}</th>}
            </tr>
          </thead>
          <tbody>
            {m.tried.map((r, i) => (
              <tr key={i} className={cn("border-t", chosen(r) && "bg-yes/10 font-medium")}>
                <td className="px-3 py-1 tabular-nums">{settingsText(r.settings, t("ModelsScreen.parameter"))}</td>
                <td className="px-3 py-1 text-right tabular-nums">{loss ? qualityNumber(r.value, r.error, 4) : qualityPercent(r.value, r.error)}</td>
                {r.millis !== undefined && <td className="px-3 py-1 text-right tabular-nums">{took(r.millis, t)}</td>}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </details>
  );
}
