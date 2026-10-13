package io.github.vlsergey.recommend4me.screen

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

/**
 * THE SCREENS OF THE INTERFACE ARE ADDRESSES of their own — the works at "/", the universes at
 * "/universes", the tags at "/tags", one of them below — so a screen is a link, opened in a tab of
 * its own and kept in the history. Each is the same page; the interface shows the screen its
 * address names.
 */
@Controller
class Screens {
    @GetMapping("/universes", "/universes/**", "/tags", "/tags/**")
    fun screen(): String = "forward:/index.html"
}
