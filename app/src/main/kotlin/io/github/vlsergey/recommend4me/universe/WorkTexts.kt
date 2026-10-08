package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.source.SourceStore

/** A text of a work and where in the work it is: "Описание", "Персонажи на сайте", "Глава 3. Встреча". */
class WorkText(val place: String, val text: String)

/** Every text of a work its characters are counted in, as [workPassages] gives them. */
fun SourceStore.workTexts(itemId: String): Sequence<String> = workPassages(itemId).map { it.text }

/**
 * Every text of a work its characters are counted in, each with where it is: its texts as the
 * user corrected them — the description, the notes, the site's line of its characters — the names
 * of the values the site gives it (a tag "гарри поттер" names Harry), a line each, and every
 * chapter read.
 */
fun SourceStore.workPassages(itemId: String): Sequence<WorkText> {
    val chapters = ArrayList<WorkText>()
    parts.forEachContent(itemId) { title, content -> chapters += WorkText(title?.takeIf { it.isNotBlank() } ?: "Глава ${chapters.size + 1}", content) }
    val labels = schema.texts.associate { it.key to it.label }
    val facetLabels = schema.facets.associate { it.key to it.label }
    val values = items.facets(itemId).flatMap { (facet, keys) -> items.facetNames(facet, keys).values.map { WorkText(facetLabels[facet] ?: facet, it) } }
    return Corrected.texts(items.texts(itemId), corrections.fieldsOf(itemId)).map { (key, text) -> WorkText(labels[key] ?: key, text) }.asSequence() +
        values.asSequence() + chapters.asSequence()
}
