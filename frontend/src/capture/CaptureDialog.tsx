import { useState, type ReactNode } from "react";
import { useQuery } from "@tanstack/react-query";
import { CheckIcon, CopyIcon, ExternalLinkIcon, Loader2Icon, RadarIcon } from "lucide-react";
import { Trans, useTranslation } from "react-i18next";
import { api, unwrap, type ItemRef } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { findSource, useTypes } from "@/contenttype/types";
import { ago, formatDate } from "@/i18n/format";
import { sourceTitle } from "@/i18n/label";
import { ItemRefDialog } from "@/item/ItemRefDialog";

const DEFAULT_ADDRESS = "http://127.0.0.1:8095";

/**
 * Browser tracking: how to install the Firefox extension that sends the pages of the tracked
 * sites the user opens, and the pages it sent last.
 */
export function CaptureDialog({ open, onOpenChange: setOpen }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [item, setItem] = useState<ItemRef | null>(null);
  return (
    <>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
          {open && (
            <CaptureDetails
              onOpenItem={(ref) => {
                setOpen(false);
                setItem(ref);
              }}
            />
          )}
        </DialogContent>
      </Dialog>
      <ItemRefDialog item={item} onClose={() => setItem(null)} />
    </>
  );
}

function CaptureDetails({ onOpenItem }: { onOpenItem: (ref: ItemRef) => void }) {
  const { t } = useTranslation("capture");
  const types = useTypes();
  const patterns = useQuery({
    queryKey: ["capture", "patterns"],
    queryFn: async () => unwrap(await api.GET("/api/capture/patterns")),
  });
  const extension = useQuery({
    queryKey: ["capture", "extension"],
    queryFn: async () => unwrap(await api.GET("/api/capture/extension")),
  });
  const recent = useQuery({
    queryKey: ["capture", "recent"],
    queryFn: async () => unwrap(await api.GET("/api/capture/recent")),
    // While the dialog is open the pages come in as the user browses
    refetchInterval: 3000,
  });
  const homepageOf = (source: string) => findSource(types.data, source)?.source.homepage;
  const tracked = (patterns.data ?? []).filter((p) => p.patterns.length > 0);

  return (
    <>
      <DialogHeader>
        <DialogTitle className="flex items-center gap-2">
          <RadarIcon className="size-5" /> {t("CaptureDetails.title")}
        </DialogTitle>
        <DialogDescription>{t("CaptureDetails.description")}</DialogDescription>
      </DialogHeader>

      <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm">
        <li>
          <Trans t={t} i18nKey="CaptureDetails.firefoxStep1" components={{ code: <Code /> }} />
        </li>
        <li>
          {t("CaptureDetails.firefoxStep2")}
          <ManifestPath manifest={extension.data?.manifest} loading={extension.isLoading} />
        </li>
        <li>{t("CaptureDetails.firefoxStep3")}</li>
      </ol>

      <SafariSteps />

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="font-semibold">{t("CaptureDetails.shownHeading")}</h4>
        <p className="text-muted-foreground">{t("CaptureDetails.shown")}</p>
      </section>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="font-semibold">{t("CaptureDetails.sentHeading")}</h4>
        <p className="text-muted-foreground">
          <Trans t={t} i18nKey="CaptureDetails.sent" values={{ address: DEFAULT_ADDRESS }} components={{ code: <Code /> }} />
        </p>
        {patterns.isLoading ? (
          <Loader2Icon className="size-4 animate-spin text-muted-foreground" />
        ) : tracked.length === 0 ? (
          <p className="text-muted-foreground">{t("CaptureDetails.noSources")}</p>
        ) : (
          <ul className="flex flex-col gap-1">
            {tracked.map((p) => (
              <li key={p.source} className="flex flex-wrap items-baseline gap-x-2">
                {homepageOf(p.source) ? (
                  <a href={homepageOf(p.source)} target="_blank" rel="noreferrer" className="font-medium underline-offset-2 hover:underline">
                    {sourceTitle(p.source)}
                  </a>
                ) : (
                  <span className="font-medium">{sourceTitle(p.source)}</span>
                )}
                <span className="truncate font-mono text-xs text-muted-foreground" title={p.patterns.join("\n")}>
                  {p.patterns.length === 1 ? p.patterns[0] : t("CaptureDetails.patterns", { count: p.patterns.length })}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="flex flex-col gap-2 text-sm">
        <h4 className="flex items-center gap-2 font-semibold">
          {t("CaptureDetails.recentHeading")}
          {recent.isFetching && <Loader2Icon className="size-3.5 animate-spin text-muted-foreground" />}
        </h4>
        {recent.data && recent.data.length === 0 && <p className="text-muted-foreground">{t("CaptureDetails.recentNone")}</p>}
        {recent.isError && <p className="text-destructive">{t("CaptureDetails.recentFailed")}</p>}
        <ul className="flex flex-col divide-y rounded-lg border">
          {(recent.data ?? []).map((p) => (
            <li key={`${p.url}-${p.capturedAt}`} className="flex items-center gap-2 px-3 py-1.5">
              <span className="w-24 shrink-0 text-xs text-muted-foreground" title={formatDate(p.capturedAt)}>
                {ago(p.capturedAt)}
              </span>
              <span className="shrink-0 text-xs text-muted-foreground">{sourceTitle(p.source)}</span>
              {p.item ? (
                <button
                  className="min-w-0 flex-1 truncate text-left underline-offset-2 hover:underline"
                  onClick={() => onOpenItem({ source: p.source, item: p.item! })}
                  title={t("CaptureDetails.openWork")}
                >
                  {p.title ?? p.item}
                </button>
              ) : (
                <span className="min-w-0 flex-1 truncate text-muted-foreground" title={p.url}>
                  {p.url}
                </span>
              )}
              <a href={p.url} target="_blank" rel="noreferrer" className="shrink-0 text-muted-foreground hover:text-foreground" title={t("CaptureDetails.sitePage")}>
                <ExternalLinkIcon className="size-3.5" />
              </a>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}

/** The Userscripts app in the App Store: an extension of Safari that runs the scripts it is given. */
const USERSCRIPTS = "https://apps.apple.com/app/userscripts/id1463298887";

/**
 * The same on the iPad, where Safari takes no extension but from the App Store: the Userscripts
 * app runs the application's userscript, made of the extension's own scripts. The script calls
 * the application at the address it was taken from — so it is taken on the iPad, at the address
 * the iPad reaches the application at: over https, which `tailscale serve` gives.
 */
function SafariSteps() {
  const { t } = useTranslation("capture");
  const origin = window.location.origin;
  const script = `${origin}/userscript/recommend4me.user.js`;
  const secure = window.location.protocol === "https:";
  return (
    <section className="flex flex-col gap-2 text-sm">
      <h4 className="font-semibold">{t("SafariSteps.heading")}</h4>
      <p className="text-muted-foreground">{t("SafariSteps.intro")}</p>
      <ol className="flex list-decimal flex-col gap-2 pl-5">
        <li>
          <Trans t={t} i18nKey="SafariSteps.step1" components={{ code: <Code /> }} />
        </li>
        <li>
          <Trans
            t={t}
            i18nKey="SafariSteps.step2"
            components={{ link: <a href={USERSCRIPTS} target="_blank" rel="noreferrer" className="underline underline-offset-2" /> }}
          />
        </li>
        <li>
          {t("SafariSteps.step3")}
          <div className="mt-1 flex flex-col gap-1">
            <a href={script} className="font-mono text-xs break-all underline underline-offset-2">
              {script}
            </a>
            {!secure && <span className="text-xs text-maybe">{t("SafariSteps.notSecure", { origin })}</span>}
          </div>
        </li>
      </ol>
    </section>
  );
}

/** The path of the extension's manifest.json on this machine, with a button to copy it. */
function ManifestPath({ manifest, loading }: { manifest?: string; loading: boolean }) {
  const { t } = useTranslation("capture");
  const [copied, setCopied] = useState(false);
  if (loading) return <Loader2Icon className="mt-1 size-4 animate-spin text-muted-foreground" />;
  if (!manifest) {
    return (
      <p className="mt-1 text-destructive">
        <Trans t={t} i18nKey="ManifestPath.unknown" components={{ code: <Code /> }} />
      </p>
    );
  }
  const copy = async () => {
    await navigator.clipboard.writeText(manifest);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  };
  return (
    <div className="mt-1 flex items-center gap-2">
      <Code>{manifest}</Code>
      <Button variant="outline" size="sm" onClick={copy} title={t("ManifestPath.copyPath")}>
        {copied ? <CheckIcon /> : <CopyIcon />}
        {copied ? t("ManifestPath.copied") : t("ManifestPath.copy")}
      </Button>
    </div>
  );
}

/** Code in a sentence; without [children] when a translation's <code> tag fills it in. */
function Code({ children }: { children?: ReactNode }) {
  return <code className="rounded bg-muted px-1 py-0.5 font-mono text-xs break-words">{children}</code>;
}
