package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.i18n.Label
import io.github.vlsergey.recommend4me.source.NumberRole
import io.github.vlsergey.recommend4me.source.NumberScale

/** The numbers of every content type: what any site of any kind of works may tell alike. */
object StandardNumbers {
    /**
     * How many other people liked the work — a site's likes, its kudos, its "liked it": one number
     * to sort and compare by across the sites, whatever each calls it.
     */
    val LIKES = StandardNumber("likes", Label("Likes", "Лайки", "Lajkovi"), NumberScale.LOG, sortable = true, role = NumberRole.APPROVAL)
}
