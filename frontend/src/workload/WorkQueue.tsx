import { useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckIcon, ClockIcon, ListOrderedIcon, Loader2Icon, PauseIcon, XIcon } from "lucide-react";
import { useEffect } from "react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type QueuedWork } from "@/api/client";
import { RefreshSelect } from "@/components/RefreshSelect";
import { typeTitle } from "@/contenttype/types";
import { ago, duration, formatTime, number } from "@/i18n/format";
import { label, sourceTitle } from "@/i18n/label";
import { useRefresh } from "@/lib/useRefresh";
import { cn } from "@/lib/utils";

/** How often the queue is asked for again till the user chooses ([RefreshSelect]). */
const EVERY_MS = 2000;

/**
 * THE QUEUE OF THE COMPUTATIONS, as a timeline: the last works done with how long each took, the one
 * computing now with when it began and when it is expected to end, those waiting their turn and
 * those planned after the quiet that follows the changes that asked for them, with how long each is
 * expected to take — as long as the last run of its kind. Each with why: a grade changed, the user
 * answered on a tag, the pictures came… and how many times each.
 */
export function WorkQueue() {
  const { t } = useTranslation("workload");
  const queryClient = useQueryClient();
  const [every, setEvery] = useRefresh("workload.refresh", EVERY_MS);
  const queue = useQuery({
    queryKey: ["workload"],
    queryFn: async () => unwrap(await api.GET("/api/workload")),
    refetchInterval: every || false,
  });
  const works = queue.data ?? [];
  // A work ended: the models it may have fitted read anew, not after their own long pause
  const lastEnded = works.filter((w) => w.state === "FINISHED").at(-1)?.ended;
  useEffect(() => {
    if (!lastEnded) return;
    void queryClient.invalidateQueries({ queryKey: ["models"] });
    void queryClient.invalidateQueries({ queryKey: ["model"] });
  }, [lastEnded, queryClient]);
  return (
    <section className="flex flex-col gap-2 rounded-lg border bg-card p-4">
      <h2 className="flex items-center gap-2 font-semibold">
        <ListOrderedIcon className="size-4" /> {t("WorkQueue.title")}
        <RefreshSelect every={every} onChange={setEvery} className="ml-auto" />
      </h2>
      <p className="text-xs text-muted-foreground">{t("WorkQueue.hint")}</p>
      {queue.isError && <p className="text-sm text-destructive">{t("WorkQueue.loadError", { error: queue.error.message })}</p>}
      {queue.isSuccess && works.length === 0 && <p className="text-sm text-muted-foreground">{t("WorkQueue.empty")}</p>}
      {works.length > 0 && (
        <ul className="flex flex-col divide-y text-sm">
          {works.map((w, i) => (
            <WorkRow key={`${w.state}:${w.what}:${w.type}:${w.source}:${w.facet}:${w.item}:${w.since}:${i}`} w={w} />
          ))}
        </ul>
      )}
    </section>
  );
}

/** A work: its state, what it does and of what, why, and its times. */
function WorkRow({ w }: { w: QueuedWork }) {
  const { t } = useTranslation("workload");
  const of = [w.type && typeTitle(w.type), w.source && sourceTitle(w.source), w.facet && (w.labelKey ? label(w.labelKey) : w.facet), w.item && `#${w.item}`].filter(
    Boolean,
  );
  return (
    <li className={cn("flex flex-wrap items-baseline gap-x-3 gap-y-1 py-1.5", w.state === "FINISHED" && "opacity-70")}>
      <State w={w} />
      <span className="font-medium">{label(`workload:What.${w.what}`)}</span>
      {of.length > 0 && <span className="text-muted-foreground">{of.join(" · ")}</span>}
      {w.reasons.length > 0 && (
        <span className="text-muted-foreground">
          {t("WorkQueue.because")} {w.reasons.map((r) => `${label(`workload:Reason.${r.key}`)}${r.count > 1 ? ` ×${number(r.count)}` : ""}`).join(", ")}
        </span>
      )}
      <span className="ml-auto text-xs text-muted-foreground tabular-nums">
        <Times w={w} />
      </span>
    </li>
  );
}

