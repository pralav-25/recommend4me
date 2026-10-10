import { useQueryClient } from "@tanstack/react-query";
import { api, unwrap, type ItemDetails, type ItemRef } from "@/api/client";
import { applyDetails, ITEMS } from "@/item/lists";
import { heldOutLater } from "@/item/useHeldOut";
import { candidatesKey, suggestionsKey, suggestionsLater } from "@/suggestion/useSuggestions";

/** The fields a correction overrides: the title, a text, a number. */
export const titleField = "title";
export const textField = (key: string) => `text:${key}`;
export const numberField = (key: string) => `number:${key}`;

/**
 * The user's corrections of one item. Each returns once the item is saved and its new details
 * are in the dialog and in the lists; a failure throws, for the button that asked to show it.
 */
export function useCorrections(item: ItemRef, typeId: string) {
  const queryClient = useQueryClient();
  const path = { source: item.source, item: item.item };

  const done = (details: ItemDetails) => {
    applyDetails(queryClient, details, item);
    // The counts of the filter values change with the values of the item; an answered suggestion
    // is no longer listed, and a correction taken back may list it again
    queryClient.invalidateQueries({ queryKey: ["facets", typeId] });
    queryClient.invalidateQueries({ queryKey: suggestionsKey(item) });
    queryClient.invalidateQueries({ queryKey: candidatesKey(item) });
    // The held-out model is slow to fit: read anew only once the answers have settled
    heldOutLater(queryClient, details.summary);
    return details;
  };

  /** An answer on a facet: kept and shown at once; the chances it changes are made apart, and read when they are. */
  const answered = (details: ItemDetails) => {
    done(details);
    suggestionsLater(queryClient, item);
    return details;
  };

  return {
    setField: async (field: string, content: string) =>
      done(unwrap(await api.PUT("/api/items/{source}/{item}/corrections/fields/{field}", { params: { path: { ...path, field } }, body: { content } }))),

    resetField: async (field: string) =>
      done(unwrap(await api.DELETE("/api/items/{source}/{item}/corrections/fields/{field}", { params: { path: { ...path, field } } }))),

    /**
     * Adds a value of a facet ([added]) or takes away one the site gives. A value is named by its
     * key, or by its name alone: the backend finds it among the facet's values or makes it up.
     */
    setFacet: async (facet: string, value: { key?: string; name?: string }, added: boolean) =>
      answered(unwrap(await api.PUT("/api/items/{source}/{item}/corrections/facets", { params: { path }, body: { facet, ...value, added } }))),

    resetFacet: async (facet: string, key: string) =>
      answered(unwrap(await api.DELETE("/api/items/{source}/{item}/corrections/facets", { params: { path, query: { facet, key } } }))),

    /** The work's values of the facet are all named ([complete]): every other is taken from it — or that is taken back. */
    setComplete: async (facet: string, complete: boolean) =>
      answered(
        unwrap(
          complete
            ? await api.PUT("/api/items/{source}/{item}/corrections/complete", { params: { path, query: { facet } } })
            : await api.DELETE("/api/items/{source}/{item}/corrections/complete", { params: { path, query: { facet } } }),
        ),
      ),

    /** The other item is the same work: both keep their cards, which now name each other — the lists are asked for anew. */
    link: async (other: ItemRef) => {
      const details = done(
        unwrap(await api.POST("/api/items/{source}/{item}/links", { params: { path }, body: { source: other.source, item: other.item } })),
      );
      queryClient.invalidateQueries({ queryKey: [ITEMS] });
      return details;
    },

    /** The other item is another work again: the lists are asked for anew. */
    unlink: async (other: ItemRef) => {
      const details = done(
        unwrap(
          await api.DELETE("/api/items/{source}/{item}/links", { params: { path, query: { otherSource: other.source, otherItem: other.item } } }),
        ),
      );
      queryClient.invalidateQueries({ queryKey: [ITEMS] });
      return details;
    },
  };
}

export type Corrections = ReturnType<typeof useCorrections>;
