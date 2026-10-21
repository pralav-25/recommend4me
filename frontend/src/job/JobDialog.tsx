import { CheckCircle2Icon, DatabaseIcon, DownloadIcon, SquareIcon, TriangleAlertIcon } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import type { JobStatus, SourceInfo } from "@/api/client";
import { RefreshSelect } from "@/components/RefreshSelect";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Progress } from "@/components/ui/progress";
import { compact, formatDate, timeLeft } from "@/i18n/format";
import { sourceTitle } from "@/i18n/label";
import { catalogueLabel, jobMessage, modeLabel, phaseLabel } from "./labels";
import { catalogueUnfinished, isActive, useCancelJob, useJobsRefresh, useStartJob } from "./useJobs";

type Props = {
  source: SourceInfo | null;
  job?: JobStatus;
  onOpenChange: (open: boolean) => void;
  onNeedSettings: () => void;
};

/**
 * The jobs of one source: what each does, how far the current one got, how long the rest will
 * take — and the buttons that start, resume or stop them.
 */
export function JobDialog({ source, job, onOpenChange, onNeedSettings }: Props) {
  const { t } = useTranslation("job");
  const start = useStartJob();
  const cancel = useCancelJob();
  const [every, setEvery] = useJobsRefresh();
  const active = isActive(job);
  const whole = source?.modes.includes("SCRAPE_ALL");
  const updates = source?.modes.includes("UPDATES");
  const left = job && active ? timeLeft(job.phaseStartedAt, job.processed, job.total) : undefined;
  const catalogue = whole ? job?.catalogue : undefined;
  const resumeWhole = catalogueUnfinished(job) && !active;
  const message = job && jobMessage(job);
  // A loaded catalogue leaves the updates to do: they are the main button then
  const updatesFirst = updates && (!whole || catalogue?.complete === true);
  const wholeLabel = resumeWhole
    ? t("JobDialog.resumeCatalogue")
    : catalogue?.complete
      ? t("JobDialog.rescanCatalogue")
      : catalogue?.stage === "none"
        ? t("JobDialog.startCatalogue")
        : t("JobDialog.loadWholeCatalogue");

  return (
    <Dialog open={source !== null} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-xl">
        {source && (
          <>
            <DialogHeader>
              <DialogTitle className="flex items-center gap-2">
                <DatabaseIcon className="size-5" /> {t("JobDialog.title", { source: sourceTitle(source.id) })}
                {/* Beside the closing cross */}
                <RefreshSelect every={every} onChange={setEvery} className="mr-8 ml-auto" />
              </DialogTitle>
              <DialogDescription>
                <Trans
                  t={t}
                  i18nKey="JobDialog.description"
                  values={{ source: sourceTitle(source.id) }}
                  components={{ link: <a href={source.homepage} target="_blank" rel="noreferrer" className="underline underline-offset-2" /> }}
                />
              </DialogDescription>
            </DialogHeader>

            <ul className="flex list-disc flex-col gap-1 pl-5 text-sm">
              {updates && (
                <li>
                  <Trans t={t} i18nKey="JobDialog.updatesAbout" components={{ b: <span className="font-medium" /> }} />
                </li>
              )}
              {whole && (
                <li>
                  <Trans t={t} i18nKey="JobDialog.wholeAbout" components={{ b: <span className="font-medium" /> }} />
                </li>
              )}
              <li>{t("JobDialog.backgroundAbout")}</li>
            </ul>

            {job && (
              <div className="grid grid-cols-3 gap-2 text-sm">
                <Stat label={t("JobDialog.items")} value={compact(job.items)} />
                <Stat label={t("JobDialog.newItems")} value={compact(job.newItems)} />
                <Stat label={t("JobDialog.errors")} value={compact(job.errors)} />
              </div>
            )}

            {catalogue && (
              <div className="flex flex-col gap-1.5 text-sm">
                <span className={catalogue.complete ? "text-yes" : catalogue.stage === "none" ? "text-muted-foreground" : undefined}>
                  {catalogueLabel(catalogue)}
                </span>
                {!catalogue.complete && catalogue.total > 0 && <Progress value={(catalogue.done / catalogue.total) * 100} />}
              </div>
            )}

            {job && active && (
              <div className="flex flex-col gap-1.5">
                <div className="flex justify-between gap-2 text-sm">
                  <span>
                    {job.mode && <span className="text-muted-foreground">{modeLabel(job.mode)} · </span>}
                    {job.state === "CANCELLING" ? t("JobDialog.stopping") : phaseLabel(job.phase)}
                  </span>
                  {job.total > 0 && (
                    <span className="tabular-nums text-muted-foreground">
                      {job.processed} / {job.total}
                      {left && ` · ${t("JobDialog.left", { left })}`}
                    </span>
                  )}
                </div>
                <Progress value={job.total > 0 ? (job.processed / job.total) * 100 : null} />
              </div>
            )}

            {message && <p className="text-sm text-muted-foreground">{message}</p>}

            {job && !active && job.state === "DONE" && job.finishedAt && (
              <div className="flex items-center gap-2 text-sm text-yes">
                <CheckCircle2Icon className="size-4" />
                {t("JobDialog.done", {
                  what: job.mode ? modeLabel(job.mode) : t("JobDialog.loading"),
                  date: formatDate(job.finishedAt),
                  count: job.newItems,
                })}
              </div>
            )}
            {job && !active && job.state === "FAILED" && (
              <div className="flex items-center gap-2 text-sm text-destructive">
                <TriangleAlertIcon className="size-4" /> {t("JobDialog.failed", { date: formatDate(job.finishedAt) })}
              </div>
            )}

            {job?.loggedIn === false && (
              <div className="flex gap-2 rounded-lg border border-maybe/50 bg-maybe/10 p-3 text-sm">
                <TriangleAlertIcon className="mt-0.5 size-4 shrink-0 text-maybe" />
                <div>
                  <Trans
                    t={t}
                    i18nKey="JobDialog.notLoggedIn"
                    components={{ settings: <button className="underline underline-offset-2" onClick={onNeedSettings} /> }}
                  />
                </div>
              </div>
            )}

            <DialogFooter>
              {active ? (
                <Button variant="outline" onClick={() => cancel.mutate(source.id)} disabled={job?.state === "CANCELLING" || cancel.isPending}>
                  <SquareIcon /> {t("JobDialog.stop")}
                </Button>
              ) : (
                <>
                  <Button variant="outline" onClick={() => onOpenChange(false)}>
                    {t("JobDialog.close")}
                  </Button>
                  {updates && (
                    <Button
                      variant={updatesFirst ? "default" : "outline"}
                      onClick={() => start.mutate({ source: source.id, mode: "UPDATES" }, { onSuccess: () => onOpenChange(false) })}
                      disabled={start.isPending}
                    >
                      <DownloadIcon /> {t("JobDialog.loadUpdates")}
                    </Button>
                  )}
                  {whole && (
                    <Button
                      variant={updatesFirst ? "outline" : "default"}
                      onClick={() => start.mutate({ source: source.id, mode: "SCRAPE_ALL" }, { onSuccess: () => onOpenChange(false) })}
                      disabled={start.isPending}
                    >
                      <DatabaseIcon /> {wholeLabel}
                    </Button>
                  )}
                </>
              )}
            </DialogFooter>
          </>
        )}
      </DialogContent>
    </Dialog>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border bg-card p-2.5">
      <div className="text-xs text-muted-foreground">{label}</div>
      <div className="mt-0.5 font-semibold tabular-nums">{value}</div>
    </div>
  );
}
