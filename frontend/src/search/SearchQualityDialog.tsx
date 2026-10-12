import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { GaugeIcon, Loader2Icon } from "lucide-react";
import { api, unwrap, type QueryRanks, type SearchQualitySum } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";

/** The first works of a search counted: a screen of the list, what is seen without scrolling. */
const TOP = 10;

/**
 * "Качество поиска": every line the user judged works for, searched again with the reranker and
 * without, and where the works judged stand — the first fitting one, the fitting and the not
 * fitting among the first ones. Asked for when opened: each line is searched twice.
 */
export function SearchQualityDialog({ type }: { type: string }) {
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
      <Button variant="ghost" size="icon" title="Качество поиска по вашим отметкам" onClick={() => setOpen(true)}>
        <GaugeIcon />
      </Button>
      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent className="max-h-[85vh] max-w-3xl overflow-y-auto">
          <DialogHeader>
            <DialogTitle>Качество поиска</DialogTitle>
            <DialogDescription>
              По вашим отметкам «подходит / не подходит» у найденных работ: каждый отмеченный запрос ищется заново — с реранкером и без. Считаются
              первые {TOP} найденных; неотмеченные среди них не считаются ни за, ни против.
            </DialogDescription>
          </DialogHeader>
          {quality.isFetching && !q && (
            <div className="flex justify-center py-8 text-muted-foreground">
              <Loader2Icon className="size-6 animate-spin" />
            </div>
          )}
          {q && q.queries.length === 0 && (
            <div className="py-6 text-sm text-muted-foreground">Отметок пока нет: найдите что-нибудь и отметьте у найденных работ, подходят ли они.</div>
          )}
          {q && q.queries.length > 0 && (
            <div className="flex flex-col gap-4 text-sm">
              <div className="grid grid-cols-[auto_1fr_1fr] gap-x-4 gap-y-1">
                <span />
                <span className="font-semibold">С реранкером</span>
                <span className="font-semibold">Без</span>
                <Sum label="Первая подходящая (1 / место, среднее)" a={q.reranked} b={q.plain} of={(s) => s.meanReciprocalRank.toFixed(2)} />
                <Sum label={`Подходящих в первых ${TOP}`} a={q.reranked} b={q.plain} of={(s) => `${s.relevantInTop} из ${s.relevant}`} />
                <Sum label={`Неподходящих в первых ${TOP}`} a={q.reranked} b={q.plain} of={(s) => String(s.irrelevantInTop)} />
                <Sum label={`Неотмеченных в первых ${TOP}`} a={q.reranked} b={q.plain} of={(s) => String(s.unjudgedInTop)} />
                <Sum label="Подходящих найдено вообще" a={q.reranked} b={q.plain} of={(s) => `${s.relevantFound} из ${s.relevant}`} />
              </div>
              <table className="w-full text-xs">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-1 text-left font-normal">Запрос</th>
                    <th className="py-1 text-right font-normal">Отмечено ✓/✗</th>
                    <th className="py-1 text-right font-normal">С реранкером</th>
                    <th className="py-1 text-right font-normal">Без</th>
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

/** "первая на 3-м · ✓4 ✗1": where the first fitting work stands, and the judged ones among the first. */
function Ranks({ r }: { r: QueryRanks }) {
  return (
    <span title={`подходящих найдено: ${r.relevantFound}; неотмеченных в первых ${TOP}: ${r.unjudgedInTop}`}>
      {r.firstRelevant ? `${r.firstRelevant}-я` : "нет"} · ✓{r.relevantInTop} ✗{r.irrelevantInTop}
    </span>
  );
}
