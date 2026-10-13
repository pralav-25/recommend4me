package io.github.vlsergey.recommend4me.layer

/** The values of a facet of the application's layer learnt from the sites' values by their names. */
object LayerValues {
    /** The layer's value of a site's value of this [name]: one for "Магия" of one site and "магия " of another. */
    fun keyOf(name: String): String = name.trim().lowercase()

    /** The same, of the tags the user merged ([merges]: a tag to the one it was merged into): "Angst" and "Ангст" merged, one. */
    fun keyOf(name: String, merges: Map<String, String>): String = keyOf(name).let { merges[it] ?: it }
}
