package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.NumberScale

/**
 * The numbers of every content type: what any site of any kind of works may tell alike — one
 * number to sort, filter and compare by across the sites, whatever each calls it.
 */
object StandardNumbers {
    /** How many other people liked the work: a site's likes, its kudos, its "liked it". */
    val LIKES = StandardNumber("likes", NumberScale.LOG, sortable = true, role = NumberRole.APPROVAL)

    /** How many times the work was opened: a site's views, its hits. */
    val VIEWS = StandardNumber("views", NumberScale.LOG, sortable = true, role = NumberRole.REACH)

    /** How long the work's text is, in words. A count of characters is another number. */
    val WORDS = StandardNumber("words", NumberScale.LOG, sortable = true, role = NumberRole.SIZE)
}
