package io.github.vlsergey.recommend4me.language

import io.github.vlsergey.recommend4me.contenttype.StandardLanguages
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.settings.Settings
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant
import kotlin.concurrent.withLock

private val log = LoggerFactory.getLogger(LanguageAnswers::class.java)

/**
 * THE LANGUAGES OF THE WORKS KNOWN, GIVEN ONCE AS THE USER'S ANSWERS (2026-10-15). The language of a
 * work became a facet of the layer the model works out ([StandardLanguages]), learnt from the
 * user's answers; what the sites said of the works known then was given as if the user had
 * answered — the user's word: of a site that states the language of a work (Archive of Our Own,
 * F95zone), that language; of a site of works in Russian alone that states none (ficbook,
 * Author.Today), Russian, the model left to learn a work of it is in Russian. A work the user
 * answered on is left as it is. Once, after the kept pages are read again ([Captures.reparse]
 * [io.github.vlsergey.recommend4me.capture.Captures.reparse]): marked in the settings.
 */
@Component
class LanguageAnswers(private val stores: Stores, private val settings: Settings, private val events: ApplicationEventPublisher) {

    @Order(2)
    @EventListener(ApplicationReadyEvent::class)
    fun give() {
        giveDescriptions()
        if (settings.get(DONE) != null) return
        stores.sources.forEach { s ->
            try {
                give(s)
            } catch (e: Exception) {
                log.error("{}: the languages of the works were not given", s.id, e)
                return
            }
        }
        settings.set(DONE, Instant.now().toString())
    }

    private fun give(s: SourceStore) {
        val def = s.schema.facet(StandardLanguages.KEY) ?: return
        val site = s.schema.facets.firstOrNull { it.standard === StandardLanguages.LANGUAGE }?.key
        val languages = HashMap<String, List<String>>()
        if (s.id in RUSSIAN) s.items.keys().forEach { languages[it.id] = listOf(RUSSIAN_CODE) }
        else if (site != null) s.items.forEachFacet(site) { id, keys ->
            val codes = keys.mapNotNull(StandardLanguages::code).distinct()
            // Of a book, one language: a site states one
            if (codes.isNotEmpty()) languages[id] = if (def.single) codes.take(1) else codes
        }
        val answered = s.corrections.allFacets().filter { it.facet == def.key }.map { it.itemId }.toSet()
        val given = languages.filterKeys { it !in answered }
        if (given.isEmpty()) return
        val now = Instant.now()
        s.writing.withLock { given.forEach { (id, codes) -> codes.forEach { s.corrections.setFacet(id, def.key, it, true, null, now) } } }
        log.info("{}: the languages of {} works given as the user's answers", s.id, given.size)
        events.publishEvent(ItemsChanged(s.id, given.keys.toList()))
    }

    /**
     * THE LANGUAGE OF THE DESCRIPTIONS OF THE WORKS KNOWN, GIVEN ONCE AS THE USER'S ANSWERS
     * (2026-10-16): of every source whose works have it ([StandardLanguages.DESCRIPTION]) — the
     * games, whose pages are written in English — English, by the user's word. A work the user
     * answered on is left as it is. Once: marked in the settings.
     */
    private fun giveDescriptions() {
        if (settings.get(DESCRIPTIONS_DONE) != null) return
        stores.sources.forEach { s ->
            val def = s.schema.facet(StandardLanguages.DESCRIPTION_KEY) ?: return@forEach
            try {
                val answered = s.corrections.allFacets().filter { it.facet == def.key }.map { it.itemId }.toSet()
                val given = s.items.keys().map { it.id }.filter { it !in answered }
                if (given.isEmpty()) return@forEach
                val now = Instant.now()
                s.writing.withLock { given.forEach { s.corrections.setFacet(it, def.key, ENGLISH_CODE, true, null, now) } }
                log.info("{}: the language of the descriptions of {} works given as the user's answers", s.id, given.size)
                events.publishEvent(ItemsChanged(s.id, given))
            } catch (e: Exception) {
                log.error("{}: the language of the descriptions was not given", s.id, e)
                return
            }
        }
        settings.set(DESCRIPTIONS_DONE, Instant.now().toString())
    }

    companion object {
        private const val DONE = "languages.answered"
        private const val DESCRIPTIONS_DONE = "description-languages.answered"
        private const val ENGLISH_CODE = "en"
        private const val RUSSIAN_CODE = "ru"

        /** The sites of works in Russian alone, which state no language. */
        private val RUSSIAN = setOf("ficbook", "author.today")
    }
}
