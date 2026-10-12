import type { InfiniteData, QueryClient, QueryKey } from "@tanstack/react-query";
import { sameItem, type ItemDetails, type ItemPage, type ItemRef, type ItemSummary } from "@/api/client";

/** The cache key of a page list: ["items", type, view, …the rest of the query]. */
export const ITEMS = "items";

export function itemKey(ref: ItemRef): QueryKey {
  return ["item", ref.source, ref.item];
}

type Lists = InfiniteData<ItemPage>;

function eachList(queryClient: QueryClient, change: (data: Lists, key: QueryKey) => Lists) {
  for (const [key] of queryClient.getQueriesData<Lists>({ queryKey: [ITEMS] })) {
    queryClient.setQueryData<Lists>(key, (data) => (data ? change(data, key) : data));
  }
}

/** The card of an item is replaced in place in every loaded list: its order is not touched. */
export function replaceInLists(queryClient: QueryClient, updated: ItemSummary) {
  eachList(queryClient, (data) => ({
    ...data,
    pages: data.pages.map((page) => ({ ...page, items: page.items.map((s) => (sameItem(s, updated) ? updated : s)) })),
  }));
}

/** A card of the work the item is of: its own, or that of another item linked to it. */
function ofWork(card: ItemSummary, item: ItemSummary): boolean {
  return sameItem(card, item) || card.work === item.work;
}

/**
 * The card after a grade of an item of its work: the item's own card is replaced; the card of
 * another item of the work takes the grade over — the work is graded, on whichever site.
 */
function graded(card: ItemSummary, updated: ItemSummary): ItemSummary {
  if (sameItem(card, updated)) return updated;
  const { grade, previousGrade, previousGradeVersion } = updated;
  return { ...card, grade, previousGrade, previousGradeVersion };
}

/**
 * A grade changed: a card graded in the "unrated" view leaves it at once, so the next one moves
 * up under the cursor; one ungraded in the "rated" view leaves that. Elsewhere it is replaced.
 */
export function applyGrade(queryClient: QueryClient, updated: ItemSummary) {
  eachList(queryClient, (data, key) => {
    const view = key[2];
    const leaves = (view === "UNRATED" && updated.grade) || (view === "RATED" && !updated.grade);
    return {
      ...data,
      pages: data.pages.map((page) => {
        const had = page.items.some((s) => ofWork(s, updated));
        return {
          total: leaves && had ? page.total - 1 : page.total,
          items: leaves ? page.items.filter((s) => !ofWork(s, updated)) : page.items.map((s) => (ofWork(s, updated) ? graded(s, updated) : s)),
        };
      }),
    };
  });
}

/**
 * A mark of interest changed: the item's card is replaced in place and every other card of its
 * work takes the mark over, as the application writes it to each. The order waits for the model.
 */
export function applyInterest(queryClient: QueryClient, updated: ItemSummary) {
  eachList(queryClient, (data) => ({
    ...data,
    pages: data.pages.map((page) => ({
      ...page,
      items: page.items.map((s) => (sameItem(s, updated) ? updated : ofWork(s, updated) ? { ...s, interest: updated.interest } : s)),
    })),
  }));
}

/** New details of an item from a correction or a download: the dialog and the lists get them at once. */
export function applyDetails(queryClient: QueryClient, details: ItemDetails, asked: ItemRef) {
  queryClient.setQueryData(itemKey(asked), details);
  if (!sameItem(asked, details.summary)) queryClient.setQueryData(itemKey(details.summary), details);
  replaceInLists(queryClient, details.summary);
}
