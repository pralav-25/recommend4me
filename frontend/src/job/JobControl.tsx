import { useEffect, useRef, useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { ChevronDownIcon, DatabaseIcon, DownloadIcon, Loader2Icon, SquareIcon, TriangleAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, JobStatus, SourceInfo } from "@/api/client";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Progress } from "@/components/ui/progress";
import { toast } from "@/components/ui/toast";
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip";
import { formatDate, timeLeft } from "@/i18n/format";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { catalogueLabel, jobMessage, modeLabel, phaseLabel } from "./labels";
import { ITEMS } from "@/item/lists";
import { JobDialog } from "./JobDialog";
import { catalogueUnfinished, isActive, useCancelJob, useJobs, useStartJob } from "./useJobs";

type Props = { type: ContentTypeInfo; onNeedSettings: () => void };

/**
 * The sources of the type that scrape by themselves: the others (the book sites) are filled by
 * the browser extension only, and have nothing to start or stop here.
 */
function scraping(type: ContentTypeInfo): SourceInfo[] {
  return type.sources.filter((s) => s.modes.includes("SCRAPE_ALL") || s.modes.includes("UPDATES"));
}

/**
 * "Load updates" of the sources of the current content type and the loads of their whole
 * catalogues, with their progress; finished jobs refresh the list, the filters and the model.
 */
