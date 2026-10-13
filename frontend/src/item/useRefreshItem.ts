import { useMutation, useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type ItemDetails, type ItemRef } from "@/api/client";
import { toast } from "@/components/ui/toast";
import i18n from "@/i18n/i18n";
import { applyDetails } from "./lists";

/**
 * Downloads the page of one item now. The dialog gets the new details at once, and the card is
 * replaced in place in every loaded list (its cover, facets and prediction may change).
 */
export function useRefreshItem(ref: ItemRef) {
  const queryClient = useQueryClient();
  return useMutation({
    // Why it failed (the site did not answer, the source cannot download) the backend says itself
    mutationFn: async (): Promise<ItemDetails> =>
      unwrap(await api.POST("/api/items/{source}/{item}/refresh", { params: { path: { source: ref.source, item: ref.item } } })),
    onSuccess: (details) => applyDetails(queryClient, details, ref),
    onError: (error) => toast.add({ title: i18n.t("RefreshItem.failed", { ns: "item" }), description: error.message, type: "error" }),
  });
}
