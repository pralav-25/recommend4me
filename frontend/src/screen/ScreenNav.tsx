import { LibraryIcon, MenuIcon, OrbitIcon, TagsIcon } from "lucide-react";
import type { ContentTypeInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { followLink, screenHref, type Screen } from "./screens";

type Entry = { kind: Screen["kind"]; label: string; icon: typeof LibraryIcon; title: string };

/** The screens of a content type: the universes only of a type whose works may be fan fiction. */
function entries(type: ContentTypeInfo): Entry[] {
  return [
    { kind: "works", label: "Работы", icon: LibraryIcon, title: "Список работ с прогнозами" },
    ...(type.universes ? [{ kind: "universes" as const, label: "Вселенные", icon: OrbitIcon, title: "Словарь вселенных фанфиков и их персонажи" }] : []),
    { kind: "tags", label: "Теги", icon: TagsIcon, title: "Теги всех сайтов: объединить одинаковые, назвать" },
  ];
}

/**
 * THE SCREENS AS LINKS, not buttons: the middle button, Ctrl+click and "copy link" work only on an
 * <a href>. On a wide screen they are a row of tabs; on a narrow one the same row folds into one
 * menu — the same entries, not a second arrangement of them.
 */
export function ScreenNav({ type, screen }: { type: ContentTypeInfo; screen: Screen }) {
  const list = entries(type);
  const current = list.find((e) => e.kind === screen.kind) ?? list[0];
  return (
    <>
      <nav className="hidden items-center gap-1 rounded-lg bg-muted p-[3px] md:flex" aria-label="Экраны">
        {list.map((e) => {
          const active = e.kind === screen.kind;
          const Icon = e.icon;
          return (
            <a
              key={e.kind}
              href={screenHref({ kind: e.kind })}
              onClick={(ev) => followLink(ev, { kind: e.kind })}
              aria-current={active ? "page" : undefined}
              title={e.title}
              className={cn(
                "inline-flex h-8 items-center gap-1.5 rounded-md px-2.5 text-sm font-medium text-muted-foreground transition-colors hover:text-foreground lg:px-3",
                active && "bg-background text-foreground shadow-sm",
              )}
            >
              <Icon className="size-4" />
              <span className="hidden lg:inline">{e.label}</span>
            </a>
          );
        })}
      </nav>
      <DropdownMenu>
        <DropdownMenuTrigger render={<Button variant="ghost" size="sm" className="md:hidden" aria-label="Экраны" />}>
          <MenuIcon /> {current.label}
        </DropdownMenuTrigger>
        <DropdownMenuContent>
          {list.map((e) => {
            const Icon = e.icon;
            return (
              <DropdownMenuItem
                key={e.kind}
                render={<a href={screenHref({ kind: e.kind })} onClick={(ev) => followLink(ev, { kind: e.kind })} />}
                aria-current={e.kind === screen.kind ? "page" : undefined}
                className={cn(e.kind === screen.kind && "font-semibold")}
              >
                <Icon /> {e.label}
              </DropdownMenuItem>
            );
          })}
        </DropdownMenuContent>
      </DropdownMenu>
    </>
  );
}
