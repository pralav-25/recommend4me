package io.github.vlsergey.recommend4me.i18n

import io.github.vlsergey.recommend4me.contenttype.Books
import io.github.vlsergey.recommend4me.contenttype.BuiltInTypes
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.refusal.Refusal
import io.github.vlsergey.recommend4me.source.SourceSchema
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * EVERY KEY OF THE STANDARD NAMESPACE THE SERVER SENDS HAS ITS WORDS: the web interface keeps them
 * (`frontend/src/standard/standard-i18n.en.json`; its compiler checks the other languages against
 * the English). A key the server sends and the interface lacks would be shown as the key itself.
 */
class StandardKeysTest {

    /** The English words of the standard namespace, beside the module. */
    private val file = Path.of("../frontend/src/standard/standard-i18n.en.json").toAbsolutePath().normalize()

    /** Every key of the standard namespace the server can send. */
    private fun sent(): Set<String> = buildSet {
        val types = listOf(Books.TYPE, BuiltInTypes.GAMES)
        types.forEach { t ->
            add(I18nKeys.typeTitle(t.id))
            add(I18nKeys.typeVerb(t.id))
            (1..5).forEach { add(I18nKeys.typeGrade(t.id, it)) }
            (t.facets + t.layer).forEach { f ->
                val def = f.asFacet()
                add(I18nKeys.facetLabel(SOURCE, def))
                f.values.orEmpty().forEach { v -> add(I18nKeys.standardValue(def, v.key)!!) }
            }
            t.numbers.forEach { add(I18nKeys.numberLabel(SOURCE, it.asNumber())) }
            t.signals.forEach { s ->
                val def = s.asSignal()
                add(I18nKeys.signalLabel(SOURCE, def))
                def.values.forEach { add(I18nKeys.signalValueLabel(SOURCE, def, it)) }
            }
        }
        // The application's layer over a source of a type with universes, and its standard tags
        (UniverseFacet.defs(SourceSchema()) + Books.TYPE.layer.map { it.asLayerFacet(null) }).forEach { def ->
            add(I18nKeys.facetLabel(SOURCE, def))
            def.standard?.values.orEmpty().forEach { v -> add(I18nKeys.standardValue(def, v.key)!!) }
        }
        UniverseFacet.ORIGINAL_CHARACTERS.keys.forEach { add(UniverseFacet.originalCharacterKey(it)!!) }
        addAll(FeatureNames.STANDARD_KEYS)
        Refusal.entries.forEach { add(it.key) }
    }

    @Test
    fun `every standard key the server sends is among the interface's words`() {
        assertTrue(Files.isRegularFile(file), "no $file")
        @Suppress("UNCHECKED_CAST")
        val words = JsonMapper.builder().build().readValue(file.toFile(), Map::class.java).keys as Set<String>
        val keys = sent()
        // Every one of the standard namespace: none of a source's slipped in
        val foreign = keys.filter { !it.startsWith(I18nKeys.STANDARD + ":") }
        assertTrue(foreign.isEmpty(), "not of the standard namespace: $foreign")
        val missing = keys.map { it.removePrefix(I18nKeys.STANDARD + ":") }.filter { it !in words }.sorted()
        assertTrue(missing.isEmpty(), "missing in $file: $missing")
    }

    private companion object {
        /** A source of no words of its own: its standard things are labelled in the standard namespace. */
        const val SOURCE = "any"
    }
}
