package io.github.vlsergey.recommend4me.tag

/**
 * An entry of a catalogue a tag is bound to — a genre, a trope, a sport, a fictional thing — with
 * its names and the line that tells it from its namesakes, by language: every language asked for
 * that has them. [aliases]: the other names it goes by, by language.
 */
class TagEntry(
    /** Stable within the catalogue: "Q305561". */
    val id: String,
    val labels: Map<String, String>,
    val aliases: Map<String, List<String>>,
    val descriptions: Map<String, String>,
    /** The entry's page for the user to look at. */
    val url: String,
)

/**
 * A catalogue the tags of the sites are bound to by the user — Wikidata: an entry of it names the
 * tag in every language of the user, and its other names are the tag's too. The user finds the
 * entry ([find]) and binds the tag to it; the entry's names are read again when asked ([entries]).
 *
 * Implemented by a plugin and declared as a Spring bean. The calls go to the catalogue's service
 * and may take seconds.
 */
interface TagCatalogue {
    /** Stable: the ids of its entries are kept with it; and the namespace of its title, which the plugin carries. */
    val id: String

    /** The entries a text may stand for, the likeliest first — an id of the catalogue, or its page's address, the entry itself. */
    fun find(text: String, languages: List<String>): List<TagEntry>

    /** The entries [ids] with their names in [languages], those the catalogue has. */
    fun entries(ids: Collection<String>, languages: List<String>): List<TagEntry>
}
