import i18n from "./i18n";

/**
 * THE WORDS OF A KEY THE SERVER SENT: the server words nothing for the user, it sends keys —
 * "standard:facet.status", "ficbook:title" — and the interface translates them. The namespace of a
 * key is "standard" (the application's own words, kept here) or a plugin's id (its words loaded at
 * run time, `plugins.ts`), so the keys are not typed: a key without words is shown as it is.
 */
export function label(key: string, params?: Record<string, unknown>): string {
  return translate(key, { ...params, defaultValue: key });
}

/** The name of a value: of the application's own its key's words, of the data as the data has it. */
export function valueName(value: { name: string; nameKey?: string }): string {
  return value.nameKey ? label(value.nameKey) : value.name;
}

/** A source's title, "<source>:title"; its id until its words come. */
export function sourceTitle(source: string): string {
  return translate(`${source}:title`, { defaultValue: source });
}

/**
 * A source's title cut short — "КФ", "AO3" — "<source>:title.short", where many sites are named in
 * little room; its full title until its words come or when it has none. Shown with the full titles in a title.
 */
export function sourceShort(source: string): string {
  return translate(`${source}:title.short`, { defaultValue: sourceTitle(source) });
}

/** A scorer's title, "<scorer>:title"; its id until its words come. */
export function scorerTitle(scorer: string): string {
  return translate(`${scorer}:title`, { defaultValue: scorer });
}

/** `t` without the typed keys: a server's key is known only at run time. */
function translate(key: string, options: Record<string, unknown>): string {
  return (i18n as unknown as { t: (key: string, options: Record<string, unknown>) => string }).t(key, options);
}
