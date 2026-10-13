import { useQueryClient } from "@tanstack/react-query";
import { TriangleAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, pictureSrc, type ItemRef, type PictureInfo, type PictureMatch } from "@/api/client";
import { itemKey } from "@/item/lists";
import { InfluenceBadge, MarkButtons, MatchVerdict, StrengthBadge } from "@/mark/MarkButtons";
import { cn } from "@/lib/utils";

/**
 * The cover and the screenshots, each with its influence on the prediction. [large]: the pictures
 * tell what the work is (a game's screenshots) and are shown big; else small, beside the texts (a
 * book's cover). Each takes the user's 👍 or 👎 — a part of grading; what the points and the marks
 * mean is told above them in the marking ([explained]).
 */
export function Pictures({ item, pictures, large = true, explained = true }: { item: ItemRef; pictures: PictureInfo[]; large?: boolean; explained?: boolean }) {
  const { t } = useTranslation("picture");
  if (pictures.length === 0) return null;
  return (
    <section>
      <h4 className="mb-1 text-sm font-semibold">{large ? t("Pictures.heading") : t("Pictures.headingWithCover")}</h4>
      {explained && pictures.some((p) => p.influence != null) && <p className="mb-2 text-xs text-muted-foreground">{t("Pictures.explanation")}</p>}
      <div className={cn("grid gap-2", large ? "grid-cols-2 sm:grid-cols-3" : "grid-cols-3 sm:grid-cols-5")}>
        {pictures.map((p) => (
          <div
            key={p.position}
            className={cn("group relative rounded-md", p.mark === 1 && "ring-3 ring-yes", p.mark === -1 && "ring-3 ring-no")}
          >
            <a href={pictureSrc(item, p.position, true)} target="_blank" rel="noreferrer" className="block">
              <img
                src={pictureSrc(item, p.position)}
                alt=""
                loading="lazy"
                referrerPolicy="no-referrer"
                className="aspect-video w-full rounded-md bg-muted object-cover transition-opacity hover:opacity-85"
              />
              {p.position === 0 && (
                <span className="absolute top-1.5 left-1.5 rounded bg-black/60 px-1.5 py-0.5 text-xs text-white">{t("Pictures.cover")}</span>
              )}
              {!p.analyzed && !p.error && (
                <span
                  className="absolute bottom-1.5 left-1.5 rounded bg-black/60 px-1.5 py-0.5 text-xs text-white/80"
                  title={t("Pictures.notAnalyzedHint")}
                >
                  {t("Pictures.notAnalyzed")}
                </span>
              )}
              {p.error && (
                <span
                  className="absolute bottom-1.5 left-1.5 inline-flex items-center gap-1 rounded bg-black/70 px-1.5 py-0.5 text-xs text-maybe"
                  title={p.error}
                >
                  <TriangleAlertIcon className="size-3" /> {t("Pictures.error")}
                </span>
              )}
              {p.influence != null && (
                <InfluenceBadge
                  influence={p.influence}
                  className="absolute right-1.5 bottom-1.5"
                  title={t("Pictures.influence")}
                />
              )}
            </a>
            <PictureMark item={item} position={p.position} mark={p.mark} />
          </div>
        ))}
      </div>
    </section>
  );
}

/**
 * Two buttons on a picture: "this is why I would pick the work" and "this is why I would drop
 * it"; pressing the chosen one again clears it. Shown on hover, and always once chosen.
 */
function PictureMark({ item, position, mark }: { item: ItemRef; position: number; mark?: number }) {
  const queryClient = useQueryClient();
  const set = async (value: number | null) => {
    const params = { path: { source: item.source, item: item.item, position } };
    ensureOk(
      value === null
        ? await api.DELETE("/api/items/{source}/{item}/pictures/{position}/mark", { params })
        : await api.PUT("/api/items/{source}/{item}/pictures/{position}/mark", { params, body: { mark: value } }),
    );
    await queryClient.invalidateQueries({ queryKey: itemKey(item) });
  };
  return (
    <MarkButtons
      mark={mark}
      onMark={set}
      className={cn(
        "absolute top-1.5 right-1.5 opacity-0 transition-opacity group-hover:opacity-100 pointer-coarse:opacity-100",
        mark != null && "opacity-100",
      )}
    />
  );
}

/**
 * The pictures of this work alike pictures the user marked in other works: this work's on the
 * left, the marked one on the right, and between them how far from chance the likeness is.
 */
export function PictureMatches({ item, matches }: { item: ItemRef; matches: PictureMatch[] }) {
  const { t } = useTranslation("picture");
  if (matches.length === 0) return null;
  return (
    <section>
      <h4 className="mb-1 text-sm font-semibold">{t("PictureMatches.heading")}</h4>
      <p className="mb-2 text-xs text-muted-foreground">{t("PictureMatches.explanation")}</p>
      <div className="flex flex-col gap-2">
        {matches.map((m) => {
          const mark = { source: m.markSource, item: m.markItem };
          return (
            <div
              key={`${m.markSource}/${m.markItem}-${m.markPosition}-${m.position}`}
              className={cn("grid grid-cols-[1fr_auto_1fr] items-center gap-2", m.verdict === -1 && "opacity-40")}
            >
              <a href={pictureSrc(item, m.position, true)} target="_blank" rel="noreferrer">
                <img
                  src={pictureSrc(item, m.position)}
                  alt=""
                  loading="lazy"
                  referrerPolicy="no-referrer"
                  className="aspect-video w-full rounded-md bg-muted object-cover"
                />
              </a>
              <StrengthBadge
                mark={m.mark}
                strength={m.strength}
                title={m.mark > 0 ? t("PictureMatches.likePick") : t("PictureMatches.likeDrop")}
              />
              <figure className="min-w-0">
                <a href={pictureSrc(mark, m.markPosition, true)} target="_blank" rel="noreferrer">
                  <img
                    src={pictureSrc(mark, m.markPosition)}
                    alt=""
                    loading="lazy"
                    referrerPolicy="no-referrer"
                    className={cn("aspect-video w-full rounded-md bg-muted object-cover ring-2", m.mark > 0 ? "ring-yes" : "ring-no")}
                  />
                </a>
                <figcaption className="mt-1 flex items-center gap-1 text-xs text-muted-foreground">
                  <span className="min-w-0 flex-1 truncate">{m.markTitle}</span>
                  <MatchVerdict
                    item={item}
                    kind="PICTURE"
                    mark={mark}
                    markRef={String(m.markPosition)}
                    matchRef={String(m.position)}
                    verdict={m.verdict}
                  />
                </figcaption>
              </figure>
            </div>
          );
        })}
      </div>
    </section>
  );
}
