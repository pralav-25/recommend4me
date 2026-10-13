import i18n from "i18next";
import LanguageDetector from "i18next-browser-languagedetector";
import { initReactI18next } from "react-i18next";
import { DEFAULT_LOCALE, INTL_TAG, isLocale, type Locale, LOCALES } from "./locales";
import { pluginWords } from "./plugins";
import { NAMESPACE_NAMES, RESOURCES } from "./resources";

/** Where the language the user chose is kept. */
const STORAGE_KEY = "language";

/**
 * THE LANGUAGE OF THE INTERFACE: the one the user chose (kept in the browser's local storage); else
 * the first of the browser's languages the interface has — "sr-Latn-RS" is Serbian — else English.
 * The application's namespaces come with the code; the plugins' are read from the server as they
 * are needed (`plugins.ts`).
 */
i18n
  .use(LanguageDetector)
  .use(pluginWords)
  .use(initReactI18next)
  .init({
    resources: RESOURCES,
    // The bundled namespaces are not read again; the others are the plugins', read at run time
    partialBundledLanguages: true,
    ns: NAMESPACE_NAMES,
    defaultNS: "common",
    supportedLngs: LOCALES,
    nonExplicitSupportedLngs: true,
    load: "languageOnly",
    fallbackLng: DEFAULT_LOCALE,
    keySeparator: false,
    interpolation: { escapeValue: false },
    detection: {
      order: ["localStorage", "navigator"],
      lookupLocalStorage: STORAGE_KEY,
      // Only the user's choice is kept ([setLocale]): a language taken from the browser follows the browser
      caches: [],
    },
  });

/** The language the interface speaks now. */
export function currentLocale(): Locale {
  const lng = i18n.resolvedLanguage;
  return isLocale(lng) ? lng : DEFAULT_LOCALE;
}

/** The tag of the language for Intl: numbers, dates. */
export function intlTag(): string {
  return INTL_TAG[currentLocale()];
}

/** The language chosen by the user: kept in the browser, the whole interface in it at once. */
export function setLocale(locale: Locale) {
  try {
    window.localStorage.setItem(STORAGE_KEY, locale);
  } catch {
    // Nowhere to keep it: the choice lives until the reload
  }
  void i18n.changeLanguage(locale);
}

function markDocument() {
  document.documentElement.lang = currentLocale();
}
markDocument();
i18n.on("languageChanged", markDocument);

export default i18n;
