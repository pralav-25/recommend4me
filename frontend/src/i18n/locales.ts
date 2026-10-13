/** The languages of the interface; the first is the fallback, when neither the user nor the browser names one of them. */
export const LOCALES = ["en", "ru", "sr"] as const;

export type Locale = (typeof LOCALES)[number];

export const DEFAULT_LOCALE: Locale = "en";

/** Every language by its own name, as it is offered to choose — not translated. Serbian in Latin script. */
export const LOCALE_NAMES: Record<Locale, string> = {
  en: "English",
  ru: "Русский",
  sr: "Srpski",
};

/** The tag numbers, dates and plurals are formatted by. */
export const INTL_TAG: Record<Locale, string> = {
  en: "en-US",
  ru: "ru-RU",
  sr: "sr-Latn-RS",
};

/** The plural forms every language has (Intl.PluralRules): its translations give a key in each. */
export type PluralForm = "zero" | "one" | "two" | "few" | "many" | "other";

export type PluralForms = {
  en: "one" | "other";
  ru: "one" | "few" | "many" | "other";
  sr: "one" | "few" | "other";
};

export function isLocale(value: string | undefined | null): value is Locale {
  return (LOCALES as readonly string[]).includes(value ?? "");
}
