import { useState } from "react";
import { PowerIcon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";

/** Where the user's word to stop without asking is kept in the browser. */
const NO_ASKING = "stop.noAsking";

/** Whether the user said to stop the application without asking again; false when the browser keeps nothing. */
export function stopsWithoutAsking(): boolean {
  try {
    return localStorage.getItem(NO_ASKING) === "true";
  } catch {
    return false;
  }
}

/** Stops the application as it should be stopped: its databases closed, its process ended. */
export async function stopApplication() {
  ensureOk(await api.POST("/api/launcher/stop"));
}

/**
 * THE STOP OF THE APPLICATION ASKED BEFORE IT IS DONE — and the user's word not to be asked again,
 * kept in the browser. Stopped, [onStopped]: the interface has nothing more to read.
 */
export function StopDialog({ open, onOpenChange, onStopped }: { open: boolean; onOpenChange: (open: boolean) => void; onStopped: () => void }) {
  const { t } = useTranslation("screen");
  const [noAsking, setNoAsking] = useState(false);
  const stop = async () => {
    await stopApplication();
    if (noAsking) {
      try {
        localStorage.setItem(NO_ASKING, "true");
      } catch {
        // Asked again next time
      }
    }
    onOpenChange(false);
    onStopped();
  };
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{t("Stop.title")}</DialogTitle>
          <DialogDescription>{t("Stop.description")}</DialogDescription>
        </DialogHeader>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={noAsking} onChange={(e) => setNoAsking(e.target.checked)} className="size-4" />
          {t("Stop.noAsking")}
        </label>
        <DialogFooter>
          <Button variant="outline" onClick={() => onOpenChange(false)}>
            {t("Stop.cancel")}
          </Button>
          <AsyncButton variant="destructive" onClick={stop} icon={<PowerIcon />}>
            {t("Stop.confirm")}
          </AsyncButton>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

/** What the interface shows once the application is stopped: how to start it again. */
export function Stopped() {
  const { t } = useTranslation("screen");
  return (
    <div className="flex min-h-screen flex-col items-center justify-center gap-2 p-6 text-center">
      <PowerIcon className="size-8 text-muted-foreground" />
      <h1 className="text-lg font-semibold">{t("Stop.stopped")}</h1>
      <p className="max-w-md text-sm text-muted-foreground">{t("Stop.startAgain")}</p>
    </div>
  );
}
