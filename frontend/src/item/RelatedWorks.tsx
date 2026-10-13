import { ChevronRightIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, Grade, RelatedWorks as Related } from "@/api/client";
import { gradeLabel } from "@/contenttype/types";
import { cn } from "@/lib/utils";
import { itemHref } from "./itemHash";
import { GRADE_TEXT, shownScore } from "./pieces";

/**
 * The other works of the series the work is a part of and of the people who made it, each with
 * the user's grade — how the volumes before went tells most of the one after — or, ungraded, the
 * model's prediction: folded, their heading and count alone, opened on demand. A work opens over
 * this one.
 */
export function RelatedWorks({ type, related }: { type: ContentTypeInfo; related: Related[] }) {
  const { t } = useTranslation("item");
  if (related.length === 0) return null;
  return (
    <div className="flex flex-col gap-2">
      {related.map((r) => (
        <details key={`${r.facet}=${r.value}`} className="group flex flex-col gap-1">
          <summary className="flex cursor-pointer list-none items-center gap-1 text-sm font-semibold select-none [&::-webkit-details-marker]:hidden">
            <ChevronRightIcon className="size-3.5 shrink-0 text-muted-foreground transition-transform group-open:rotate-90" />
            {r.label}: {r.name} <span className="font-normal text-muted-foreground tabular-nums">{r.items.length}</span>
          </summary>
          <ul className="mt-1 flex max-h-60 flex-col divide-y overflow-y-auto rounded-lg border">
            {r.items.map((s) => (
              <li key={`${s.source}/${s.item}`}>
                <a
                  href={itemHref(s)}
                  className="flex items-center gap-2 px-2 py-1 text-sm hover:bg-muted/50"
                  title={s.grade ? t("RelatedWorks.yourGrade") : s.prediction ? t("RelatedWorks.prediction") : undefined}
                >
                  <span className="min-w-0 flex-1 truncate">{s.title}</span>
                  {s.grade ? (
                    <span className={cn("shrink-0 text-xs", GRADE_TEXT[s.grade as Grade])}>
                      {s.grade} · {gradeLabel(type, s.grade as Grade)}
                    </span>
                  ) : (
                    s.prediction && <span className="shrink-0 text-xs text-muted-foreground tabular-nums">★ {shownScore(s.prediction.score)}</span>
                  )}
                </a>
              </li>
            ))}
          </ul>
        </details>
      ))}
    </div>
  );
}
