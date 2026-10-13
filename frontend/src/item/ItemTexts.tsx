import { useState } from "react";
import { ChevronDownIcon, PlusIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { SourceInfo, TextValue } from "@/api/client";
import { Button } from "@/components/ui/button";
import { CorrectedMark, EditButton, FieldEditor } from "@/correction/FieldEditor";
import { textField, type Corrections } from "@/correction/useCorrections";
import { label } from "@/i18n/label";
import { cn } from "@/lib/utils";

/** A text longer than this is cut to a few lines until opened: the length of a screen's paragraph, not meaning. */
const LONG = 600;

/**
 * The texts of the item: what the work is about first (its source's DESCRIPTION), open, a long one
 * cut until clicked; everything else — notes, changelogs, details — folded under its label, a
 * spoiler marked so. In the marking ([editing]) each can be corrected, and a text the source
 * declares but the item lacks can be written.
 */
export function ItemTexts({
  texts,
  source,
  corrections,
  editing = false,
}: {
  texts: TextValue[];
  source?: SourceInfo;
  corrections: Corrections;
  editing?: boolean;
}) {
  const [adding, setAdding] = useState<string | null>(null);
  // A facet's line as the site writes it is shown with the facet, not among the texts
  const facetLines = new Set((source?.facets ?? []).map((f) => f.original));
  const missing = (source?.texts ?? []).filter((t) => !texts.some((v) => v.key === t.key) && !facetLines.has(t.key));
  const added = missing.find((t) => t.key === adding);
  const roleOf = (key: string) => source?.texts.find((t) => t.key === key)?.role;
  const describing = texts.filter((t) => roleOf(t.key) === "DESCRIPTION");
  const rest = texts.filter((t) => roleOf(t.key) !== "DESCRIPTION");
  return (
    <>
      {describing.map((t) => (
        <ItemText key={t.key} text={t} folded={false} corrections={corrections} editing={editing} />
      ))}
      {rest.length > 0 && (
        <div className="flex flex-col gap-2">
          {rest.map((t) => (
            <ItemText key={t.key} text={t} folded corrections={corrections} editing={editing} />
          ))}
        </div>
      )}
      {editing && added && (
        <section>
          <h4 className="mb-1 text-sm font-semibold">{label(added.labelKey)}</h4>
          <FieldEditor
            value=""
            multiline
            corrected={false}
            onSave={(content) => corrections.setField(textField(added.key), content)}
            onReset={async () => {}}
            onClose={() => setAdding(null)}
          />
        </section>
      )}
      {editing && missing.length > 0 && !added && (
        <div className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
          {missing.map((t) => (
            <Button key={t.key} variant="ghost" size="xs" className="text-muted-foreground" onClick={() => setAdding(t.key)}>
              <PlusIcon /> {label(t.labelKey)}
            </Button>
          ))}
        </div>
      )}
    </>
  );
}

function ItemText({ text, folded, corrections, editing }: { text: TextValue; folded: boolean; corrections: Corrections; editing: boolean }) {
  const { t } = useTranslation("item");
  const [open, setOpen] = useState(!folded && !text.spoiler);
  const [whole, setWhole] = useState(false);
  const [editingText, setEditingText] = useState(false);
  const field = textField(text.key);
  const foldable = folded || text.spoiler;
  const long = text.content.length > LONG;
  return (
    <section className="group/text">
      <div className={cn("flex items-center gap-1", (open || editingText) && "mb-1")}>
        {foldable ? (
          <button className="inline-flex items-center gap-1 text-sm font-semibold hover:underline" onClick={() => setOpen(!open)} aria-expanded={open}>
            <ChevronDownIcon className={open ? "size-4" : "size-4 -rotate-90"} />
            {label(text.labelKey)}
            {text.spoiler && <span className="font-normal text-muted-foreground">· {t("ItemText.spoiler")}</span>}
          </button>
        ) : (
          <h4 className="text-sm font-semibold">{label(text.labelKey)}</h4>
        )}
        {text.corrected && <CorrectedMark />}
        {editing && !editingText && (
          <EditButton
            onClick={() => setEditingText(true)}
            title={t("ItemText.edit")}
            className="opacity-0 transition-opacity group-hover/text:opacity-100 focus-visible:opacity-100 pointer-coarse:opacity-100"
          />
        )}
      </div>
      {editingText ? (
        <FieldEditor
          value={text.content}
          multiline
          corrected={text.corrected}
          onSave={(content) => corrections.setField(field, content)}
          onReset={() => corrections.resetField(field)}
          onClose={() => setEditingText(false)}
        />
      ) : (
        open && (
          <p
            className={cn("text-sm leading-relaxed whitespace-pre-line text-foreground/90", long && !whole && "line-clamp-6 cursor-pointer")}
            onClick={() => long && setWhole(!whole)}
            title={long && !whole ? t("ItemText.showWhole") : undefined}
          >
            {text.content}
          </p>
        )
      )}
    </section>
  );
}
