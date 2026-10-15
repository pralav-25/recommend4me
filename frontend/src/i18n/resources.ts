import type { Locale, PluralForm, PluralForms } from "./locales";
import captureEn from "../capture/capture-i18n.en.json";
import captureRu from "../capture/capture-i18n.ru.json";
import captureSr from "../capture/capture-i18n.sr.json";
import correctionEn from "../correction/correction-i18n.en.json";
import correctionRu from "../correction/correction-i18n.ru.json";
import correctionSr from "../correction/correction-i18n.sr.json";
import facetEn from "../facet/facet-i18n.en.json";
import facetRu from "../facet/facet-i18n.ru.json";
import facetSr from "../facet/facet-i18n.sr.json";
import itemEn from "../item/item-i18n.en.json";
import itemRu from "../item/item-i18n.ru.json";
import itemSr from "../item/item-i18n.sr.json";
import jobEn from "../job/job-i18n.en.json";
import jobRu from "../job/job-i18n.ru.json";
import jobSr from "../job/job-i18n.sr.json";
import markEn from "../mark/mark-i18n.en.json";
import markRu from "../mark/mark-i18n.ru.json";
import markSr from "../mark/mark-i18n.sr.json";
import modelEn from "../model/model-i18n.en.json";
import modelRu from "../model/model-i18n.ru.json";
import modelSr from "../model/model-i18n.sr.json";
import numberEn from "../number/number-i18n.en.json";
import numberRu from "../number/number-i18n.ru.json";
import numberSr from "../number/number-i18n.sr.json";
import partEn from "../part/part-i18n.en.json";
import partRu from "../part/part-i18n.ru.json";
import partSr from "../part/part-i18n.sr.json";
import pictureEn from "../picture/picture-i18n.en.json";
import pictureRu from "../picture/picture-i18n.ru.json";
import pictureSr from "../picture/picture-i18n.sr.json";
import reviewEn from "../review/review-i18n.en.json";
import reviewRu from "../review/review-i18n.ru.json";
import reviewSr from "../review/review-i18n.sr.json";
import screenEn from "../screen/screen-i18n.en.json";
import screenRu from "../screen/screen-i18n.ru.json";
import screenSr from "../screen/screen-i18n.sr.json";
import searchEn from "../search/search-i18n.en.json";
import searchRu from "../search/search-i18n.ru.json";
import searchSr from "../search/search-i18n.sr.json";
import settingsEn from "../settings/settings-i18n.en.json";
import settingsRu from "../settings/settings-i18n.ru.json";
import settingsSr from "../settings/settings-i18n.sr.json";
import standardEn from "../standard/standard-i18n.en.json";
import standardRu from "../standard/standard-i18n.ru.json";
import standardSr from "../standard/standard-i18n.sr.json";
import statusEn from "../status/status-i18n.en.json";
import statusRu from "../status/status-i18n.ru.json";
import statusSr from "../status/status-i18n.sr.json";
import suggestionEn from "../suggestion/suggestion-i18n.en.json";
import suggestionRu from "../suggestion/suggestion-i18n.ru.json";
import suggestionSr from "../suggestion/suggestion-i18n.sr.json";
import tagEn from "../tag/tag-i18n.en.json";
import tagRu from "../tag/tag-i18n.ru.json";
import tagSr from "../tag/tag-i18n.sr.json";
import universeEn from "../universe/universe-i18n.en.json";
import universeRu from "../universe/universe-i18n.ru.json";
import universeSr from "../universe/universe-i18n.sr.json";
import workloadEn from "../workload/workload-i18n.en.json";
import workloadRu from "../workload/workload-i18n.ru.json";
import workloadSr from "../workload/workload-i18n.sr.json";
import commonEn from "./common-i18n.en.json";
import commonRu from "./common-i18n.ru.json";
import commonSr from "./common-i18n.sr.json";

/*
 * THE TRANSLATIONS, ONE NAMESPACE A SECTION OF THE APPLICATION: the files of a section's words lie
 * beside its code, `<section>-i18n.<language>.json`; the words shared by every section are the
 * namespace "common"; the application's words the server sends by their keys — "standard:facet.status"
 * — are the namespace "standard". Keys are flat — "Component.purpose" — and a word of a count has a key of every
 * plural form of its language: "works_one", "works_few", "works_many", "works_other" in Russian.
 *
 * The English files are the shape of the keys (`i18next.d.ts`); every other language is checked
 * against them here, by the compiler: the same keys, no more and no fewer, and every word of a count
 * in each plural form of that language.
 */

