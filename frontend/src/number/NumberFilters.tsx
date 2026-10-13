import { useEffect, useState } from "react";
import { useTranslation } from "react-i18next";
import type { ContentTypeInfo, NumberInfo } from "@/api/client";
import { Input } from "@/components/ui/input";
import { label } from "@/i18n/label";
import { cn } from "@/lib/utils";

/** The range of a number the list is limited to: from so many, up to so many; an end not given is open. */
export type NumberRange = { from?: number; to?: number };

/** The ranges by the number's id (ContentTypeInfo.filterNumbers). */
export type NumberRanges = Record<string, NumberRange>;

/** The ranges of the numbers the content type still has, the empty ones left out. */
export function validRanges(type: ContentTypeInfo, ranges: NumberRanges): NumberRanges {
  return Object.fromEntries(
    Object.entries(ranges).filter(([id, r]) => type.filterNumbers.some((n) => n.id === id) && (r.from !== undefined || r.to !== undefined)),
  );
}

/** The ranges as the list asks for them: "<number id>=<from>..<to>". */
export function rangeParams(ranges: NumberRanges): string[] {
  return Object.entries(ranges).map(([id, r]) => `${id}=${r.from ?? ""}..${r.to ?? ""}`);
}

/**
 * "Chapters from … to …": the list limited to the works whose number lies within the range, both ends
 * included, for every number the content type's sources let it be limited by. A work without the
 * number is not shown while a range of it is set.
 */
export function NumberFilters({ type, value, onChange }: { type: ContentTypeInfo; value: NumberRanges; onChange: (value: NumberRanges) => void }) {
  return (
    <>
      {type.filterNumbers.map((n) => (
        <NumberFilter key={n.id} number={n} range={value[n.id] ?? {}} onChange={(r) => onChange({ ...value, [n.id]: r })} />
      ))}
    </>
  );
}

function NumberFilter({ number, range, onChange }: { number: NumberInfo; range: NumberRange; onChange: (range: NumberRange) => void }) {
  const { t } = useTranslation("number");
  const set = range.from !== undefined || range.to !== undefined;
  const name = label(number.labelKey);
  return (
    <div className={cn("flex items-center gap-1 rounded-lg border px-2 py-0.5 text-sm", set ? "border-primary/60" : "border-input")}>
      <span className="text-muted-foreground">{name}</span>
      <End
        label={t("NumberFilter.fromLabel", { label: name })}
        placeholder={t("NumberFilter.from")}
        value={range.from}
        onChange={(from) => onChange({ ...range, from })}
      />
      <span className="text-muted-foreground">–</span>
      <End
        label={t("NumberFilter.toLabel", { label: name })}
        placeholder={t("NumberFilter.to")}
        value={range.to}
        onChange={(to) => onChange({ ...range, to })}
      />
    </div>
  );
}

/**
 * One end of a range, typed as text: a number sets it, an empty field opens it, anything else is
 * kept in the field, marked, and not sent.
 */
function End({ label, placeholder, value, onChange }: { label: string; placeholder: string; value?: number; onChange: (value?: number) => void }) {
  const [text, setText] = useState(value?.toString() ?? "");
  // A range set elsewhere — another tab, the type switched back — is shown; not the field's own
  // value coming back, which would take "1," from under the user typing "1,5"
  useEffect(() => setText((t) => (parse(t) === value ? t : (value?.toString() ?? ""))), [value]);
  const parsed = parse(text);
  const invalid = parsed !== undefined && !Number.isFinite(parsed);
  return (
    <Input
      aria-label={label}
      placeholder={placeholder}
      inputMode="decimal"
      value={text}
      aria-invalid={invalid}
      className="h-6 w-14 px-1.5 text-center tabular-nums"
      onChange={(e) => {
        const next = e.target.value;
        setText(next);
        const n = parse(next);
        if (n === undefined || Number.isFinite(n)) onChange(n);
      }}
    />
  );
}

/** A typed end: undefined when empty, NaN when no number; a decimal comma read as a point. */
function parse(text: string): number | undefined {
  const t = text.trim();
  return t === "" ? undefined : Number(t.replace(",", "."));
}
