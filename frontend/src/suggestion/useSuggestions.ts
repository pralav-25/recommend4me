import { useQuery, type QueryClient } from "@tanstack/react-query";
import { api, refKey, unwrap, type ItemRef, type SourceInfo } from "@/api/client";
import { applyDetails } from "@/item/lists";

export function suggestionsKey(ref: ItemRef) {
  return ["suggestions", ref.source, ref.item];
}

/**
 * The values the application suggests for the item's facets — ones the work lacks and more likely
 * has than not; asked for only when the source has a facet to suggest values of.
 */
export function useSuggestions(ref: ItemRef, source: SourceInfo | undefined) {
  return useQuery({
    queryKey: suggestionsKey(ref),
    enabled: source?.facets.some((f) => f.suggest) ?? false,
    queryFn: async () =>
      unwrap(await api.GET("/api/items/{source}/{item}/suggestions", { params: { path: { source: ref.source, item: ref.item } } })),
  });
}

/** Every candidate of one facet of the item; the prefix of them all is the item's. */
export function candidatesKey(ref: ItemRef, facet?: string) {
  return facet === undefined ? ["candidates", ref.source, ref.item] : ["candidates", ref.source, ref.item, facet];
}

/**
 * Every value of a facet the work may be given and lacks — the characters of its universes, the
 * pairs of its characters — with the model's chance and how often its texts name it, the likeliest
 * first; asked for only when [enabled].
 */
export function useCandidates(ref: ItemRef, facet: string, enabled: boolean) {
  return useQuery({
    queryKey: candidatesKey(ref, facet),
    enabled,
    queryFn: async () =>
      unwrap(
        await api.GET("/api/items/{source}/{item}/candidates", { params: { path: { source: ref.source, item: ref.item }, query: { facet } } }),
      ),
  });
}

/** How many times each work has waited for the chances made after answers: a wait begun before the last answer is stale. */
const settling = new Map<string, number>();

/**
 * The work, its suggestions and its candidates read again once the application has made the
 * chances an answer changed — made apart from the answer, a second or two. An answer given
 * meanwhile makes this wait stale: the one after it waits for the chances of all of them.
 */
export function suggestionsLater(queryClient: QueryClient, ref: ItemRef) {
  const key = refKey(ref);
  const asked = (settling.get(key) ?? 0) + 1;
  settling.set(key, asked);
  void api.GET("/api/items/{source}/{item}/suggestions/settled", { params: { path: { source: ref.source, item: ref.item } } }).then((result) => {
    if (settling.get(key) !== asked || !result.response.ok) return;
    settling.delete(key);
    // The work as the application now has it, in the dialog and in the lists alike
    void api.GET("/api/items/{source}/{item}", { params: { path: { source: ref.source, item: ref.item } } }).then((read) => {
      if (read.data !== undefined && settling.get(key) === undefined) applyDetails(queryClient, read.data, ref);
    });
    queryClient.invalidateQueries({ queryKey: suggestionsKey(ref) });
    queryClient.invalidateQueries({ queryKey: candidatesKey(ref) });
  });
}
