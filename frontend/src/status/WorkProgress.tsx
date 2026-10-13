import { useQuery } from "@tanstack/react-query";
import { BookOpenTextIcon, FileTextIcon, ImageIcon, LayersIcon, Loader2Icon, type LucideIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type WorkStatus } from "@/api/client";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { compact, duration, number } from "@/i18n/format";
import { workLabel } from "./labels";

const ICON: Record<string, LucideIcon> = {
  pictures: ImageIcon,
  texts: FileTextIcon,
  parts: BookOpenTextIcon,
  sets: LayersIcon,
};

/** How far the background work has gone — pictures, texts, chapters, sets; it runs for days. */
export function WorkProgress() {
  const status = useQuery({
    queryKey: ["status"],
    queryFn: async () => unwrap(await api.GET("/api/status")),
    refetchInterval: (q) => (q.state.data?.work.some((w) => w.running) ? 10000 : 30000),
  });
  const work = (status.data?.work ?? []).filter((w) => w.total > 0 || w.running);
  if (work.length === 0) return null;
  return (
    // Where the header has the room: on a narrower screen the menu and the buttons come first
    <div className="hidden shrink-0 items-center gap-3 2xl:flex">
      {work.map((w) => (
        <Work key={w.name} w={w} />
      ))}
    </div>
  );
}

function Work({ w }: { w: WorkStatus }) {
  const { t } = useTranslation("status");
  const Icon = ICON[w.name] ?? LayersIcon;
  const label = workLabel(w.name);
  const rest = w.total - w.done - (w.failed ?? 0);
  const left = w.perMinute && w.perMinute > 0 && rest > 0 ? duration((rest / w.perMinute) * 60) : undefined;
  // The first line, whole: how much is done, how much failed, whether it runs now
  const counts = { title: label.title, done: number(w.done), total: number(w.total), failed: number(w.failed ?? 0) };
  const state =
    w.total > 0
      ? w.failed
        ? t(w.running ? "WorkProgress.countsFailedRunning" : "WorkProgress.countsFailed", counts)
        : t(w.running ? "WorkProgress.countsRunning" : "WorkProgress.counts", counts)
      : t(w.running ? "WorkProgress.running" : "WorkProgress.idle", counts);
  return (
    <Tooltip>
      <TooltipTrigger render={<span className="inline-flex items-center gap-1.5 text-xs whitespace-nowrap text-muted-foreground tabular-nums" />}>
        {w.running ? <Loader2Icon className="size-3.5 animate-spin" /> : <Icon className="size-3.5" />}
        {w.total > 0 && `${compact(w.done)} / ${compact(w.total)}`}
      </TooltipTrigger>
      <TooltipContent className="block max-w-sm">
        <div>{state}</div>
        {label.about && <div className="mt-1">{label.about}</div>}
        {/* The time left comes from the speed: there is none without it */}
        {w.perMinute ? (
          <div className="mt-1">
            {left
              ? t("WorkProgress.speedLeft", { speed: number(Math.round(w.perMinute)), left })
              : t("WorkProgress.speed", { speed: number(Math.round(w.perMinute)) })}
          </div>
        ) : null}
      </TooltipContent>
    </Tooltip>
  );
}
