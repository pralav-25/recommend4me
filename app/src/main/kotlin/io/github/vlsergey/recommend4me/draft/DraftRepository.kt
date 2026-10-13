package io.github.vlsergey.recommend4me.draft

import io.github.vlsergey.recommend4me.database.model.tables.references.LAYER_DRAFT
import io.github.vlsergey.recommend4me.universe.LayerDraft
import org.jooq.DSLContext

/**
 * The drafts of the layer of one source's works ([LayerDraft]), in the model database of its
 * content type: made of the site's values and the dictionary, made again whenever either changes —
 * nothing of the user's.
 */
class DraftRepository(private val db: DSLContext, private val source: String) {

    /** The drafts of the items [ids]: item to its drafts. */
    fun ofItems(ids: Collection<String>): Map<String, List<LayerDraft>> {
        val out = HashMap<String, MutableList<LayerDraft>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(LAYER_DRAFT.ITEM_ID, LAYER_DRAFT.FACET, LAYER_DRAFT.VALUE_KEY, LAYER_DRAFT.SITE_VALUE).from(LAYER_DRAFT)
                .where(LAYER_DRAFT.SOURCE.eq(source), LAYER_DRAFT.ITEM_ID.`in`(chunk))
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += LayerDraft(it.value2()!!, it.value3()!!, it.value4()!!) }
        }
        return out
    }

    /** The drafts of every item of the [facets]: item to its drafts. */
    fun ofFacets(facets: Collection<String>): Map<String, List<LayerDraft>> {
        val out = HashMap<String, MutableList<LayerDraft>>()
        if (facets.isEmpty()) return out
        db.select(LAYER_DRAFT.ITEM_ID, LAYER_DRAFT.FACET, LAYER_DRAFT.VALUE_KEY, LAYER_DRAFT.SITE_VALUE).from(LAYER_DRAFT)
            .where(LAYER_DRAFT.SOURCE.eq(source), LAYER_DRAFT.FACET.`in`(facets))
            .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += LayerDraft(it.value2()!!, it.value3()!!, it.value4()!!) }
        return out
    }

    /** Every item's drafts: what a reading of every work is compared with. */
    fun all(): Map<String, List<LayerDraft>> {
        val out = HashMap<String, MutableList<LayerDraft>>()
        db.select(LAYER_DRAFT.ITEM_ID, LAYER_DRAFT.FACET, LAYER_DRAFT.VALUE_KEY, LAYER_DRAFT.SITE_VALUE).from(LAYER_DRAFT)
            .where(LAYER_DRAFT.SOURCE.eq(source))
            .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += LayerDraft(it.value2()!!, it.value3()!!, it.value4()!!) }
        return out
    }

    /** The drafts of the items [drafts] names replaced by these; an item with none is left with none. */
    fun replace(drafts: Map<String, List<LayerDraft>>) = db.transaction { c ->
        val t = c.dsl()
        drafts.keys.chunked(BATCH).forEach { chunk ->
            t.deleteFrom(LAYER_DRAFT).where(LAYER_DRAFT.SOURCE.eq(source), LAYER_DRAFT.ITEM_ID.`in`(chunk)).execute()
        }
        drafts.flatMap { (id, list) -> list.distinctBy { it.facet to it.key }.map { id to it } }.chunked(BATCH).forEach { chunk ->
            t.batch(chunk.map { (id, d) ->
                t.insertInto(LAYER_DRAFT)
                    .set(LAYER_DRAFT.SOURCE, source).set(LAYER_DRAFT.ITEM_ID, id).set(LAYER_DRAFT.FACET, d.facet)
                    .set(LAYER_DRAFT.VALUE_KEY, d.key.take(500)).set(LAYER_DRAFT.SITE_VALUE, d.from.take(1000))
            }).execute()
        }
    }

    /** The drafts of the item removed from the library. */
    fun forget(itemId: String) {
        db.deleteFrom(LAYER_DRAFT).where(LAYER_DRAFT.SOURCE.eq(source), LAYER_DRAFT.ITEM_ID.eq(itemId)).execute()
    }

    companion object {
        private const val BATCH = 1000
    }
}
