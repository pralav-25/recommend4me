import type { ReactNode } from "react";
import { useTranslation } from "react-i18next";
import { ArrowDownWideNarrowIcon, CalendarPlusIcon, ChevronDownIcon, HashIcon, LightbulbIcon, SearchIcon, SparklesIcon, StarIcon, ZapIcon } from "lucide-react";
import type { ContentTypeInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuGroup,
  DropdownMenuLabel,
  DropdownMenuRadioGroup,
  DropdownMenuRadioItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { label } from "@/i18n/label";
import { ORDERS, sortNumbers, type SortChoice } from "./sort";

/** The order searched works come in: theirs alone while a line is searched. */
const RELEVANCE = "RELEVANCE";

/** The value of the menu of an order: an order of its own by its name, a number as "NUMBER:<id>". */
function valueOf(choice: SortChoice): string {
  return choice.sort === "NUMBER" ? `NUMBER:${choice.number}` : choice.sort;
}

function choiceOf(value: string): SortChoice {
  return value.startsWith("NUMBER:") ? { sort: "NUMBER", number: value.slice("NUMBER:".length) } : { sort: value as SortChoice["sort"] };
}

/** The words and the icon of every order of its own; the keys translated at render. */
const ORDER_WORDS = {
  SCORE: { label: "SortMenu.score", short: "SortMenu.score.short", hint: "SortMenu.score.title", icon: SparklesIcon },
  INTEREST: { label: "SortMenu.interest", short: "SortMenu.interest.short", hint: "SortMenu.interest.title", icon: LightbulbIcon },
  GRADE: { label: "SortMenu.grade", short: "SortMenu.grade.short", hint: "SortMenu.grade.title", icon: StarIcon },
  UPDATED: { label: "SortMenu.updated", short: "SortMenu.updated.short", hint: "SortMenu.updated.title", icon: ArrowDownWideNarrowIcon },
  ADDED: { label: "SortMenu.added", short: "SortMenu.added.short", hint: "SortMenu.added.title", icon: CalendarPlusIcon },
  SURPRISE: { label: "SortMenu.surprise", short: "SortMenu.surprise.short", hint: "SortMenu.surprise.title", icon: ZapIcon },
} as const satisfies Record<(typeof ORDERS)[number], object>;

/**
 * THE ORDER OF THE LIST, ONE CHOICE OF EVERY ORDER ALIKE: by the formula, by interest, by the
 * prediction of the grade, by the date of the update,
 * by the date the application first saw the work, by surprise, then by every number the list can
 * be sorted by — the standard ones first. One button showing the order chosen — on a phone its short
 * name — opens the list. While a line is searched, the works come by relevance alone: the button
 * says so, and the other orders wait for the search to be cleared.
 */
export function SortMenu({
  type,
  value,
  searching,
  onChange,
}: {
  type: ContentTypeInfo;
  value: SortChoice;
  searching: boolean;
  onChange: (choice: SortChoice) => void;
}) {
  const { t } = useTranslation();
  const numbers = sortNumbers(type);
  const number = value.sort === "NUMBER" ? numbers.find((n) => n.id === value.number) : undefined;

  // What the button shows: the order chosen, its short name on a phone
  let shown: ReactNode;
  if (searching) {
    shown = (
      <>
        <SearchIcon /> {t("SortMenu.relevance")}
      </>
    );
  } else if (number) {
    shown = (
      <>
        <HashIcon /> {label(number.labelKey)}
      </>
    );
  } else {
    const words = ORDER_WORDS[value.sort === "NUMBER" ? "SCORE" : value.sort];
    const Icon = words.icon;
    shown = (
      <>
        <Icon /> <span className="sm:hidden">{t(words.short)}</span>
        <span className="hidden sm:inline">{t(words.label)}</span>
      </>
    );
  }

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        render={<Button variant="outline" size="sm" aria-label={t("SortMenu.label")} title={searching ? t("SortMenu.searching") : t("SortMenu.label")} />}
      >
        {shown}
        <ChevronDownIcon className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-auto min-w-56">
        <DropdownMenuRadioGroup value={searching ? RELEVANCE : valueOf(value)} onValueChange={(v) => v !== RELEVANCE && onChange(choiceOf(String(v)))}>
          {searching && (
            <DropdownMenuRadioItem value={RELEVANCE} title={t("SortMenu.searching")}>
              <SearchIcon /> {t("SortMenu.relevance")}
            </DropdownMenuRadioItem>
          )}
          {ORDERS.map((order) => {
            const words = ORDER_WORDS[order];
            const Icon = words.icon;
            return (
              <DropdownMenuRadioItem key={order} value={order} disabled={searching} title={t(words.hint)}>
                <Icon /> {t(words.label)}
              </DropdownMenuRadioItem>
            );
          })}
          {numbers.length > 0 && (
            <>
              <DropdownMenuSeparator />
              <DropdownMenuGroup>
                <DropdownMenuLabel>{t("SortMenu.number")}</DropdownMenuLabel>
                {numbers.map((n) => (
                  <DropdownMenuRadioItem
                    key={n.id}
                    value={`NUMBER:${n.id}`}
                    disabled={searching}
                    title={t("SortMenu.number.title", { number: label(n.labelKey) })}
                  >
                    <HashIcon /> {label(n.labelKey)}
                  </DropdownMenuRadioItem>
                ))}
              </DropdownMenuGroup>
            </>
          )}
        </DropdownMenuRadioGroup>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
