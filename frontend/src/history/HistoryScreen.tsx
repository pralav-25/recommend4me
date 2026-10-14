import { useQuery } from "@tanstack/react-query";
import { HistoryIcon, Loader2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type Grade, type HistoryEntry } from "@/api/client";
import { gradeLabel, typeIcon, typeTitle } from "@/contenttype/types";
import { sourceShort, sourceTitle } from "@/i18n/label";
import { itemHref } from "@/item/itemHash";

/** How many of the latest grades and marks are shown. */
const SHOWN = 200;

/**
 * THE USER'S HISTORY: every grade and mark of interest as it stands, of every type, the newest first.
 * A grade given by mistake — its work gone from the list it was given in — is found here: the title
 * opens the work's window, where it is taken back.
 */
export function HistoryScreen() {
  const { t, i18n } = useTranslation("screen");
  const history = useQuery({
    queryKey: ["history"],
    queryFn: async () => unwrap(await api.GET("/api/history", { params: { query: { limit: SHOWN } } })),
  });
  const when = new Intl.DateTimeFormat(i18n.language, { dateStyle: "medium", timeStyle: "short" });
  return (
    <div className="mx-auto flex max-w-4xl flex-col gap-3 px-4 py-4">
      <h1 className="flex items-center gap-2 text-xl font-semibold">
        <HistoryIcon className="size-5" /> {t("History.title")}
      </h1>
      <p className="text-sm text-muted-foreground">{t("History.intro")}</p>
      {history.isLoading && <Loader2Icon className="size-5 animate-spin text-muted-foreground" />}
      {history.isError && <p className="text-sm text-destructive">{t("History.loadError", { error: history.error.message })}</p>}
      {history.data && history.data.length === 0 && <p className="text-sm text-muted-foreground">{t("History.empty")}</p>}
      {history.data && history.data.length > 0 && (
        <ul className="flex flex-col divide-y rounded-lg border">
          {history.data.map((e) => (
            <li key={`${e.kind}:${e.source}/${e.item}/${e.version ?? ""}`} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-3 py-2 text-sm">
              <span className="w-36 shrink-0 text-xs text-muted-foreground tabular-nums">{when.format(new Date(e.at))}</span>
              <What entry={e} />
              <a href={itemHref({ source: e.source, item: e.item })} className="min-w-0 flex-1 truncate underline-offset-2 hover:underline" title={e.title}>
                {e.title}
              </a>
              <TypeMark entry={e} />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** What was given: the grade with its words, or interest. */
function What({ entry: e }: { entry: HistoryEntry }) {
  const { t } = useTranslation("screen");
  if (e.kind === "GRADE" && e.grade != null) {
    return (
      <span className="w-40 shrink-0" title={e.version ? t("History.version", { version: e.version }) : undefined}>
        <span className="font-semibold tabular-nums">{e.grade}</span> {gradeLabel(e.type, e.grade as Grade)}
      </span>
    );
  }
  return <span className="w-40 shrink-0">{e.interesting ? `🤔 ${t("History.interesting")}` : `🥱 ${t("History.dull")}`}</span>;
}

/** The type and the site of the work. */
function TypeMark({ entry: e }: { entry: HistoryEntry }) {
  const Icon = typeIcon(e.type);
  return (
    <span className="flex shrink-0 items-center gap-1 text-xs text-muted-foreground" title={`${typeTitle(e.type)} · ${sourceTitle(e.source)}`}>
      <Icon className="size-3.5" /> {sourceShort(e.source)}
    </span>
  );
}
