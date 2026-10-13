import { useState } from "react";
import { useMutation } from "@tanstack/react-query";
import { CheckIcon, Loader2Icon, PencilIcon, Undo2Icon, XIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

/**
 * The name the user gives a universe or a character over the catalogue's — the "Severus Snape" the
 * reader knows over the catalogue's translation of it: shown everywhere in its place, read in the
 * works' texts as one more name of it.
 * A pencil by the name opens a field; an empty one, or ↺, gives the catalogue's name back.
 */
export function LocalNameEdit({
  typeId,
  catalogue,
  entry,
  localName,
  catalogueName,
  onSaved,
}: {
  typeId: string;
  catalogue: string;
  entry: string;
  localName?: string;
  catalogueName: string;
  onSaved: () => Promise<unknown>;
}) {
  const { t } = useTranslation("universe");
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(localName ?? "");
  const save = useMutation({
    mutationFn: async (value: string | null) =>
      ensureOk(
        await api.PUT("/api/types/{type}/universes/{catalogue}/{entry}/name", {
          params: { path: { type: typeId, catalogue, entry } },
          body: { name: value ?? undefined },
        }),
      ),
    onSuccess: async () => {
      await onSaved();
      setEditing(false);
    },
  });

  if (!editing) {
    return (
      <span className="inline-flex items-center gap-0.5 align-middle">
        <Button
          variant="ghost"
          size="icon-xs"
          onClick={() => {
            setName(localName ?? "");
            setEditing(true);
          }}
          title={localName ? t("LocalName.ownNameHint", { name: catalogueName }) : t("LocalName.setOwnName")}
          aria-label={t("LocalName.ownName")}
        >
          <PencilIcon />
        </Button>
        {localName && (
          <Button
            variant="ghost"
            size="icon-xs"
            onClick={() => save.mutate(null)}
            disabled={save.isPending}
            title={t("LocalName.resetHint", { name: catalogueName })}
            aria-label={t("LocalName.reset")}
          >
            {save.isPending ? <Loader2Icon className="animate-spin" /> : <Undo2Icon />}
          </Button>
        )}
      </span>
    );
  }
  return (
    <form
      className="inline-flex items-center gap-1 align-middle"
      onSubmit={(e) => {
        e.preventDefault();
        save.mutate(name.trim() || null);
      }}
    >
      <Input
        autoFocus
        value={name}
        onChange={(e) => setName(e.target.value)}
        onKeyDown={(e) => e.key === "Escape" && setEditing(false)}
        placeholder={catalogueName}
        className="h-7 w-56"
        aria-label={t("LocalName.ownName")}
      />
      <Button type="submit" size="icon-xs" disabled={save.isPending} title={t("LocalName.saveHint")} aria-label={t("LocalName.save")}>
        {save.isPending ? <Loader2Icon className="animate-spin" /> : <CheckIcon />}
      </Button>
      <Button type="button" variant="ghost" size="icon-xs" onClick={() => setEditing(false)} title={t("LocalName.cancel")} aria-label={t("LocalName.cancel")}>
        <XIcon />
      </Button>
      {save.isError && <span className="text-xs text-destructive">{save.error.message}</span>}
    </form>
  );
}
