import type { CatalogueProgress, SourceMode } from "./api/client";

/** A score with one decimal at most, the Russian way: 7,5. */
export function score(value: number): string {
  return (Math.round(value * 10) / 10).toLocaleString("ru-RU");
}

/** The phases of a job: the application's own and the ones the sources name. */
const JOB_PHASE_LABEL: Record<string, string> = {
  dictionary: "Словарь тегов",
  feed: "Лента обновлений",
  catalogue: "Каталог",
  details: "Страницы работ",
  pages: "Страницы",
  covers: "Обложки",
  pictures: "Картинки",
  reviews: "Отзывы",
  parts: "Главы",
  vectors: "Векторы текстов",
  scoring: "Обучение и прогнозы",
};

export function phaseLabel(phase: string | undefined): string {
  return phase ? (JOB_PHASE_LABEL[phase] ?? phase) : "";
}

export const MODE_LABEL: Record<SourceMode, string> = {
  SCRAPE_ALL: "Весь каталог",
  UPDATES: "Обновления",
  BROWSER: "Слежение в браузере",
  PAGE: "Страница работы по запросу",
};

/**
 * How far the whole catalogue of a source is loaded, across the runs of its job: "Каталог: лента
 * 120 / 650 страниц", "Каталог: страницы работ 20 000 / 27 516", "Каталог загружен".
 */
export function catalogueLabel(c: CatalogueProgress): string {
  if (c.complete) return "Каталог загружен";
  const of = c.total > 0 ? `${number(c.done)} / ${number(c.total)}` : number(c.done);
  switch (c.stage) {
    case "none":
      return "Полный сбор каталога не запускался";
    case "feed":
      return `Каталог: лента ${of} страниц`;
    case "details":
      return `Каталог: страницы работ ${of}`;
    default:
      return `Каталог: ${phaseLabel(c.stage)} ${of}`;
  }
}

/** The background work of the application, as `/api/status` names it. */
export const WORK_LABEL: Record<string, { title: string; about: string }> = {
  pictures: {
    title: "Картинки",
    about: "Обложки и скриншоты, разобранные моделью картинок: сначала оценённых работ, потом остальных по прогнозу.",
  },
  texts: { title: "Тексты", about: "Описания и другие тексты, превращённые в векторы для модели и поиска по смыслу." },
  parts: { title: "Главы", about: "Тексты глав, нарезанные на фрагменты; у каждого фрагмента свой вектор." },
  sets: { title: "Наборы", about: "Пересчёт направлений наборов: скриншотов, отзывов, фрагментов текста." },
};

export function formatDate(iso: string | undefined): string {
  if (!iso) return "";
  return new Date(iso).toLocaleString("ru-RU", {
    day: "2-digit",
    month: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
}

/** "25.06.2019" — the day alone. */
export function formatDay(iso: string | undefined): string {
  if (!iso) return "";
  return new Date(iso).toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit", year: "numeric" });
}

const RELATIVE = new Intl.RelativeTimeFormat("ru", { numeric: "auto" });

/** "3 часа назад", "вчера" — how long ago something happened. */
export function ago(iso: string): string {
  const seconds = (new Date(iso).getTime() - Date.now()) / 1000;
  const abs = Math.abs(seconds);
  if (abs < 60) return RELATIVE.format(Math.round(seconds), "second");
  if (abs < 3600) return RELATIVE.format(Math.round(seconds / 60), "minute");
  if (abs < 86400) return RELATIVE.format(Math.round(seconds / 3600), "hour");
  if (abs < 86400 * 30) return RELATIVE.format(Math.round(seconds / 86400), "day");
  if (abs < 86400 * 365) return RELATIVE.format(Math.round(seconds / (86400 * 30)), "month");
  return RELATIVE.format(Math.round(seconds / (86400 * 365)), "year");
}

/** A share as a percent; "—" when there is none, absent or null in the JSON alike. */
export function percent(x: number | null | undefined): string {
  return x == null ? "—" : `${Math.round(x * 100)}%`;
}

export function compact(n: number): string {
  return new Intl.NumberFormat("ru-RU", { notation: "compact", maximumFractionDigits: 1 }).format(n);
}

/** A number as it is written: 1 234,5. */
export function number(n: number): string {
  return n.toLocaleString("ru-RU", { maximumFractionDigits: 2 });
}

const PLURAL = new Intl.PluralRules("ru-RU");

/** A count with the word in its Russian form: plural(5, "персонаж", "персонажа", "персонажей") — "5 персонажей". */
export function plural(n: number, one: string, few: string, many: string): string {
  const form = PLURAL.select(n);
  return `${number(n)} ${form === "one" ? one : form === "few" ? few : many}`;
}

/** "2 ч 15 мин", "40 мин", "меньше минуты" — a duration in seconds as people say it. */
export function duration(seconds: number): string {
  if (!isFinite(seconds) || seconds <= 0) return "";
  if (seconds < 60) return "меньше минуты";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} мин`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} ч` : `${hours} ч ${rest} мин`;
}

/** Time left of a phase from its pace so far; undefined until there is a pace to judge by. */
export function timeLeft(phaseStartedAt: string | undefined, processed: number, total: number): string | undefined {
  if (!phaseStartedAt || processed <= 0 || total <= processed) return undefined;
  const elapsed = (Date.now() - new Date(phaseStartedAt).getTime()) / 1000;
  return duration((elapsed / processed) * (total - processed));
}
