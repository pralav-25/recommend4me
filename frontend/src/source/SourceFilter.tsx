import { ChevronDownIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { DropdownMenu, DropdownMenuCheckboxItem, DropdownMenuContent, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { cn } from "@/lib/utils";

/**
 * Which sources of the content type the list shows; all at first. What is kept is the hidden
 * ones, so a source added later shows. The last shown one cannot be hidden.
 */
export function SourceFilter({ type, hidden, onChange }: { type: ContentTypeInfo; hidden: string[]; onChange: (hidden: string[]) => void }) {
  const { t } = useTranslation();
  if (type.sources.length < 2) return null;
  const shown = type.sources.filter((s) => !hidden.includes(s.id));
  const summary =
    shown.length === type.sources.length
      ? null
      : shown.length === 1
        ? shown[0].title
        : t("SourceFilter.someOf", { shown: shown.length, total: type.sources.length });
  return (
    <DropdownMenu>
      <DropdownMenuTrigger render={<Button variant="outline" size="sm" className={cn(summary && "border-primary/60")} />}>
        <span className="max-w-44 truncate">
          {t("SourceFilter.sites")}
          {summary && <span className="font-semibold">: {summary}</span>}
        </span>
        <ChevronDownIcon className="opacity-60" />
      </DropdownMenuTrigger>
      <DropdownMenuContent className="w-auto min-w-48">
        {type.sources.map((s) => {
          const on = !hidden.includes(s.id);
          return (
            <DropdownMenuCheckboxItem
              key={s.id}
              checked={on}
              disabled={on && shown.length === 1}
              onCheckedChange={(checked) => onChange(checked ? hidden.filter((h) => h !== s.id) : [...hidden, s.id])}
              closeOnClick={false}
            >
              {s.title}
            </DropdownMenuCheckboxItem>
          );
        })}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
