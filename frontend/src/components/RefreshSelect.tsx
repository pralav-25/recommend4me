import { useTranslation } from "react-i18next";
import { REFRESH_EVERY } from "@/lib/useRefresh";
import { cn } from "@/lib/utils";

/**
 * The choice of how often an area reads its data again ([useRefresh]): every area that reads itself
 * again has one, in its heading.
 */
export function RefreshSelect({ every, onChange, className }: { every: number; onChange: (ms: number) => void; className?: string }) {
  const { t } = useTranslation();
  const word = (ms: number) =>
    ms === 0 ? t("Refresh.off") : ms < 60_000 ? t("Refresh.seconds", { value: ms / 1000 }) : t("Refresh.minutes", { value: ms / 60_000 });
  return (
    <label className={cn("flex items-center gap-1.5 text-xs font-normal text-muted-foreground", className)} title={t("Refresh.title")}>
      {t("Refresh.label")}
      <select value={every} onChange={(e) => onChange(Number(e.currentTarget.value))} className="h-7 rounded-md border bg-background px-1.5 text-foreground">
        {REFRESH_EVERY.map((ms) => (
          <option key={ms} value={ms}>
            {word(ms)}
          </option>
        ))}
      </select>
    </label>
  );
}
