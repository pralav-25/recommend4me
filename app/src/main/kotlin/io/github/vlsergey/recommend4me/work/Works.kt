package io.github.vlsergey.recommend4me.work

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.vector.Vectors
import org.springframework.stereotype.Component

/**
 * Which items are one work: the links the user made within sources and across them, closed under
 * "is the same as". An item of no link is a work alone. Read at the start of every operation that
 * needs it — the links are few, made by hand.
 */
class WorkClusters(private val clusters: Map<ItemKey, List<ItemKey>>) {

    /** The items that are the same work as [key], [key] first; the item alone when it has no link. */
    fun members(key: ItemKey): List<ItemKey> = clusters[key]?.let { listOf(key) + (it - key) } ?: listOf(key)

    /** The work an item is of: the same number for every item of a work. */
    fun work(key: ItemKey): Long = Vectors.keyOf(representative(key).toString())

    /** The item that names the work: the first of its members by key. Every member keeps its own card. */
    fun representative(key: ItemKey): ItemKey = clusters[key]?.first() ?: key

    fun linked(key: ItemKey): Boolean = key in clusters

    /** Every work of more than one item: its items. */
    fun linkedWorks(): List<List<ItemKey>> = clusters.values.distinct()

    companion object {
        fun of(links: List<Pair<ItemKey, ItemKey>>): WorkClusters {
            val parent = HashMap<ItemKey, ItemKey>()
            fun find(x: ItemKey): ItemKey {
                var r = x
                while (parent[r] != null && parent[r] != r) r = parent.getValue(r)
                return r
            }
            links.forEach { (a, b) ->
                parent.putIfAbsent(a, a)
                parent.putIfAbsent(b, b)
                val ra = find(a)
                val rb = find(b)
                if (ra != rb) parent[ra] = rb
            }
            val groups = parent.keys.groupBy(::find).values.map { members -> members.sortedBy { it.toString() } }
            return WorkClusters(groups.flatMap { members -> members.map { it to members } }.toMap())
        }
    }
}

@Component
class Works {
    /** The clusters of a content type: the links across its sources and within each. */
    fun of(type: TypeStore): WorkClusters =
        WorkClusters.of(type.links.links() + type.sources.flatMap { s -> s.corrections.links().map { (a, b) -> ItemKey(s.id, a) to ItemKey(s.id, b) } })
}
