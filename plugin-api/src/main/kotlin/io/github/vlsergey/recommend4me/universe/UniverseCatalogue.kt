package io.github.vlsergey.recommend4me.universe

/**
 * THE TEXTS OF AN ENTRY ARE BY LANGUAGE — language to text, of every language the catalogue was
 * asked for — and what is shown is the text of the first of [languages], the user's, that has one.
 * The texts of every language asked are kept: other languages of the user need no new answer of
 * the catalogue for those already asked.
 */
private fun <T> Map<String, T>.first(languages: List<String>): T? = languages.firstNotNullOfOrNull { this[it] }

/**
 * An entry of a catalogue of fictional universes: a universe, a franchise, a series, a work —
 * whatever the catalogue holds that fan fiction is written about — with its name and a line that
 * tells it from its namesakes, by language, shown in the first of [languages] that has them.
 */
class UniverseEntry(
    /** Stable within the catalogue: "Q8337". */
    val id: String,
    val labels: Map<String, String>,
    val descriptions: Map<String, String>,
    /** The entry's page for the user to look at. */
    val url: String,
    val languages: List<String>,
) {
    val name: String get() = labels.first(languages) ?: id
    val description: String? get() = descriptions.first(languages)
}

/**
 * A character of a universe: its main name and every other name it goes by, by language — authors
 * write the one they like — and what it is, by language; shown in the first of [languages] that has
 * them. What the catalogue says it is in [classes] (a human, a wizard, a shop, a spell): a universe
 * holds more than its characters.
 */
class UniverseCharacter(
    val id: String,
    val labels: Map<String, String>,
    val aliases: Map<String, List<String>>,
    val descriptions: Map<String, String>,
    val url: String,
    val languages: List<String>,
    /** The ids of the catalogue's classes the entry is of; none when the catalogue does not say. */
    val classes: List<String> = emptyList(),
    /** The character's sex or gender as the catalogue gives it; null when it does not say. */
    val sex: CharacterSex? = null,
    /**
     * The catalogue's statements on the character the plugins asked for ([Source.catalogueProperties][io.github.vlsergey.recommend4me.source.Source.catalogueProperties]),
     * by property — "P8419", its tag on Archive of Our Own — every value as the catalogue gives it:
     * an entry by its id, any other value as its text.
     */
    val properties: Map<String, List<String>> = emptyMap(),
) {
    /** Every name in [languages]: the labels, the user's language first, then the other names. */
    val names: List<String> = (languages.mapNotNull { labels[it] } + languages.flatMap { aliases[it].orEmpty() }).distinct()
    val description: String? get() = descriptions.first(languages)
}

/**
 * A character's sex or gender: male, female, or anything else the catalogue says — agender,
 * transgender, non-binary, genderless.
 */
enum class CharacterSex { MALE, FEMALE, OTHER }

/**
 * A class of the catalogue's entries, named by language, shown in the first of [languages] that
 * has a name. [character]: its entries are characters — people, creatures, beings a story is
 * about — rather than places, groups, things, spells or events.
 */
class UniverseClass(val id: String, val labels: Map<String, String>, val character: Boolean, val languages: List<String>) {
    val name: String get() = labels.first(languages) ?: id
}

/**
 * A catalogue of fictional universes and their characters — Wikidata, kept by people and kept up
 * to date. The application asks it for the universes a fandom of a site may be, and for the
 * characters of the universes the user linked a fandom to; what it answers is kept and asked for
 * again when the user wants it fresh, or reads in languages it was not asked in.
 *
 * Every text is answered in each of the languages asked for that has it.
 *
 * Implemented by a plugin and declared as a Spring bean. The calls go to the catalogue's service
 * and may take seconds.
 */
interface UniverseCatalogue {
    /** Stable: the ids of its entries are kept with it; and the namespace of its title, which the plugin carries ([I18nKeys][io.github.vlsergey.recommend4me.i18n.I18nKeys]). */
    val id: String

    /** The entries a name of a fandom may stand for, the likeliest first. */
    fun findUniverses(name: String, languages: List<String>): List<UniverseEntry>

    fun universe(id: String, languages: List<String>): UniverseEntry?

    /**
     * Every entry said to be in the universe [id] and in the works, series and franchises within
     * it, with its classes — the characters and whatever else the catalogue puts there — and its
     * statements of [properties], the ones the plugins asked for.
     */
    fun characters(id: String, languages: List<String>, properties: Set<String> = emptySet()): List<UniverseCharacter>

    /** The classes [ids], named, each with whether its entries are characters; none of a catalogue without classes. */
    fun classes(ids: Collection<String>, languages: List<String>): List<UniverseClass> = emptyList()
}
