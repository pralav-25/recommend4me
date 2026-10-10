import { useQuery, type QueryClient, type QueryKey } from "@tanstack/react-query";
import { api, failure, refKey, type HeldOut, type ItemRef } from "@/api/client";

export const heldOutKey = (item: ItemRef): QueryKey => ["heldOut", item.source, item.item];

/**
 * What a model that had not seen the graded work says of it. The backend fits that model for the
 * asking — a second or more — so it is read apart from the work's details, which stay quick.
 */
export function useHeldOut(item: ItemRef, graded: boolean) {
  return useQuery({
    queryKey: heldOutKey(item),
    enabled: graded,
    queryFn: async (): Promise<HeldOut | null> => {
      const result = await api.GET("/api/items/{source}/{item}/held-out", { params: { path: { source: item.source, item: item.item } } });
      // No model, too few works for folds, or the work not graded after all
      if (result.response.status === 204) return null;
      if (result.data === undefined) throw failure(result);
      return result.data;
    },
  });
}

/** How long the answers on a work are let settle before its held-out model is fitted anew. */
const SETTLE_MS = 2000;

const pending = new Map<string, ReturnType<typeof setTimeout>>();

/**
 * Reads the held-out model of the work anew once the user has stopped answering on it: every
 * answer within [SETTLE_MS] of the last one puts the reading off again.
 */
export function heldOutLater(queryClient: QueryClient, item: ItemRef) {
  const key = refKey(item);
  clearTimeout(pending.get(key));
  pending.set(
    key,
    setTimeout(() => {
      pending.delete(key);
      queryClient.invalidateQueries({ queryKey: heldOutKey(item) });
    }, SETTLE_MS),
  );
}
