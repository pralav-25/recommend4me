import type { BackendModule, ReadCallback } from "i18next";

/**
 * THE WORDS OF THE PLUGINS, loaded at run time: a source's, a scorer's, a catalogue's words are the
 * namespace of its id, laid out by the application as `/i18n/<id>/<language>.json`. They are read
 * as the interface needs them (`usePluginNamespaces`) and, once read, again in every language the
 * user switches to before the interface speaks it — i18next loads every namespace it was asked for
 * when the language changes. The application's own namespaces come with the code and are never read.
 */
export const pluginWords: BackendModule = {
  type: "backend",
  init() {},
  read(language: string, namespace: string, callback: ReadCallback) {
    fetch(`/i18n/${encodeURIComponent(namespace)}/${encodeURIComponent(language)}.json`)
      .then(async (response) => {
        // A plugin without words in the language: its keys are shown as they are
        if (response.status === 404) return callback(null, {});
        if (!response.ok) return callback(new Error(`HTTP ${response.status}`), false);
        callback(null, await response.json());
      })
      .catch((error: unknown) => callback(error instanceof Error ? error : new Error(String(error)), false));
  },
};
