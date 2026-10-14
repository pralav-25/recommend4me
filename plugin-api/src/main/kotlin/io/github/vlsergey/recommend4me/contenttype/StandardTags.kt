package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.source.FacetRole

/**
 * THE FREE TAGS OF A SITE, every content type's alike — games' and books': the site's word, and the
 * layer of the normalized tags worked out of it. A tag is one of the user's dictionary whichever
 * type's site it is of: "Магия" of a site of books and "Magic" of a site of games may be one.
 */
object StandardTags {
    /** The tags as the site writes them: the site's word, read by the model, the examples of [TAGS]. */
    val TAG = StandardFacet("tag", role = FacetRole.ORIGINAL, searchWeight = 0.9f)

    /**
     * The work's tags: of every tag of every site, one by its name — worked out from everything the
     * site says of the work, its own tags first of all, and corrected by the user.
     */
    val TAGS = StandardFacet("tags", role = FacetRole.CONTENT, searchWeight = 0.9f, infer = true, examples = TAG)
}
