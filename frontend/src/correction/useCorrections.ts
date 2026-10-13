import { useQueryClient, type QueryClient } from "@tanstack/react-query";
import { api, ensureOk, refKey, unwrap, type Candidate, type FacetSuggestions, type FacetValueInfo, type ItemDetails, type ItemRef } from "@/api/client";
import { applyDetails, ITEMS, itemKey } from "@/item/lists";
import { heldOutLater } from "@/item/useHeldOut";
import { candidatesKey, suggestionsKey, suggestionsLater } from "@/suggestion/useSuggestions";

/** The fields a correction overrides: the title, a text, a number. */
export const titleField = "title";
export const textField = (key: string) => `text:${key}`;
export const numberField = (key: string) => `number:${key}`;

/** The quiet after the last answer on a work after which it is read anew: answers given one after another wait for no reading. */
const SETTLE_MS = 2000;

/** The read of every work pending after its answers, by "source/item". */
const pending = new Map<string, ReturnType<typeof setTimeout>>();

/**
 * Once no answer on the work has come for [SETTLE_MS]: the counts of the filters and its
 * suggestions read anew, and the work itself with its candidates once the chances its answers
 * change are made (suggestionsLater) — read before, they would wait for those chances all the same,
 * and be read again after them. The answers are shown meanwhile as kept. Nothing is read between answers.
 */
function settleLater(queryClient: QueryClient, item: ItemRef, typeId: string) {
  const key = refKey(item);
  clearTimeout(pending.get(key));
  pending.set(
    key,
    setTimeout(() => {
      pending.delete(key);
      queryClient.invalidateQueries({ queryKey: ["facets", typeId] });
      queryClient.invalidateQueries({ queryKey: suggestionsKey(item) });
      heldOutLater(queryClient, item);
      suggestionsLater(queryClient, item);
    }, SETTLE_MS),
  );
}

/** What a value answered on is shown by, of what the page shows already: the work's values, its suggestions, its candidates. */
function shownName(queryClient: QueryClient, item: ItemRef, facet: string, key: string): Pick<FacetValueInfo, "name" | "nameKey"> | undefined {
  const details = queryClient.getQueryData<ItemDetails>(itemKey(item));
  const own = details?.allFacets.find((f) => f.facet === facet)?.values.find((v) => v.key === key);
  if (own) return { name: own.name, nameKey: own.nameKey };
  const suggested = queryClient.getQueryData<FacetSuggestions[]>(suggestionsKey(item))?.find((s) => s.facet === facet)?.suggested.find((v) => v.key === key);
  if (suggested) return { name: suggested.name, nameKey: suggested.nameKey };
  const candidate = queryClient.getQueryData<Candidate[]>(candidatesKey(item, facet))?.find((c) => c.key === key);
  return candidate && { name: candidate.name, nameKey: candidate.nameKey };
}

/**
 * The work's values with an answer the application has kept, as the page shows it until the work is
 * read anew: added — the user's (CONFIRMED of a value the site, the model or a draft gave, ADDED of
 * any other), taken away — REMOVED, crossed out in its place; [added] null — the answer taken back.
 */
function withAnswer(
  details: ItemDetails,
  facet: string,
  key: string,
  added: boolean | null,
  shown: Pick<FacetValueInfo, "name" | "nameKey"> | undefined,
): ItemDetails {
  return {
    ...details,
    allFacets: details.allFacets.map((f) => {
      if (f.facet !== facet) return f;
      const at = f.values.findIndex((v) => v.key === key);
      const v = at >= 0 ? f.values[at] : undefined;
      // Given by the site, the model or a draft before the answer
      const given = v !== undefined && ((v.corrected == null && !v.offered && !v.implied) || (v.chance ?? 0) > 0.5 || v.draftedFrom != null);
      if (added === null) {
        if (!v) return f;
        // A value only the user gave goes with the answer; any other stays, unanswered
        if (v.corrected === "ADDED" && !given) return { ...f, values: f.values.filter((x) => x !== v) };
        return { ...f, values: f.values.map((x) => (x === v ? { ...x, corrected: undefined } : x)) };
      }
      const corrected: FacetValueInfo["corrected"] = !added ? "REMOVED" : given ? "CONFIRMED" : "ADDED";
      if (v) return { ...f, values: f.values.map((x) => (x === v ? { ...x, corrected, offered: undefined } : x)) };
      return { ...f, values: [...f.values, { key, name: shown?.name ?? key, nameKey: shown?.nameKey, corrected }] };
    }),
  };
}

/** The suggestions of the work without the value answered on: it is answered, no longer a suggestion. */
function withoutSuggested(list: FacetSuggestions[] | undefined, facet: string, key: string): FacetSuggestions[] | undefined {
  return list?.map((s) => (s.facet === facet ? { ...s, suggested: s.suggested.filter((v) => v.key !== key) } : s));
}

/**
 * The user's corrections of one item. Each returns once the item is saved; a failure throws, for the
 * button that asked to show it. An answer on a facet is only written: the page shows it once kept,
 * and reads the work anew once the answers have settled ([settleLater]).
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
    heldOutLater(queryClient, item);
    return details;
  };

  /** An answer kept: shown as it is now, the work read anew after the quiet. */
  const shownAnswer = (facet: string, key: string, added: boolean | null, shown?: Pick<FacetValueInfo, "name" | "nameKey">) => {
    const name = shown ?? shownName(queryClient, item, facet, key);
    queryClient.setQueryData<ItemDetails>(itemKey(item), (d) => d && withAnswer(d, facet, key, added, name));
    queryClient.setQueryData<FacetSuggestions[]>(suggestionsKey(item), (list) => withoutSuggested(list, facet, key));
    settleLater(queryClient, item, typeId);
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
    setFacet: async (facet: string, value: { key?: string; name?: string }, added: boolean) => {
      const answer = unwrap(await api.PUT("/api/items/{source}/{item}/corrections/facets", { params: { path }, body: { facet, ...value, added } }));
      shownAnswer(facet, answer.key, added, answer.name ? { name: answer.name } : undefined);
    },

    resetFacet: async (facet: string, key: string) => {
      ensureOk(await api.DELETE("/api/items/{source}/{item}/corrections/facets", { params: { path, query: { facet, key } } }));
      shownAnswer(facet, key, null);
    },

    /** The work's values of the facet are all named ([complete]): every other is taken from it — or that is taken back. */
    setComplete: async (facet: string, complete: boolean) => {
      ensureOk(
        complete
          ? await api.PUT("/api/items/{source}/{item}/corrections/complete", { params: { path, query: { facet } } })
          : await api.DELETE("/api/items/{source}/{item}/corrections/complete", { params: { path, query: { facet } } }),
      );
      queryClient.setQueryData<ItemDetails>(itemKey(item), (d) =>
        d && {
          ...d,
          allFacets: d.allFacets.map((f) =>
            // Said complete, nothing more is offered
            f.facet !== facet ? f : { ...f, complete: complete || undefined, values: complete ? f.values.filter((v) => !v.offered) : f.values },
          ),
        },
      );
      settleLater(queryClient, item, typeId);
    },

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
