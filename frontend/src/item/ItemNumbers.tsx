import { useState } from "react";
import { PlusIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import type { NumberValue, SourceInfo } from "@/api/client";
import { Button } from "@/components/ui/button";
import { CorrectedMark, EditButton, FieldEditor } from "@/correction/FieldEditor";
import { numberField, type Corrections } from "@/correction/useCorrections";
import { number } from "@/i18n/format";
import { label } from "@/i18n/label";

/** The numbers of the item, each correctable; a number the source declares but the item lacks can be given. */
export function ItemNumbers({ numbers, source, corrections }: { numbers: NumberValue[]; source?: SourceInfo; corrections: Corrections }) {
  const { t } = useTranslation("item");
  const [editing, setEditing] = useState<string | null>(null);
  const missing = (source?.numbers ?? []).filter((n) => !numbers.some((v) => v.key === n.key));
  const adding = missing.find((n) => n.key === editing);
  if (numbers.length === 0 && missing.length === 0) return null;
  return (
    <section>
      <h4 className="mb-2 text-sm font-semibold">{t("ItemNumbers.heading")}</h4>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] items-center gap-x-3 gap-y-1 text-sm">
        {numbers.map((n) => (
          <div key={n.key} className="group/number contents">
            <dt className="text-muted-foreground">{label(n.labelKey)}</dt>
            <dd className="flex min-w-0 items-center gap-1">
              {editing === n.key ? (
                <FieldEditor
                  value={String(n.value)}
                  numeric
                  corrected={n.corrected}
                  onSave={(content) => corrections.setField(numberField(n.key), content)}
                  onReset={() => corrections.resetField(numberField(n.key))}
                  onClose={() => setEditing(null)}
                  className="w-full"
                />
              ) : (
                <>
                  <span className="tabular-nums">{number(n.value)}</span>
                  {n.corrected && <CorrectedMark />}
                  <EditButton
                    onClick={() => setEditing(n.key)}
                    title={t("ItemNumbers.edit")}
                    className="opacity-0 transition-opacity group-hover/number:opacity-100 focus-visible:opacity-100 pointer-coarse:opacity-100"
                  />
                </>
              )}
            </dd>
          </div>
        ))}
        {adding && (
          <>
            <dt className="text-muted-foreground">{label(adding.labelKey)}</dt>
            <dd>
              <FieldEditor
                value=""
                numeric
                corrected={false}
                onSave={(content) => corrections.setField(numberField(adding.key), content)}
                onReset={async () => {}}
                onClose={() => setEditing(null)}
              />
            </dd>
          </>
        )}
      </dl>
      {missing.length > 0 && !adding && (
        <div className="mt-1 flex flex-wrap gap-1">
          {missing.map((n) => (
            <Button key={n.key} variant="ghost" size="xs" className="text-muted-foreground" onClick={() => setEditing(n.key)}>
              <PlusIcon /> {label(n.labelKey)}
            </Button>
          ))}
        </div>
      )}
    </section>
  );
}
