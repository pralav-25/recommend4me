package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.i18n.text
import io.github.vlsergey.recommend4me.source.SourceStore

/** The name of a chapter without a title of its own, by its number. */
private val CHAPTER = Label("Chapter %d", "Глава %d", "Poglavlje %d")

/** A text of a work and where in the work it is, in the language of the request: "Summary", "Characters on the site", "Chapter 3. The meeting". */
class WorkText(val place: String, val text: String)

/** The names of values of a facet: of the facet, of the keys asked for at least. */
typealias FacetNames = (facet: String, keys: Collection<String>) -> Map<String, String>

/** Every text of a work its characters are counted in, as [workPassages] gives them. */
fun SourceStore.workTexts(itemId: String, names: FacetNames = items::facetNames): Sequence<String> = workPassages(itemId, names).map { it.text }

/**
 * Every text of a work its characters are counted in, each with where it is: its texts as the
 * user corrected them — the description, the notes, the site's line of its characters — the names
 * of the values the site gives it (a tag "гарри поттер" names Harry), a line each, and every
 * chapter read. [names]: where the values' names are read — the store, or a reading of every
 * work's that has them all ([everyName]).
 */
fun SourceStore.workPassages(itemId: String, names: FacetNames = items::facetNames): Sequence<WorkText> {
    val chapters = ArrayList<WorkText>()
    parts.forEachContent(itemId) { title, content -> chapters += WorkText(title?.takeIf { it.isNotBlank() } ?: CHAPTER.text().format(chapters.size + 1), content) }
    val labels = schema.texts.associate { it.key to it.label.text() }
    val facetLabels = schema.facets.associate { it.key to it.label.text() }
    val values = items.facets(itemId).flatMap { (facet, keys) ->
        names(facet, keys).let { named -> keys.mapNotNull { named[it] } }.map { WorkText(facetLabels[facet] ?: facet, it) }
    }
    return Corrected.texts(items.texts(itemId), corrections.fieldsOf(itemId)).map { (key, text) -> WorkText(labels[key] ?: key, text) }.asSequence() +
        values.asSequence() + chapters.asSequence()
}

/**
 * The names of every value of the source's facets, each facet read whole once, when first asked
 * for: for the texts of every work of one reading. Not kept past it — a reading of every work asks
 * for the names of most values, and a query of each work's would read them again a work at a time.
 */
fun SourceStore.everyName(): FacetNames {
    val read = HashMap<String, Map<String, String>>()
    return { facet, _ -> read.getOrPut(facet) { names(facet) } }
}
