import { useQuery } from "@tanstack/react-query";
import { ClockIcon, ListOrderedIcon, Loader2Icon, PauseIcon, PlayIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type QueuedWork } from "@/api/client";
import { typeTitle } from "@/contenttype/types";
import { ago, number } from "@/i18n/format";
import { label, sourceTitle } from "@/i18n/label";
import { cn } from "@/lib/utils";

/** How often the queue is asked for again while anything is in it, and when it is empty. */
const BUSY_MS = 2000;
const IDLE_MS = 10_000;

/**
 * THE QUEUE OF THE BACKGROUND WORKS: the heavy work computing now — one at a time, each holds a
 * catalogue — the works running beside it, those waiting their turn, and those planned after the
 * quiet that follows the changes that asked for them. Each with why: a grade changed, the user
 * answered on a tag, the pictures came… and how many times each.
 */
export function WorkQueue() {
  const { t } = useTranslation("workload");
  const queue = useQuery({
    queryKey: ["workload"],
    queryFn: async () => unwrap(await api.GET("/api/workload")),
    refetchInterval: (q) => ((q.state.data?.length ?? 0) > 0 ? BUSY_MS : IDLE_MS),
  });
  const works = queue.data ?? [];
  return (
    <section className="flex flex-col gap-2 rounded-lg border bg-card p-4">
      <h2 className="flex items-center gap-2 font-semibold">
        <ListOrderedIcon className="size-4" /> {t("WorkQueue.title")}
      </h2>
      <p className="text-xs text-muted-foreground">{t("WorkQueue.hint")}</p>
      {queue.isError && <p className="text-sm text-destructive">{t("WorkQueue.loadError", { error: queue.error.message })}</p>}
      {queue.isSuccess && works.length === 0 && <p className="text-sm text-muted-foreground">{t("WorkQueue.empty")}</p>}
      {works.length > 0 && (
        <ul className="flex flex-col divide-y text-sm">
          {works.map((w, i) => (
            <WorkRow key={`${w.state}:${w.what}:${w.type}:${w.source}:${w.facet}:${w.item}:${i}`} w={w} />
          ))}
        </ul>
      )}
    </section>
  );
}

/** A work: its state, what it does and of what, why, and since or till when. */
function WorkRow({ w }: { w: QueuedWork }) {
  const { t } = useTranslation("workload");
  const of = [w.type && typeTitle(w.type), w.source && sourceTitle(w.source), w.facet && (w.labelKey ? label(w.labelKey) : w.facet), w.item && `#${w.item}`].filter(
    Boolean,
  );
  return (
    <li className="flex flex-wrap items-baseline gap-x-3 gap-y-1 py-1.5">
      <State w={w} />
      <span className="font-medium">{label(`workload:What.${w.what}`)}</span>
      {of.length > 0 && <span className="text-muted-foreground">{of.join(" · ")}</span>}
      {w.reasons.length > 0 && (
        <span className="text-muted-foreground">
          {t("WorkQueue.because")} {w.reasons.map((r) => `${label(`workload:Reason.${r.key}`)}${r.count > 1 ? ` ×${number(r.count)}` : ""}`).join(", ")}
        </span>
      )}
      <span className="ml-auto text-xs text-muted-foreground tabular-nums">{when(w, t)}</span>
    </li>
  );
}

/**
 * When: a work running or beside since it began; a work waiting since it was asked; a planned one
 * when it is due — one past due waits for the work before it to end.
 */
function when(w: QueuedWork, t: (key: "WorkQueue.due" | "WorkQueue.overdue" | "WorkQueue.since" | "WorkQueue.sincePlanned", options?: Record<string, unknown>) => string): string {
  if (w.state === "RUNNING" || w.state === "BESIDE") return t("WorkQueue.since", { when: ago(w.since) });
  if (w.state === "WAITING") return t("WorkQueue.sincePlanned", { when: ago(w.since) });
  if (w.due && new Date(w.due).getTime() > Date.now()) return t("WorkQueue.due", { when: ago(w.due) });
  return t("WorkQueue.overdue");
}

/** The state of a work as a mark: computing now, beside the queue, waiting its turn, planned. */
function State({ w }: { w: QueuedWork }) {
  const { t } = useTranslation("workload");
  const look = {
    RUNNING: { icon: <Loader2Icon className="size-3.5 animate-spin" />, text: t("WorkQueue.state.RUNNING"), className: "bg-yes/15 text-yes" },
    BESIDE: { icon: <PlayIcon className="size-3.5" />, text: t("WorkQueue.state.BESIDE"), className: "bg-muted text-foreground" },
    WAITING: { icon: <PauseIcon className="size-3.5" />, text: t("WorkQueue.state.WAITING"), className: "bg-maybe/15 text-maybe" },
    PLANNED: { icon: <ClockIcon className="size-3.5" />, text: t("WorkQueue.state.PLANNED"), className: "bg-muted text-muted-foreground" },
  }[w.state];
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium", look.className)} title={t(`WorkQueue.state.${w.state}.hint`)}>
      {look.icon}
      {look.text}
    </span>
  );
}
