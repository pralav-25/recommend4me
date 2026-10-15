import { useEffect, useState } from "react";

/**
 * THE SCREENS OF THE INTERFACE, each an address of its own: the works of a content type at
 * "/<type>" ("/books", "/games"), the dictionary of universes at "/universes" (one of them at
 * "/universes/<catalogue>/<id>"), the tags of every site at "/tags" (one at "/tags/<key>") — the
 * universes and the tags are every type's; the user's grades and marks, the newest first, at
 * "/history". What a page shows is all in its address: opened in
 * another tab, it shows the same. A work's dialog is the "#item=…" over any of them. The server
 * answers every such address with the same page.
 */
export type Screen =
  | {
      kind: "works";
      /** The content type; none at "/" — the one shown last, put in the address ([replaceScreen]). */
      type?: string;
    }
  | { kind: "universes"; catalogue?: string; universe?: string }
  | { kind: "tags"; tag?: string }
  | { kind: "history" }
  /** The models of a content type ("/models/<type>"); of the one shown last at "/models". */
  | { kind: "models"; type?: string };

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
  if (parts[0] === "history") return { kind: "history" };
  if (parts[0] === "models") return { kind: "models", type: parts[1] };
  return { kind: "works", type: parts[0] };
}

export function screenHref(screen: Screen): string {
  switch (screen.kind) {
    case "works":
      return screen.type ? `/${encodeURIComponent(screen.type)}` : "/";
    case "universes":
      return screen.catalogue && screen.universe ? `/universes/${encodeURIComponent(screen.catalogue)}/${encodeURIComponent(screen.universe)}` : "/universes";
    case "tags":
      // A tag's key may hold slashes ("m/m"): each piece escaped, the slashes kept
      return screen.tag ? `/tags/${screen.tag.split("/").map(encodeURIComponent).join("/")}` : "/tags";
    case "history":
      return "/history";
    case "models":
      return screen.type ? `/models/${encodeURIComponent(screen.type)}` : "/models";
  }
}

const NAVIGATED = "r4m-navigated";

/**
 * The address made whole — of the works at "/" or of an unknown type, the works of the type shown —
 * put in place of the address, not a step of the history; the work's dialog kept.
 */
export function replaceScreen(screen: Screen) {
  const href = screenHref(screen);
  if (href === window.location.pathname) return;
  window.history.replaceState(null, "", href + window.location.search + window.location.hash);
  window.dispatchEvent(new Event(NAVIGATED));
}

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
