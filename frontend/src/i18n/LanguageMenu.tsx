import { ChevronDownIcon, LanguagesIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSub,
  DropdownMenuSubContent,
  DropdownMenuSubTrigger,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";
import { currentLocale, setLocale } from "./i18n";
import { isLocale, LOCALE_NAMES, LOCALES } from "./locales";

/** The languages to choose from, each by its own name, the current one marked. */
function Choices() {
  return (
    <DropdownMenuRadioGroup
      value={currentLocale()}
      onValueChange={(v) => {
        const locale = String(v);
        if (isLocale(locale)) setLocale(locale);
      }}
    >
      {LOCALES.map((l) => (
        <DropdownMenuRadioItem key={l} value={l} lang={l}>
          {LOCALE_NAMES[l]}
        </DropdownMenuRadioItem>
      ))}
    </DropdownMenuRadioGroup>
  );
}

/** The language of the interface as a submenu of the folded menu ([ScreenMenuButton][@/screen/ScreenNav]). */
export function LanguageSubmenu() {
  const { t } = useTranslation();
  return (
    <DropdownMenuSub>
      {/* Opened by a press alone: on a touch screen there is no hover, and a hover opening it first makes the press close it */}
      <DropdownMenuSubTrigger openOnHover={false}>
        <LanguagesIcon /> {t("Language.title")}
        <span className="ms-2 text-muted-foreground">{LOCALE_NAMES[currentLocale()]}</span>
      </DropdownMenuSubTrigger>
      <DropdownMenuSubContent>
        <Choices />
      </DropdownMenuSubContent>
    </DropdownMenuSub>
  );
}

/** The language of the interface as an entry of the menu's bar on a wide screen ([ScreenBar][@/screen/ScreenNav]), its choices dropped down. */
export function LanguageBarMenu({ className }: { className: string }) {
  const { t } = useTranslation();
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<button type="button" className={cn(className)} title={t("Language.title")} />}>
        <LanguagesIcon /> {LOCALE_NAMES[currentLocale()]}
        <ChevronDownIcon className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-auto min-w-36">
        <Choices />
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
