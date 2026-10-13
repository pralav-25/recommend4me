import { useState, type KeyboardEvent } from "react";
import { CheckIcon, PencilIcon, Undo2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { cn } from "@/lib/utils";

/** "corrected": the value shown is the user's, not the site's. */
export function CorrectedMark({ className }: { className?: string }) {
  const { t } = useTranslation("correction");
  return (
    <span
      className={cn("rounded border border-dashed border-primary/50 px-1 text-[10px] leading-4 font-normal text-muted-foreground", className)}
      title={t("CorrectedMark.hint")}
    >
      {t("CorrectedMark.label")}
    </span>
  );
}

export function EditButton({ onClick, title, className }: { onClick: () => void; title: string; className?: string }) {
  return (
    <Button
      variant="ghost"
      size="icon-xs"
      onClick={(e) => {
        e.stopPropagation();
        onClick();
      }}
      title={title}
      aria-label={title}
      className={cn("text-muted-foreground", className)}
    >
      <PencilIcon />
    </Button>
  );
}

/**
 * Editing one field of an item: the title, a text or a number. Saving overrides the site's value;
 * "as on the site" takes the correction back. Closes itself once the item is saved.
 */
export function FieldEditor({
  value,
  multiline = false,
  numeric = false,
  corrected,
  onSave,
  onReset,
  onClose,
  className,
}: {
  value: string;
  multiline?: boolean;
  numeric?: boolean;
  corrected: boolean;
  onSave: (content: string) => Promise<unknown>;
  onReset: () => Promise<unknown>;
  onClose: () => void;
  className?: string;
}) {
  const { t } = useTranslation("correction");
  const [draft, setDraft] = useState(value);
  const invalid = numeric ? draft.trim() === "" || !isFinite(Number(draft.replace(",", "."))) : false;
  const save = async () => {
    await onSave(numeric ? String(Number(draft.replace(",", "."))) : draft);
    onClose();
  };
  const reset = async () => {
    await onReset();
    onClose();
  };
  const keys = (e: KeyboardEvent) => {
    // The dialog's own keys (grades, arrows) must not take what is typed here
    e.stopPropagation();
    if (e.key === "Escape") {
      e.preventDefault();
      onClose();
    }
  };
  return (
    <div className={cn("flex flex-col gap-2", className)} onClick={(e) => e.stopPropagation()}>
      {multiline ? (
        <textarea
          autoFocus
          rows={Math.min(16, Math.max(4, draft.split("\n").length + 1))}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={keys}
          className="w-full rounded-lg border border-input bg-background px-2.5 py-2 text-sm leading-relaxed text-foreground outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
        />
      ) : (
        <Input
          autoFocus
          value={draft}
          inputMode={numeric ? "decimal" : undefined}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            keys(e);
            if (e.key === "Enter" && !invalid) save().catch(() => {});
          }}
          className="bg-background text-foreground"
        />
      )}
      <div className="flex flex-wrap items-center gap-1">
        <AsyncButton size="sm" onClick={save} disabled={invalid || draft === value} icon={<CheckIcon />}>
          {t("FieldEditor.save")}
        </AsyncButton>
        <Button size="sm" variant="ghost" onClick={onClose}>
          {t("FieldEditor.cancel")}
        </Button>
        {corrected && (
          <AsyncButton size="sm" variant="outline" onClick={reset} icon={<Undo2Icon />} className="ml-auto" title={t("FieldEditor.resetHint")}>
            {t("FieldEditor.reset")}
          </AsyncButton>
        )}
      </div>
    </div>
  );
}
