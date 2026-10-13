package io.github.vlsergey.recommend4me.i18n

import io.github.vlsergey.recommend4me.source.Source
import tools.jackson.databind.json.JsonMapper

/**
 * THE CHECK OF A PLUGIN'S TRANSLATIONS, for its tests: its namespace's files —
 * `i18n/<namespace>.<language>.json` among its resources, one flat object of a key to its text —
 * in every language of the interface ([I18nKeys.LANGUAGES]), each with exactly the same keys, none
 * blank; and of a source, every key it needs ([I18nKeys.requiredKeys]).
 */
object Translations {
    private val json = JsonMapper.builder().build()

    /** The translations of [namespace] in [language], as the plugin carries them; null when it carries none. */
    fun read(namespace: String, language: String, loader: ClassLoader): Map<String, String>? =
        loader.getResourceAsStream("i18n/$namespace.$language.json")?.use { stream ->
            @Suppress("UNCHECKED_CAST")
            json.readValue(stream, Map::class.java) as Map<String, String>
        }

    /** What is wrong with the translations of [namespace] that must have [required]; empty when nothing is. */
    fun problems(namespace: String, required: Set<String>, loader: ClassLoader): List<String> {
        val out = ArrayList<String>()
        val bundles = I18nKeys.LANGUAGES.associateWith { read(namespace, it, loader) }
        bundles.filterValues { it == null }.keys.forEach { out += "no i18n/$namespace.$it.json" }
        val present = bundles.filterValues { it != null }.mapValues { it.value!! }
        val first = I18nKeys.LANGUAGES.first()
        present.forEach { (language, texts) ->
            (required - texts.keys).forEach { out += "$language: missing \"$it\"" }
            texts.filterValues { it.isBlank() }.keys.forEach { out += "$language: blank \"$it\"" }
            texts.keys.filter { ':' in it }.forEach { out += "$language: \"$it\" holds \":\"" }
            present[first]?.let { reference ->
                if (language != first) {
                    (reference.keys - texts.keys).forEach { out += "$language: missing \"$it\" that $first has" }
                    (texts.keys - reference.keys).forEach { out += "$language: \"$it\" that $first has not" }
                }
            }
        }
        return out
    }

    /** What is wrong with the translations of [source]: of its namespace, its id. */
    fun problems(source: Source): List<String> =
        problems(source.id, I18nKeys.requiredKeys(source), source.javaClass.classLoader)

    /** What is wrong with the translations of a plugin of one title alone — a scorer, a catalogue. */
    fun titleProblems(namespace: String, loader: ClassLoader): List<String> = problems(namespace, setOf(I18nKeys.TITLE), loader)
}
