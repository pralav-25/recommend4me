import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { GaugeIcon, Loader2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type QueryRanks, type SearchQualitySum } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";

/** The first works of a search counted: a screen of the list, what is seen without scrolling. */
const TOP = 10;

/**
 * "Search quality": every line the user judged works for, searched again with the reranker and
 * without, and where the works judged stand — the first fitting one, the fitting and the not
 * fitting among the first ones. Asked for when opened: each line is searched twice.
 */
export function SearchQualityDialog({ type }: { type: string }) {
  const { t } = useTranslation("search");
  const [open, setOpen] = useState(false);
  const quality = useQuery({
    queryKey: ["searchQuality", type],
    enabled: open,
    staleTime: 0,
    queryFn: async () => unwrap(await api.GET("/api/types/{type}/search/quality", { params: { path: { type }, query: { top: TOP } } })),
  });
  const q = quality.data;
  return (
    <>
      <Button variant="ghost" size="icon" title={t("SearchQualityDialog.open")} onClick={() => setOpen(true)}>
        <GaugeIcon />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>{t("SearchQualityDialog.title")}</DialogTitle>
            <DialogDescription>{t("SearchQualityDialog.description", { count: TOP })}</DialogDescription>
          </DialogHeader>
          {quality.isFetching && !q && (
            <div className="flex justify-center py-8 text-muted-foreground">
              <Loader2Icon className="size-6 animate-spin" />
            </div>
          )}
          {q && q.queries.length === 0 && (
            <div className="py-6 text-sm text-muted-foreground">{t("SearchQualityDialog.empty")}</div>
          )}
          {q && q.queries.length > 0 && (
            <div className="flex flex-col gap-4 text-sm">
              <div className="grid grid-cols-[auto_1fr_1fr] gap-x-4 gap-y-1">
                <span />
                <span className="font-semibold">{t("SearchQualityDialog.reranked")}</span>
                <span className="font-semibold">{t("SearchQualityDialog.plain")}</span>
                <Sum label={t("SearchQualityDialog.firstRelevant")} a={q.reranked} b={q.plain} of={(s) => s.meanReciprocalRank.toFixed(2)} />
                <Sum
                  label={t("SearchQualityDialog.relevantInTop", { count: TOP })}
                  a={q.reranked}
                  b={q.plain}
                  of={(s) => t("SearchQualityDialog.ofRelevant", { found: s.relevantInTop, all: s.relevant })}
                />
                <Sum label={t("SearchQualityDialog.irrelevantInTop", { count: TOP })} a={q.reranked} b={q.plain} of={(s) => String(s.irrelevantInTop)} />
                <Sum label={t("SearchQualityDialog.unjudgedInTop", { count: TOP })} a={q.reranked} b={q.plain} of={(s) => String(s.unjudgedInTop)} />
                <Sum
                  label={t("SearchQualityDialog.relevantFound")}
                  a={q.reranked}
                  b={q.plain}
                  of={(s) => t("SearchQualityDialog.ofRelevant", { found: s.relevantFound, all: s.relevant })}
                />
              </div>
              <table className="w-full text-xs">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-1 text-left font-normal">{t("SearchQualityDialog.query")}</th>
                    <th className="py-1 text-right font-normal">{t("SearchQualityDialog.judged")}</th>
                    <th className="py-1 text-right font-normal">{t("SearchQualityDialog.reranked")}</th>
                    <th className="py-1 text-right font-normal">{t("SearchQualityDialog.plain")}</th>
                  </tr>
                </thead>
                <tbody>
                  {q.queries.map((row) => (
                    <tr key={row.query} className="border-t">
                      <td className="py-1">{row.query}</td>
                      <td className="py-1 text-right tabular-nums">
                        {row.relevant}/{row.irrelevant}
                      </td>
                      <td className="py-1 text-right tabular-nums">
                        <Ranks r={row.reranked} />
                      </td>
                      <td className="py-1 text-right tabular-nums">
                        <Ranks r={row.plain} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </>
  );
}

function Sum({ label, a, b, of }: { label: string; a: SearchQualitySum; b: SearchQualitySum; of: (s: SearchQualitySum) => string }) {
  return (
    <>
      <span className="text-muted-foreground">{label}</span>
      <span className="tabular-nums">{of(a)}</span>
      <span className="tabular-nums">{of(b)}</span>
    </>
  );
}

/** "#3 · ✓4 ✗1": where the first fitting work stands, and the judged ones among the first. */
function Ranks({ r }: { r: QueryRanks }) {
  const { t } = useTranslation("search");
  return (
    <span title={t("Ranks.hint", { count: TOP, found: r.relevantFound, unjudged: r.unjudgedInTop })}>
      {r.firstRelevant ? t("Ranks.first", { rank: r.firstRelevant }) : t("Ranks.none")} · ✓{r.relevantInTop} ✗{r.irrelevantInTop}
    </span>
  );
}
