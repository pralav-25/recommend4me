package io.github.vlsergey.recommend4me.layer

/** The values of a facet of the application's layer learnt from the sites' values by their names. */
object LayerValues {
    /** A site's value's [name] as compared to another's: one for "Магия" of one site and "магия " of another. */
    fun keyOf(name: String): String = name.trim().lowercase()
}
