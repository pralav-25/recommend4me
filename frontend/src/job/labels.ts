import type { CatalogueProgress, SourceMode } from "@/api/client";
import { number } from "@/i18n/format";
import i18n from "@/i18n/i18n";

/** The phases of a job the application names: its own and the ones the sources name. */
const PHASES = ["dictionary", "feed", "catalogue", "details", "pages", "covers", "pictures", "reviews", "parts", "vectors", "scoring"] as const;

type Phase = (typeof PHASES)[number];

function isPhase(phase: string): phase is Phase {
  return (PHASES as readonly string[]).includes(phase);
}

/** A phase of a job by its name in the language of the interface; one the application does not know, as the source names it. */
export function phaseLabel(phase: string | undefined): string {
  if (!phase) return "";
  return isPhase(phase) ? i18n.t(`Phase.${phase}`, { ns: "job" }) : phase;
}

export function modeLabel(mode: SourceMode): string {
  return i18n.t(`Mode.${mode}`, { ns: "job" });
}

/**
 * How far the whole catalogue of a source is loaded, across the runs of its job: "Catalogue: feed
 * 120 / 650 pages", "Catalogue: work pages 20,000 / 27,516", "Catalogue loaded".
 */
export function catalogueLabel(c: CatalogueProgress): string {
  if (c.complete) return i18n.t("Catalogue.complete", { ns: "job" });
  const of = c.total > 0 ? `${number(c.done)} / ${number(c.total)}` : number(c.done);
  switch (c.stage) {
    case "none":
      return i18n.t("Catalogue.none", { ns: "job" });
    case "feed":
      return i18n.t("Catalogue.feed", { ns: "job", of });
    case "details":
      return i18n.t("Catalogue.details", { ns: "job", of });
    default:
      return i18n.t("Catalogue.phase", { ns: "job", phase: phaseLabel(c.stage), of });
  }
}
