package io.github.vlsergey.recommend4me.i18n

import io.github.vlsergey.recommend4me.plugin.Plugins
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * THE WORDS OF THE INTERFACE, a namespace in a language ([I18nKeys]), for the interfaces to read:
 * the web interface and the browser extension. Not a part of the JSON API — the files as they are:
 *
 * - "standard" — the application's own words, which the web interface's build lays out among its
 *   pages (`static/i18n/standard/<language>.json`);
 * - a plugin's id — a source's, a scorer's, a catalogue's — the words the plugin carries among its
 *   resources, `i18n/<id>.<language>.json`, read by the plugin's own class loader.
 *
 * Read anew on every request: the plugins' jars and the build are the only copy of them.
 */
@RestController
class TranslationsController(private val plugins: Plugins) {

    @GetMapping("/i18n/{namespace}/{language}.json")
    fun translations(@PathVariable namespace: String, @PathVariable language: String): ResponseEntity<ByteArray> {
        if (!NAME.matches(namespace) || language !in I18nKeys.LANGUAGES) return ResponseEntity.notFound().build()
        val bytes = if (namespace == I18nKeys.STANDARD) {
            javaClass.classLoader.getResourceAsStream("static/i18n/${I18nKeys.STANDARD}/$language.json")?.use { it.readBytes() }
        } else {
            pluginOf(namespace)?.javaClass?.classLoader?.getResourceAsStream("i18n/$namespace.$language.json")?.use { it.readBytes() }
        } ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok().contentType(JSON).cacheControl(CacheControl.noCache()).body(bytes)
    }

    /** The plugin whose id is [namespace]: a source, a scorer, a catalogue of universes, or any other kind with words of its own. */
    private fun pluginOf(namespace: String): Any? =
        plugins.sources.firstOrNull { it.id == namespace }
            ?: plugins.scorers.firstOrNull { it.id == namespace }
            ?: plugins.catalogues.firstOrNull { it.id == namespace }
            ?: plugins.suggester?.takeIf { it.id == namespace }
            ?: plugins.search?.takeIf { it.id == namespace }
            ?: plugins.textEncoders.firstOrNull { it.id == namespace }
            ?: plugins.imageEncoders.firstOrNull { it.id == namespace }
            ?: plugins.rerankers.firstOrNull { it.id == namespace }

    companion object {
        /** A namespace is an id — "author.today" among them: dots between its words, no path within it. */
        private val NAME = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")
        private val JSON = MediaType("application", "json", Charsets.UTF_8)
    }
}
