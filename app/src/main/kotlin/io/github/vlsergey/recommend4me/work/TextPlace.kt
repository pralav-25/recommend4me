package io.github.vlsergey.recommend4me.work

/**
 * WHERE IN A WORK A TEXT IS, as the interface words it: a text or a facet of it by the key of its
 * label ([labelKey]); a chapter by its title as the data has it ([title]), or by its number among
 * the chapters read when it has none ([part], 1-based) — its words, "Part {{n}}", are the interface's.
 */
data class TextPlace(val labelKey: String? = null, val title: String? = null, val part: Int? = null)
