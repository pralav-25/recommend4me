import i18n, { intlTag } from "./i18n";

/*
 * NUMBERS, DATES AND DURATIONS IN THE LANGUAGE OF THE INTERFACE: every call reads the language now,
 * nothing formatted is kept — the interface switched to another language shows it at once.
 */

/** A score with one decimal at most, as the language writes it: 7.5, 7,5. */
export function score(value: number): string {
  return (Math.round(value * 10) / 10).toLocaleString(intlTag());
}

export function formatDate(iso: string | undefined): string {
  if (!iso) return "";
  return new Date(iso).toLocaleString(intlTag(), {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** The day alone: 25.06.2019, 06/25/2019. */
export function formatDay(iso: string | undefined): string {
  if (!iso) return "";
  return new Date(iso).toLocaleDateString(intlTag(), { day: "2-digit", month: "2-digit", year: "numeric" });
}

/** How long ago something happened: "3 hours ago", "yesterday". */
export function ago(iso: string): string {
  const relative = new Intl.RelativeTimeFormat(intlTag(), { numeric: "auto" });
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
  const abs = Math.abs(seconds);
  if (abs < 60) return relative.format(Math.round(seconds), "second");
  if (abs < 3600) return relative.format(Math.round(seconds / 60), "minute");
  if (abs < 86400) return relative.format(Math.round(seconds / 3600), "hour");
  if (abs < 86400 * 30) return relative.format(Math.round(seconds / 86400), "day");
  if (abs < 86400 * 365) return relative.format(Math.round(seconds / (86400 * 30)), "month");
  return relative.format(Math.round(seconds / (86400 * 365)), "year");
}

/** A share as a percent; "—" when there is none, absent or null in the JSON alike. */
export function percent(x: number | null | undefined): string {
  return x == null ? "—" : `${Math.round(x * 100)}%`;
}

export function compact(n: number): string {
  return new Intl.NumberFormat(intlTag(), { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

/** A number as the language writes it: 1,234.5, 1 234,5. */
export function number(n: number): string {
  return n.toLocaleString(intlTag(), { maximumFractionDigits: 2 });
}

/** A duration in seconds as people say it: "2 h 15 min", "40 min", "less than a minute". */
export function duration(seconds: number): string {
  if (!isFinite(seconds) || seconds <= 0) return "";
  if (seconds < 60) return i18n.t("Duration.underMinute");
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return i18n.t("Duration.minutes", { minutes });
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? i18n.t("Duration.hours", { hours }) : i18n.t("Duration.hoursMinutes", { hours, minutes: rest });
}

/** Time left of a phase from its pace so far; undefined until there is a pace to judge by. */
export function timeLeft(phaseStartedAt: string | undefined, processed: number, total: number): string | undefined {
  if (!phaseStartedAt || processed <= 0 || total <= processed) return undefined;
  const elapsed = (Date.now() - new Date(phaseStartedAt).getTime()) / 1000;
  return duration((elapsed / processed) * (total - processed));
}
