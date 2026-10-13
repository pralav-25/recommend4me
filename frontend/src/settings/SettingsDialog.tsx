import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2Icon, TriangleAlertIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, unwrap, type SettingValue, type SourceSettings } from "@/api/client";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { label, sourceTitle } from "@/i18n/label";

export function useSettings() {
  return useQuery({ queryKey: ["settings"], queryFn: async () => unwrap(await api.GET("/api/settings")) });
}

type Props = { open: boolean; onOpenChange: (open: boolean) => void };

/** The settings of every source that has any: logins, pauses between requests and the like. */
export function SettingsDialog({ open, onOpenChange }: Props) {
  const { t } = useTranslation("settings");
  const settings = useSettings();
  const sources = (settings.data ?? []).filter((s) => s.values.length > 0);
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-h-[90vh] max-w-2xl overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{t("SettingsDialog.title")}</DialogTitle>
          <DialogDescription>{t("SettingsDialog.description")}</DialogDescription>
        </DialogHeader>
        {settings.isError && <div className="text-sm text-destructive">{t("SettingsDialog.loadFailed")}</div>}
        {settings.data && sources.length === 0 && <div className="text-sm text-muted-foreground">{t("SettingsDialog.nothing")}</div>}
        {open && sources.map((s) => <SourceForm key={s.source} settings={s} />)}
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t("SettingsDialog.close")}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/**
 * The settings of one source. A secret is never shown: only whether it is set; typing a value
 * replaces it, the button beside it takes it away. Only what was changed is sent.
 */
function SourceForm({ settings }: { settings: SourceSettings }) {
  const { t } = useTranslation("settings");
  const queryClient = useQueryClient();
  const initial = () => Object.fromEntries(settings.values.map((v) => [v.key, v.secret ? "" : (v.value ?? "")]));
  const [draft, setDraft] = useState<Record<string, string>>(initial);
  const [saved, setSaved] = useState(false);

  const save = useMutation({
    mutationFn: async (body: Record<string, string>) =>
      unwrap(await api.PUT("/api/sources/{source}/settings", { params: { path: { source: settings.source } }, body })),
    onSuccess: (data) => {
      queryClient.setQueryData<SourceSettings[]>(["settings"], (all) => all?.map((s) => (s.source === data.source ? data : s)));
      setDraft(Object.fromEntries(data.values.map((v) => [v.key, v.secret ? "" : (v.value ?? "")])));
      setSaved(true);
    },
  });

  const changed = Object.fromEntries(
    settings.values
      .filter((v) => (v.secret ? draft[v.key] !== "" : draft[v.key] !== (v.value ?? "")))
      .map((v) => [v.key, draft[v.key]]),
  );
  const set = (key: string, value: string) => {
    setSaved(false);
    setDraft({ ...draft, [key]: value });
  };

  return (
    <section className="flex flex-col gap-4 rounded-lg border p-4">
      <h3 className="font-semibold">{sourceTitle(settings.source)}</h3>
      {settings.values.map((v) => (
        <Field
          key={v.key}
          id={`${settings.source}-${v.key}`}
          value={v}
          draft={draft[v.key] ?? ""}
          onChange={(x) => set(v.key, x)}
          onClear={() => save.mutate({ [v.key]: "" })}
          busy={save.isPending}
        />
      ))}
      {save.isError && <div className="text-sm text-destructive">{t("SourceForm.saveFailed", { error: String(save.error.message) })}</div>}
      <div className="flex items-center justify-end gap-2">
        {saved && Object.keys(changed).length === 0 && (
          <span className="inline-flex items-center gap-1 text-xs text-yes">
            <CheckCircle2Icon className="size-3.5" /> {t("SourceForm.saved")}
          </span>
        )}
        <Button onClick={() => save.mutate(changed)} disabled={save.isPending || Object.keys(changed).length === 0}>
          {t("SourceForm.save")}
        </Button>
      </div>
    </section>
  );
}

function Field({
  id,
  value: v,
  draft,
  onChange,
  onClear,
  busy,
}: {
  id: string;
  value: SettingValue;
  draft: string;
  onChange: (value: string) => void;
  onClear: () => void;
  busy: boolean;
}) {
  const { t } = useTranslation("settings");
  return (
    <div className="grid gap-2">
      <Label htmlFor={id} className="flex items-center gap-2">
        {label(v.labelKey)}
        {v.secret &&
          (v.set ? (
            <span className="inline-flex items-center gap-1 text-xs text-yes">
              <CheckCircle2Icon className="size-3.5" /> {t("Field.set")}
            </span>
          ) : (
            <span className="inline-flex items-center gap-1 text-xs text-maybe">
              <TriangleAlertIcon className="size-3.5" /> {t("Field.notSet")}
            </span>
          ))}
      </Label>
      {v.secret ? (
        <div className="flex items-start gap-2">
          <textarea
            id={id}
            rows={3}
            value={draft}
            onChange={(e) => onChange(e.target.value)}
            placeholder={v.set ? t("Field.keepEmpty") : t("Field.newValue")}
            autoComplete="off"
            spellCheck={false}
            className="min-h-16 w-full rounded-lg border border-input bg-transparent px-2.5 py-2 font-mono text-xs outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 dark:bg-input/30"
          />
          {v.set && (
            <Button variant="destructive" size="sm" onClick={onClear} disabled={busy}>
              {t("Field.delete")}
            </Button>
          )}
        </div>
      ) : (
        <Input
          id={id}
          type={v.kind === "NUMBER" ? "number" : "text"}
          value={draft}
          onChange={(e) => onChange(e.target.value)}
          placeholder={v.set ? undefined : t("Field.default")}
        />
      )}
      {v.hintKey && <p className="text-xs text-muted-foreground">{label(v.hintKey)}</p>}
    </div>
  );
}
