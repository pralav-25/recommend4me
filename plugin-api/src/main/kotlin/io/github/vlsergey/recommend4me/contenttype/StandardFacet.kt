package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.FacetRole
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.NumberScale
import io.github.vlsergey.recommend4me.source.SignalDef

/**
 * A FACET EVERY SOURCE OF A CONTENT TYPE FILLS ALIKE — the author, the status, the tags: one facet
 * for the model, the filters and the card, whichever site a work is from. A source declares its
 * facet as the standard one ([asFacet]) and maps the site's values onto it first of all: of a facet
 * with [values] — the status of a book — only those, by their keys, the application leaving out any
 * other; of a facet without, any value as the site writes it (an author, a tag), the same value of
 * two sites under one key.
 *
 * Its label and the names of its values are words of the interface's standard namespace, by its
 * [key] ([I18nKeys][io.github.vlsergey.recommend4me.i18n.I18nKeys]).
 */
class StandardFacet(
    val key: String,
    /** The only values the facet may have, in their order; null — any. */
    val values: List<StandardValue>? = null,
    val role: FacetRole? = null,
    val filter: Boolean = false,
    val onCard: Boolean = false,
    val names: Boolean = false,
    val searchWeight: Float = 0f,
    val suggest: Boolean = false,
    /** Of the layer ([ContentType.layer]): the values are worked out for every work and given to it ([FacetDef.infer]). */
    val infer: Boolean = false,
    /** Of the layer: the standard facet of the sites whose values are this one's examples, by their names ([FacetDef.examplesFrom]). */
    val examples: StandardFacet? = null,
) {
    /** The value of the key; null when the facet has no such value, or takes any. */
    fun value(key: String): StandardValue? = values?.firstOrNull { it.key == key }

    /** The source's facet [key] as this one: its role and use are the standard's. */
    fun asFacet(key: String = this.key) = FacetDef(
        key,
        filter = filter, shared = this.key, searchWeight = searchWeight, onCard = onCard, names = names, suggest = suggest, role = role,
        standard = this,
    )

    /**
     * This one as a facet of the application's layer over a source: corrected by the user, worked out
     * from [examplesFrom] — the key of the source's own facet of [examples] — and the rest.
     */
    fun asLayerFacet(examplesFrom: String?, originalFacet: String? = examplesFrom) = FacetDef(
        key,
        filter = filter, shared = key, searchWeight = searchWeight, onCard = onCard, names = names, suggest = suggest, infer = infer, role = role,
        standard = this, editable = true, examplesFrom = examplesFrom, originalFacet = originalFacet,
    )
}

/**
 * A value of a [StandardFacet] with a closed list of them, by its stable key. [modelName] is the
 * name the model and the search read it by — one language, whatever the user reads, the texts
 * encoded once; the user is shown it by its key, in the interface's words.
 */
class StandardValue(val key: String, val modelName: String)

/**
 * A NUMBER EVERY SOURCE OF A CONTENT TYPE THAT HAS IT TELLS ALIKE — the chapters of a book: one
 * number for the model and the filters, whichever site a work is from. A source declares its number
 * as the standard one ([asNumber]) under its own key.
 */
class StandardNumber(
    val key: String,
    val scale: NumberScale = NumberScale.LOG,
    val sortable: Boolean = false,
    val filterable: Boolean = false,
    val role: NumberRole? = null,
) {
    /** The source's number [key] as this one: its scale and use are the standard's. */
    fun asNumber(key: String = this.key) = NumberDef(
        key, scale, sortable = sortable, shared = this.key, role = role, filterable = filterable, standard = this,
    )
}

/**
 * A USER'S OWN ACTION ON A SITE EVERY SOURCE OF A CONTENT TYPE TELLS ALIKE — read the work, liked
 * it: one signal for the model and the card, whichever site it was done on. A source sets it under
 * [key] to one of [values], mapping its own marks onto it (a shelf "finished" — read), besides any
 * signal of its own.
 */
class StandardSignal(val key: String, val values: List<StandardValue>) {
    fun asSignal() = SignalDef(key, values.map { it.key }.toSet(), standard = this)
}
