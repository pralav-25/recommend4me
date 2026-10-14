package io.github.vlsergey.recommend4me.screen

import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.server.ResponseStatusException

/**
 * THE SCREENS OF THE INTERFACE ARE ADDRESSES of their own — the works of a content type at
 * "/books", the universes at "/universes", the tags of every site at "/tags", one of them below —
 * so a screen is a link, opened in a tab of its own and kept in the history. Each is the same page;
 * the interface shows the screen its address names.
 */
@Controller
class Screens(private val stores: Stores) {
    @GetMapping("/universes", "/universes/**", "/tags", "/tags/**")
    fun screen(): String = PAGE

    /** The works of a type: of a known one only — "/favicon.ico" and the like are files. */
    @GetMapping("/{type:[a-z][a-z0-9-]*}")
    fun works(@PathVariable type: String): String {
        if (stores.type(type) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND, "There is no type '$type'")
        return PAGE
    }

    private companion object {
        const val PAGE = "forward:/index.html"
    }
}
