import type { ReactNode } from "react";
import type { TFunction } from "i18next";
import { HistoryIcon, MenuIcon, OrbitIcon, PowerIcon, RadarIcon, TagsIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { typeIcon, typeTitle } from "@/contenttype/types";
import { LanguageBarMenu, LanguageSubmenu } from "@/i18n/LanguageMenu";
import { cn } from "@/lib/utils";
import { ModelLabel } from "@/model/GradeModel";
import { followLink, screenHref, type Screen } from "./screens";

/**
 * An entry of the menu: a screen of a content type, opened by a link — or a window opened by
 * [open]: the model's details, the browser extension's.
 */
type Entry = { key: string; label: ReactNode; title: string; active: boolean } & (
  | { screen: Screen }
  | { open: () => void }
);

export type MenuProps = {
  types: ContentTypeInfo[];
  type: ContentTypeInfo;
  screen: Screen;
  onCapture: () => void;
  /** The application stopped as it should be ([StopDialog][@/screen/StopDialog]). */
  onStop: () => void;
};

/**
 * THE ENTRIES, ONE LIST FOR BOTH MENUS: the works of every content type by its name — "Games",
 * "Books" — the dictionary of universes and the tags, both every type's; then
 * the model of the type and the browser extension, each opening its window; the stop of the
 * application. The language of the interface follows them, in each menu as fits it.
 */
function entries({ types, type, screen, onCapture, onStop }: MenuProps, t: TFunction<"screen">): Entry[] {
  const universes = types.some((tp) => tp.universes);
  return [
    ...types.map((tp): Entry => {
      const Icon = typeIcon(tp.id);
      return {
        key: `works:${tp.id}`,
        screen: { kind: "works", type: tp.id },
        label: (
          <>
            <Icon /> {typeTitle(tp.id)}
          </>
        ),
        title: t("Menu.works.title", { type: typeTitle(tp.id) }),
        active: screen.kind === "works" && tp.id === type.id,
      };
    }),
    ...(universes
      ? [
          {
            key: "universes",
            screen: { kind: "universes" },
            label: (
              <>
                <OrbitIcon /> {t("Menu.universes")}
              </>
            ),
            title: t("Menu.universes.title"),
            active: screen.kind === "universes",
          } satisfies Entry,
        ]
      : []),
    {
      key: "tags",
      screen: { kind: "tags" },
      label: (
        <>
          <TagsIcon /> {t("Menu.tags")}
        </>
      ),
      title: t("Menu.tags.title"),
      active: screen.kind === "tags",
    },
    {
      key: "history",
      screen: { kind: "history" },
      label: (
        <>
          <HistoryIcon /> {t("Menu.history")}
        </>
      ),
      title: t("Menu.history.title"),
      active: screen.kind === "history",
    },
    {
      key: "models",
      screen: { kind: "models", type: type.id },
      label: <ModelLabel type={type} />,
      title: t("Menu.model.title"),
      active: screen.kind === "models",
    },
    {
      key: "capture",
      open: onCapture,
      label: (
        <>
          <RadarIcon /> {t("Menu.capture")}
        </>
      ),
      title: t("Menu.capture.title"),
      active: false,
    },
    {
      key: "stop",
      open: onStop,
      label: (
        <>
          <PowerIcon /> {t("Menu.stop")}
        </>
      ),
      title: t("Menu.stop.title"),
      active: false,
    },
  ];
}

/** A click on an entry of a screen: followed as a link is — the type is in its address. */
function follow(ev: React.MouseEvent<HTMLAnchorElement>, e: Entry & { screen: Screen }) {
  followLink(ev, e.screen);
}

/** An entry of the bar: a link or a button drawn alike; the screen the user stands on filled — brighter than a hover. */
const ITEM =
  "inline-flex h-8 items-center gap-1.5 rounded-md px-3 text-sm font-medium whitespace-nowrap transition-colors hover:bg-muted [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0";
const ACTIVE = "bg-primary text-primary-foreground hover:bg-primary";

/**
 * THE BAR OF THE MENU, ON A WIDE SCREEN. Below xl (1280px) it does not fit beside the jobs, the
 * theme and the settings — the work list and six entries with their words — and goes whole; the
 * button of the same menu ([ScreenMenuButton]) takes its place at the start of the header. Both are
 * always in the markup and the breakpoint decides which shows: a width counted in script would be
 * a second truth of what is on the screen.
 */
export function ScreenBar(props: MenuProps) {
  const { t } = useTranslation("screen");
  return (
    <nav className="hidden items-center gap-1 xl:flex" aria-label={t("Menu.title")}>
      {entries(props, t).map((e) =>
        "screen" in e ? (
          <a
            key={e.key}
            href={screenHref(e.screen)}
            onClick={(ev) => follow(ev, e)}
            aria-current={e.active ? "page" : undefined}
            title={e.title}
            className={cn(ITEM, e.active && ACTIVE)}
          >
            {e.label}
          </a>
        ) : (
          <button key={e.key} type="button" onClick={e.open} title={e.title} className={ITEM}>
            {e.label}
          </button>
        ),
      )}
      <LanguageBarMenu className={ITEM} />
    </nav>
  );
}

/**
 * THE SAME MENU UNDER A BUTTON, ON A NARROW SCREEN — the bar folded, not a second arrangement of
 * it: the button stands first in the header, before the name, and hides where the bar shows.
 */
export function ScreenMenuButton(props: MenuProps) {
  const { t } = useTranslation("screen");
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="ghost" size="icon" className="-ms-1 xl:hidden" aria-label={t("Menu.title")} />}>
        <MenuIcon />
      </DropdownMenuTrigger>
      {/* As wide as its words, not as the button it hangs on */}
      <DropdownMenuContent className="w-auto min-w-48">
        {entries(props, t).map((e) =>
          "screen" in e ? (
            <DropdownMenuItem
              key={e.key}
              render={<a href={screenHref(e.screen)} onClick={(ev) => follow(ev, e)} />}
              aria-current={e.active ? "page" : undefined}
              className={cn(e.active && "bg-accent text-accent-foreground")}
            >
              {e.label}
            </DropdownMenuItem>
          ) : (
            <DropdownMenuItem key={e.key} onClick={e.open}>
              {e.label}
            </DropdownMenuItem>
          ),
        )}
        <DropdownMenuSeparator />
        <LanguageSubmenu />
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
