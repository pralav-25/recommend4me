import { useEffect, useState } from "react";
import i18n from "./i18n";

/**
 * Asks for the words of [namespaces] — the ids of the plugins shown (`plugins.ts`) — in the
 * language of the interface; true once they are there. What the namespaces are drawn with waits
 * for it, so a title is never shown as its key first.
 */
export function usePluginNamespaces(namespaces: readonly string[]): boolean {
  const wanted = [...new Set(namespaces)].sort().join(",");
  const loaded = wanted === "" || wanted.split(",").every((ns) => i18n.hasLoadedNamespace(ns));
  // The namespaces last asked for whose asking has ended, read or not
  const [asked, setAsked] = useState<string | undefined>();
  useEffect(() => {
    if (loaded) return;
    let live = true;
    // A namespace that could not be read is shown by its keys rather than never at all
    void i18n.loadNamespaces(wanted.split(",")).finally(() => {
      if (live) setAsked(wanted);
    });
    return () => {
      live = false;
    };
  }, [wanted, loaded]);
  return loaded || asked === wanted;
}
