import { CheckIcon, Undo2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { FacetValueInfo } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { cn } from "@/lib/utils";
import type { Corrections } from "./useCorrections";

/** A small button inside a chip; a finger's size on a touch screen. */
const BUTTON = "size-4 rounded-sm pointer-coarse:size-8";

/** A chip the buttons are in: as high as they are on a touch screen. */
export const CHIP_TOUCH = "pointer-coarse:h-9 pointer-coarse:text-sm";

/**
 * The user's word on a value of a facet, inside its chip: ✓ the work has it, ✕ it has it not — for
 * a value the user has not answered on, and a suggested one ([corrected] undefined). A value the
 * user confirmed or added says so by its look; the one button it keeps takes the word back, shown
 * when the chip is pointed at — always on a touch screen. One the user took away is crossed out and ↺ brings it back. A tag
 * the work has only by a tag under it ([implied]) takes no answer: it is the work's while that one is.
 */
export function FacetAnswers({
  facet,
  value,
  corrections,
}: {
  facet: string;
  value: { key: string; corrected?: FacetValueInfo["corrected"]; implied?: boolean };
  corrections: Corrections;
}) {
  const { t } = useTranslation("correction");
  const answer = (added: boolean) => corrections.setFacet(facet, { key: value.key }, added);
  const takeBack = () => corrections.resetFacet(facet, value.key);

  if (value.corrected === "REMOVED")
    return <AsyncButton variant="ghost" size="icon-xs" className={BUTTON} title={t("FacetAnswers.restore")} onClick={takeBack} icon={<Undo2Icon className="size-3 pointer-coarse:size-4" />} />;
  if (value.corrected === "ADDED" || value.corrected === "CONFIRMED")
    return (
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className={cn(BUTTON, "text-muted-foreground opacity-0 group-hover:opacity-100 focus-visible:opacity-100 pointer-coarse:opacity-100")}
        title={value.corrected === "ADDED" ? t("FacetAnswers.removeAdded") : t("FacetAnswers.withdraw")}
        onClick={takeBack}
        icon={<XIcon className="size-3 pointer-coarse:size-4" />}
      />
    );
  if (value.implied) return null;

  return (
    <>
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className={cn(BUTTON, "text-yes")}
        title={t("FacetAnswers.yes")}
        onClick={() => answer(true)}
        icon={<CheckIcon className="size-3 pointer-coarse:size-4" />}
      />
      <AsyncButton
        variant="ghost"
        size="icon-xs"
        className={cn(BUTTON, "text-destructive")}
        title={t("FacetAnswers.no")}
        onClick={() => answer(false)}
        icon={<XIcon className="size-3 pointer-coarse:size-4" />}
      />
    </>
  );
}