/**
 * The times of a work. Done: when it ended and how long it took. Running: when it began and when
 * it is expected to end. Waiting: since when, and how long it will take. Planned: when it is due —
 * one past due waits for the work before it — and how long it will take.
 */
function Times({ w }: { w: QueuedWork }) {
  const { t } = useTranslation("workload");
  const since = new Date(w.since).getTime();
  // Left out comes as null at run time, whatever the type says
  const expectedMillis = w.expectedMillis ?? undefined;
  const expected = expectedMillis !== undefined ? t("WorkQueue.expected", { span: span(expectedMillis, t) }) : t("WorkQueue.noEstimate");
  switch (w.state) {
    case "FINISHED":
      return t("WorkQueue.finished", { time: formatTime(w.ended), span: span(new Date(w.ended!).getTime() - since, t) });
    case "RUNNING": {
      const began = t("WorkQueue.began", { time: formatTime(w.since), span: span(Date.now() - since, t) });
      if (expectedMillis === undefined) return `${began} · ${t("WorkQueue.noEstimate")}`;
      const left = since + expectedMillis - Date.now();
      return `${began} · ${left > 0 ? t("WorkQueue.endsIn", { span: span(left, t) }) : t("WorkQueue.longer", { span: span(expectedMillis, t) })}`;
    }
    case "WAITING":
      return `${t("WorkQueue.waiting", { when: ago(w.since) })} · ${expected}`;
    case "PLANNED": {
      const due = w.due && new Date(w.due).getTime() > Date.now() ? t("WorkQueue.due", { when: ago(w.due) }) : t("WorkQueue.overdue");
      return `${due} · ${expected}`;
    }
  }
}

/** A span to the second — "12 s", "3 min 05 s" — an hour or more as the coarse duration. */
function span(millis: number, t: (key: "Span.seconds" | "Span.minutesSeconds", options: Record<string, unknown>) => string): string {
  const seconds = Math.max(0, millis) / 1000;
  if (seconds < 60) return t("Span.seconds", { value: number(Number(seconds.toPrecision(seconds < 10 ? 2 : 3))) });
  if (seconds < 3600) return t("Span.minutesSeconds", { minutes: Math.floor(seconds / 60), seconds: String(Math.round(seconds % 60)).padStart(2, "0") });
  return duration(seconds);
}

/** The state of a work as a mark: done or failed, computing now, waiting its turn, planned. */
function State({ w }: { w: QueuedWork }) {
  const { t } = useTranslation("workload");
  const look = w.failed
    ? { icon: <XIcon className="size-3.5" />, text: t("WorkQueue.state.FAILED"), hint: t("WorkQueue.state.FAILED.hint"), className: "bg-destructive/15 text-destructive" }
    : {
        FINISHED: { icon: <CheckIcon className="size-3.5" />, text: t("WorkQueue.state.FINISHED"), hint: t("WorkQueue.state.FINISHED.hint"), className: "bg-muted text-muted-foreground" },
        RUNNING: { icon: <Loader2Icon className="size-3.5 animate-spin" />, text: t("WorkQueue.state.RUNNING"), hint: t("WorkQueue.state.RUNNING.hint"), className: "bg-yes/15 text-yes" },
        WAITING: { icon: <PauseIcon className="size-3.5" />, text: t("WorkQueue.state.WAITING"), hint: t("WorkQueue.state.WAITING.hint"), className: "bg-maybe/15 text-maybe" },
        PLANNED: { icon: <ClockIcon className="size-3.5" />, text: t("WorkQueue.state.PLANNED"), hint: t("WorkQueue.state.PLANNED.hint"), className: "bg-muted text-muted-foreground" },
      }[w.state];
  return (
    <span className={cn("inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-xs font-medium", look.className)} title={look.hint}>
      {look.icon}
      {look.text}
    </span>
  );
}