type Keys<T> = Extract<keyof T, string>;

/** The words of a count, by the English "…_other" key. */
type Counted<En> = Keys<En> extends infer K ? (K extends `${infer Base}_other` ? Base : never) : never;

/** The keys a language must have: every key of the English, a word of a count in each of the language's plural forms. */
type Expected<En, Forms extends PluralForm> = Exclude<Keys<En>, `${Counted<En>}_${PluralForm}`> | `${Counted<En>}_${Forms}`;

/** Nothing when [T] has exactly the [Wanted] keys; else a type no file satisfies, naming the keys missing and extra. */
type Exactly<Wanted extends string, T> = [Exclude<Wanted, Keys<T>>, Exclude<Keys<T>, Wanted>] extends [never, never]
  ? unknown
  : { missingKeys: Exclude<Wanted, Keys<T>>; extraKeys: Exclude<Keys<T>, Wanted> };

/** A namespace in every language, its translations checked against the English. */
function namespace<En extends Record<string, string>, Ru, Sr>(
  en: En & Exactly<Expected<En, PluralForms["en"]>, En>,
  ru: Ru & Exactly<Expected<En, PluralForms["ru"]>, Ru>,
  sr: Sr & Exactly<Expected<En, PluralForms["sr"]>, Sr>,
): Record<Locale, Record<string, string>> {
  return { en, ru: ru as Record<string, string>, sr: sr as Record<string, string> };
}

const NAMESPACES = {
  capture: namespace(captureEn, captureRu, captureSr),
  common: namespace(commonEn, commonRu, commonSr),
  correction: namespace(correctionEn, correctionRu, correctionSr),
  facet: namespace(facetEn, facetRu, facetSr),
  item: namespace(itemEn, itemRu, itemSr),
  job: namespace(jobEn, jobRu, jobSr),
  mark: namespace(markEn, markRu, markSr),
  model: namespace(modelEn, modelRu, modelSr),
  number: namespace(numberEn, numberRu, numberSr),
  part: namespace(partEn, partRu, partSr),
  picture: namespace(pictureEn, pictureRu, pictureSr),
  review: namespace(reviewEn, reviewRu, reviewSr),
  screen: namespace(screenEn, screenRu, screenSr),
  search: namespace(searchEn, searchRu, searchSr),
  settings: namespace(settingsEn, settingsRu, settingsSr),
  standard: namespace(standardEn, standardRu, standardSr),
  status: namespace(statusEn, statusRu, statusSr),
  suggestion: namespace(suggestionEn, suggestionRu, suggestionSr),
  tag: namespace(tagEn, tagRu, tagSr),
  universe: namespace(universeEn, universeRu, universeSr),
  workload: namespace(workloadEn, workloadRu, workloadSr),
};

export type Namespace = keyof typeof NAMESPACES;

export const NAMESPACE_NAMES = Object.keys(NAMESPACES) as Namespace[];

/** The resources as i18next takes them: language, then namespace. */
export const RESOURCES = Object.fromEntries(
  (["en", "ru", "sr"] as const).map((lng) => [lng, Object.fromEntries(NAMESPACE_NAMES.map((ns) => [ns, NAMESPACES[ns][lng]]))]),
) as Record<Locale, Record<Namespace, Record<string, string>>>;

/** The shape of the keys, the English: what `t` is checked against. */
export type Resources = {
  capture: typeof captureEn;
  common: typeof commonEn;
  correction: typeof correctionEn;
  facet: typeof facetEn;
  item: typeof itemEn;
  job: typeof jobEn;
  mark: typeof markEn;
  model: typeof modelEn;
  number: typeof numberEn;
  part: typeof partEn;
  picture: typeof pictureEn;
  review: typeof reviewEn;
  screen: typeof screenEn;
  search: typeof searchEn;
  settings: typeof settingsEn;
  standard: typeof standardEn;
  status: typeof statusEn;
  suggestion: typeof suggestionEn;
  tag: typeof tagEn;
  universe: typeof universeEn;
  workload: typeof workloadEn;
};
