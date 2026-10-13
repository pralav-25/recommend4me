import { label } from "@/i18n/label";

/**
 * Where in a work something was found, as the server tells it: a text or a facet by the key of its
 * label, or a chapter — by its title as the data has it, by its number when it has none.
 */
export type Place = { labelKey?: string; title?: string; part?: number };

/** The words of a place: "Description", "Chapter one", "Part 3". */
export function placeName(place: Place): string {
  if (place.labelKey) return label(place.labelKey);
  if (place.title) return place.title;
  if (place.part !== undefined) return label("standard:part.untitled", { n: place.part });
  return "";
}
