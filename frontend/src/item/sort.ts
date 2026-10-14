import type { ContentTypeInfo, ItemSort, NumberInfo } from "@/api/client";

/**
 * The order of the list: by the formula — the prediction times the chance of interest — by
 * interest first, by the prediction of the grade first, by the date of the update, by the date the
 * application first saw the work, by surprise, or by one of the numbers of the content type.
 */
export type SortChoice = { sort: ItemSort; number?: string };

export const BY_SCORE: SortChoice = { sort: "SCORE" };

/** The orders of their own, in the order they are offered; the numbers come after them. */
export const ORDERS = ["SCORE", "INTEREST", "GRADE", "UPDATED", "ADDED", "SURPRISE"] as const satisfies readonly ItemSort[];

/** A remembered order the content type no longer has — a number gone, an order unknown — falls back to the prediction. */
export function validSort(choice: SortChoice, type: ContentTypeInfo): SortChoice {
  if (choice.sort === "NUMBER") return type.numbers.some((n) => n.id === choice.number) ? choice : BY_SCORE;
  return (ORDERS as readonly ItemSort[]).includes(choice.sort) ? choice : BY_SCORE;
}

/** The numbers the list can be sorted by: the standard ones — every site's alike — first, each part in its own order. */
export function sortNumbers(type: ContentTypeInfo): NumberInfo[] {
  const standard = (n: NumberInfo) => n.labelKey.startsWith("standard:");
  return [...type.numbers.filter(standard), ...type.numbers.filter((n) => !standard(n))];
}
