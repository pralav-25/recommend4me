import type { ReactNode } from "react";
import { MenuIcon, OrbitIcon, RadarIcon, TagsIcon } from "lucide-react";
import type { ContentTypeInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { typeIcon } from "@/contenttype/types";
import { cn } from "@/lib/utils";
import { ModelLabel } from "@/model/ModelDialog";
import { followLink, screenHref, type Screen } from "./screens";

/**
 * An entry of the menu: a screen of a content type, opened by a link — or a window opened by
 * [open]: the model's details, the browser extension's.
 */
type Entry = { key: string; label: ReactNode; title: string; active: boolean } & (
  | { screen: Screen; typeId: string }
  | { open: () => void }
);

export type MenuProps = {
  types: ContentTypeInfo[];
  type: ContentTypeInfo;
  screen: Screen;
  onType: (id: string) => void;
  onModel: () => void;
  onCapture: () => void;
};

/**
 * THE ENTRIES, ONE LIST FOR BOTH MENUS: the works of every content type by its name — "Игры",
 * "Книги" — the dictionary of universes, of the type that has one, the tags of the type chosen; then
 * the model of the type and the browser extension, each opening its window.
 */
function entries({ types, type, screen, onModel, onCapture }: MenuProps): Entry[] {
  const universes = type.universes ? type : types.find((t) => t.universes);
  return [
    ...types.map((t): Entry => {
      const Icon = typeIcon(t.id);
      return {
        key: `works:${t.id}`,
        screen: { kind: "works" },
        typeId: t.id,
        label: (
          <>
            <Icon /> {t.title}
          </>
        ),
        title: `${t.title}: работы с прогнозами`,
        active: screen.kind === "works" && t.id === type.id,
      };
    }),
    ...(universes
      ? [
          {
            key: "universes",
            screen: { kind: "universes" },
            typeId: universes.id,
            label: (
              <>
                <OrbitIcon /> Вселенные
              </>
            ),
            title: "Словарь вселенных фанфиков и их персонажи",
            active: screen.kind === "universes",
          } satisfies Entry,
        ]
      : []),
    {
      key: "tags",
      screen: { kind: "tags" },
      typeId: type.id,
      label: (
        <>
          <TagsIcon /> Теги
        </>
      ),
      title: `Теги всех сайтов (${type.title.toLowerCase()}): объединить одинаковые, назвать, выстроить надтеги и подтеги`,
      active: screen.kind === "tags",
    },
    { key: "model", open: onModel, label: <ModelLabel type={type} />, title: "Модель рекомендаций: как она упорядочивает работы", active: false },
    {
      key: "capture",
      open: onCapture,
      label: (
        <>
          <RadarIcon /> Расширение
        </>
      ),
      title: "Расширение для Firefox и скрипт для Safari на iPad: как подключить, что они присылают",
      active: false,
    },
  ];
}

/** A click on an entry of a screen: the type switched when it is another's, the screen followed as a link is. */
function follow(ev: React.MouseEvent<HTMLAnchorElement>, e: Entry & { screen: Screen; typeId: string }, props: MenuProps) {
  if (e.typeId !== props.type.id && ev.button === 0 && !ev.ctrlKey && !ev.metaKey && !ev.shiftKey && !ev.altKey) props.onType(e.typeId);
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
  return (
    <nav className="hidden items-center gap-1 xl:flex" aria-label="Меню">
      {entries(props).map((e) =>
        "screen" in e ? (
          <a
            key={e.key}
            href={screenHref(e.screen)}
            onClick={(ev) => follow(ev, e, props)}
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
    </nav>
  );
}

/**
 * THE SAME MENU UNDER A BUTTON, ON A NARROW SCREEN — the bar folded, not a second arrangement of
 * it: the button stands first in the header, before the name, and hides where the bar shows.
 */
export function ScreenMenuButton(props: MenuProps) {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="ghost" size="icon" className="-ms-1 xl:hidden" aria-label="Меню" />}>
        <MenuIcon />
      </DropdownMenuTrigger>
      {/* As wide as its words, not as the button it hangs on */}
      <DropdownMenuContent className="w-auto min-w-48">
        {entries(props).map((e) =>
          "screen" in e ? (
            <DropdownMenuItem
              key={e.key}
              render={<a href={screenHref(e.screen)} onClick={(ev) => follow(ev, e, props)} />}
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
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
