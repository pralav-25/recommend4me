import { useEffect, useState } from "react";

/**
 * THE SCREENS OF THE INTERFACE, each an address of its own: the works at "/", the dictionary of
 * universes at "/universes" (one of them at "/universes/<catalogue>/<id>"), the tags at "/tags" (one
 * at "/tags/<key>"). A work's dialog is the "#item=…" over any of them. The server answers every
 * such address with the same page.
 */
export type Screen =
  | { kind: "works" }
  | { kind: "universes"; catalogue?: string; universe?: string }
  | { kind: "tags"; tag?: string };

export function parseScreen(path: string): Screen {
  const parts = path.split("/").filter((p) => p.length > 0).map((p) => {
    try {
      return decodeURIComponent(p);
    } catch {
      return p;
    }
  });
  if (parts[0] === "universes") return { kind: "universes", catalogue: parts[1], universe: parts[2] };
  if (parts[0] === "tags") return { kind: "tags", tag: parts.length > 1 ? parts.slice(1).join("/") : undefined };
  return { kind: "works" };
}

export function screenHref(screen: Screen): string {
  switch (screen.kind) {
    case "works":
      return "/";
    case "universes":
      return screen.catalogue && screen.universe ? `/universes/${encodeURIComponent(screen.catalogue)}/${encodeURIComponent(screen.universe)}` : "/universes";
    case "tags":
      // A tag's key may hold slashes ("m/m"): each piece escaped, the slashes kept
      return screen.tag ? `/tags/${screen.tag.split("/").map(encodeURIComponent).join("/")}` : "/tags";
  }
}

const NAVIGATED = "r4m-navigated";

/** Goes to [screen]: a new entry of the history, the work's dialog closed. */
export function navigate(screen: Screen) {
  const href = screenHref(screen);
  if (href === window.location.pathname && !window.location.hash) return;
  window.history.pushState(null, "", href);
  window.dispatchEvent(new Event(NAVIGATED));
}

/** The screen the address names now, followed through the links, Back and Forward. */
export function useScreen(): Screen {
  const [screen, setScreen] = useState(() => parseScreen(window.location.pathname));
  useEffect(() => {
    const update = () => setScreen(parseScreen(window.location.pathname));
    window.addEventListener("popstate", update);
    window.addEventListener(NAVIGATED, update);
    return () => {
      window.removeEventListener("popstate", update);
      window.removeEventListener(NAVIGATED, update);
    };
  }, []);
  return screen;
}

/**
 * The click on a link to a screen: kept by the page — no reload — unless it opens elsewhere (the
 * middle button, Ctrl, Shift, Cmd): those are the browser's, as of any link.
 */
export function followLink(e: React.MouseEvent<HTMLAnchorElement>, screen: Screen) {
  if (e.button !== 0 || e.ctrlKey || e.metaKey || e.shiftKey || e.altKey) return;
  e.preventDefault();
  navigate(screen);
}
