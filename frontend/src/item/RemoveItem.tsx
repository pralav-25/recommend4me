import { useState } from "react";
import { useQueryClient } from "@tanstack/react-query";
import { Trash2Icon } from "lucide-react";
import { useTranslation } from "react-i18next";
import { api, ensureOk, type ItemSummary } from "@/api/client";
import { AsyncButton } from "@/components/AsyncButton";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { ITEMS, itemKey } from "./lists";

/**
 * "Remove from the library": for a work gone from the site — with everything known of it, the
 * user's grades and answers too; asked to be confirmed, for it is not undone (a work still on the
 * site is made anew when seen there again, without them). Removed, the work leaves the lists, and
 * the card shows the next one in its place.
 */
export function RemoveItem({ summary, typeId }: { summary: ItemSummary; typeId: string }) {
  const { t } = useTranslation("item");
  const [asking, setAsking] = useState(false);
  const queryClient = useQueryClient();
  const ref = { source: summary.source, item: summary.item };

  const remove = async () => {
    ensureOk(await api.DELETE("/api/items/{source}/{item}", { params: { path: ref } }));
    setAsking(false);
    queryClient.removeQueries({ queryKey: itemKey(ref) });
    queryClient.invalidateQueries({ queryKey: [ITEMS] });
    // The counts of the filter values were counted with it
    queryClient.invalidateQueries({ queryKey: ["facets", typeId] });
  };

  return (
    <>
      <Button variant="destructive" size="sm" onClick={() => setAsking(true)}>
        <Trash2Icon /> {t("RemoveItem.button")}
      </Button>
      <Dialog open={asking} onOpenChange={setAsking}>
        <DialogContent className="max-w-md">
          <DialogHeader>
            <DialogTitle>{t("RemoveItem.confirmTitle")}</DialogTitle>
            <DialogDescription>{t("RemoveItem.confirmText", { title: summary.title })}</DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="outline" onClick={() => setAsking(false)}>
              {t("RemoveItem.cancel")}
            </Button>
            <AsyncButton variant="destructive" icon={<Trash2Icon />} onClick={remove}>
              {t("RemoveItem.remove")}
            </AsyncButton>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