export function JobControl({ type, onNeedSettings }: Props) {
  const { t } = useTranslation("job");
  const queryClient = useQueryClient();
  const jobs = useJobs();
  const start = useStartJob();
  const cancel = useCancelJob();
  const [dialog, setDialog] = useState<string | null>(null);

  const sources = scraping(type);
  const jobOf = (source: string) => jobs.data?.find((j) => j.source === source);
  const ours = (jobs.data ?? []).filter((j) => sources.some((s) => s.id === j.source));

  // A job that has just ended: the list, the filters and the model are asked for anew, and a toast says how it went
  const states = useRef(new Map<string, JobStatus["state"]>());
  useEffect(() => {
    for (const job of jobs.data ?? []) {
      const was = states.current.get(job.source);
      states.current.set(job.source, job.state);
      if (!was || !isActive({ ...job, state: was }) || isActive(job)) continue;
      queryClient.invalidateQueries({ queryKey: [ITEMS] });
      queryClient.invalidateQueries({ queryKey: ["facets"] });
      queryClient.invalidateQueries({ queryKey: ["model"] });
      const name = sourceTitle(job.source);
      if (job.state === "DONE")
        toast.add({
          title: t(job.mode === "SCRAPE_ALL" ? "JobControl.catalogueLoaded" : "JobControl.updatesLoaded", { source: name }),
          description: job.errors
            ? t("JobControl.resultErrors", { newItems: job.newItems, errors: job.errors })
            : t("JobControl.result", { newItems: job.newItems }),
          type: "success",
        });
      if (job.state === "FAILED") toast.add({ title: t("JobControl.failed", { source: name }), description: jobMessage(job), type: "error" });
    }
  });

  if (sources.length === 0) return null;

  const opened = sources.find((s) => s.id === dialog) ?? null;
  const dialogElement = (
    <JobDialog
      source={opened}
      job={opened ? jobOf(opened.id) : undefined}
      onOpenChange={(open) => !open && setDialog(null)}
      onNeedSettings={() => {
        setDialog(null);
        onNeedSettings();
      }}
    />
  );

  const running = ours.filter(isActive);
  if (running.length > 0) {
    const s = running[0];
    const left = timeLeft(s.phaseStartedAt, s.processed, s.total);
    return (
      <div className="flex min-w-0 items-center gap-1 sm:gap-3">
        <Loader2Icon className="hidden size-4 shrink-0 animate-spin text-muted-foreground sm:block" />
        <button
          className="flex min-w-0 flex-1 flex-col gap-1 text-left sm:w-64 sm:flex-none"
          onClick={() => setDialog(s.source)}
          title={s.catalogue && s.mode === "SCRAPE_ALL" ? catalogueLabel(s.catalogue) : t("JobControl.details")}
        >
          <div className="flex justify-between gap-2 text-xs">
            <span className="truncate">
              <span className="hidden text-muted-foreground sm:inline">
                {sources.length > 1 && `${sourceShort(s.source)} · `}
                {s.mode === "SCRAPE_ALL" && `${t("JobControl.catalogue")} · `}
              </span>
              {s.state === "CANCELLING" ? t("JobControl.stopping") : phaseLabel(s.phase) || t("JobControl.loading")}
            </span>
            {s.total > 0 && (
              <span className="shrink-0 tabular-nums text-muted-foreground">
                {s.processed} / {s.total}
                {left && <span className="hidden sm:inline"> · ~{left}</span>}
              </span>
            )}
          </div>
          <Progress value={s.total > 0 ? (s.processed / s.total) * 100 : null} />
        </button>
        {running.length > 1 && (
          <Badge variant="outline" title={running.slice(1).map((j) => sourceTitle(j.source)).join(", ")}>
            +{running.length - 1}
          </Badge>
        )}
        <Button variant="ghost" size="icon-sm" onClick={() => cancel.mutate(s.source)} disabled={s.state === "CANCELLING"} title={t("JobControl.stop")}>
          <SquareIcon />
        </Button>
        {dialogElement}
      </div>
    );
  }

  const withUpdates = sources.filter((s) => s.modes.includes("UPDATES"));
  const updateAll = () => withUpdates.forEach((s) => start.mutate({ source: s.id, mode: "UPDATES" }));
  const last = ours
    .filter((j) => j.state === "DONE" && j.finishedAt)
    .sort((a, b) => (b.finishedAt ?? "").localeCompare(a.finishedAt ?? ""))[0];
  const notLoggedIn = ours.filter((j) => j.loggedIn === false);
  const resumable = ours.filter((j) => sources.some((s) => s.id === j.source && s.modes.includes("SCRAPE_ALL")) && catalogueUnfinished(j));

  return (
    <div className="flex items-center gap-2">
      <div className="flex">
        <Button
          onClick={() => (withUpdates.length > 0 ? updateAll() : setDialog(sources[0].id))}
          disabled={start.isPending}
          className="rounded-r-none"
          title={withUpdates.length > 0 ? t("JobControl.updatesOf", { sources: withUpdates.map((s) => sourceTitle(s.id)).join(", ") }) : undefined}
        >
          {withUpdates.length > 0 ? <DownloadIcon /> : <DatabaseIcon />}
          <span className="hidden sm:inline">{withUpdates.length > 0 ? t("JobControl.loadUpdates") : t("JobControl.loadCatalogue")}</span>
        </Button>
        <DropdownMenu>
          <DropdownMenuTrigger render={<Button className="rounded-l-none border-l border-primary-foreground/20 px-1.5" aria-label={t("JobControl.whatToLoad")} />}>
            <ChevronDownIcon />
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-auto min-w-64">
            {sources.map((s, i) => {
              const catalogue = s.modes.includes("SCRAPE_ALL") ? jobOf(s.id)?.catalogue : undefined;
              return (
                <DropdownMenuGroup key={s.id}>
                  {i > 0 && <DropdownMenuSeparator />}
                  <DropdownMenuLabel>
                    {sourceTitle(s.id)}
                    {catalogue && <div className="font-normal text-muted-foreground">{catalogueLabel(catalogue)}</div>}
                  </DropdownMenuLabel>
                  {s.modes.includes("UPDATES") && (
                    <DropdownMenuItem onClick={() => start.mutate({ source: s.id, mode: "UPDATES" })}>
                      <DownloadIcon /> {modeLabel("UPDATES")}
                    </DropdownMenuItem>
                  )}
                  <DropdownMenuItem onClick={() => setDialog(s.id)}>
                    <DatabaseIcon /> {s.modes.includes("SCRAPE_ALL") ? `${modeLabel("SCRAPE_ALL")}…` : t("JobControl.more")}
                  </DropdownMenuItem>
                </DropdownMenuGroup>
              );
            })}
          </DropdownMenuContent>
        </DropdownMenu>
      </div>
      {last && (
        <span className="hidden text-xs text-muted-foreground xl:inline" title={formatDate(last.finishedAt)}>
          {t("JobControl.newItems", { count: last.newItems })}
        </span>
      )}
      {notLoggedIn.length > 0 && (
        <Tooltip>
          <TooltipTrigger render={<Badge variant="outline" className="hidden cursor-pointer border-maybe/60 text-maybe sm:inline-flex" onClick={onNeedSettings} />}>
            <TriangleAlertIcon /> <span className="hidden xl:inline">{t("JobControl.notLoggedIn")}</span>
          </TooltipTrigger>
          <TooltipContent>{t("JobControl.notLoggedInHint", { sources: notLoggedIn.map((j) => sourceTitle(j.source)).join(", ") })}</TooltipContent>
        </Tooltip>
      )}
      {resumable.map((j) => (
        <Button
          key={j.source}
          variant="outline"
          onClick={() => setDialog(j.source)}
          title={
            j.catalogue
              ? t("JobControl.catalogueUnfinishedAt", { progress: catalogueLabel(j.catalogue) })
              : t("JobControl.catalogueUnfinished")
          }
          className="hidden sm:inline-flex"
        >
          <DatabaseIcon />
          <span className="hidden 2xl:inline">
            {sources.length > 1 ? t("JobControl.resumeSource", { source: sourceTitle(j.source) }) : t("JobControl.resumeCatalogue")}
          </span>
        </Button>
      ))}
      {dialogElement}
    </div>
  );
}
