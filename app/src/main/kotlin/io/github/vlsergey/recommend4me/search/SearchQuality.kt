package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.stereotype.Component
import io.github.vlsergey.recommend4me.api.model.QueryQuality as ApiQueryQuality
import io.github.vlsergey.recommend4me.api.model.QueryRanks as ApiQueryRanks
import io.github.vlsergey.recommend4me.api.model.SearchQuality as ApiSearchQuality
import io.github.vlsergey.recommend4me.api.model.SearchQualitySum as ApiSearchQualitySum

/**
 * HOW WELL THE SEARCH FINDS WHAT THE USER JUDGED: every line the user said of the works found which
 * answer it and which do not ([SearchJudgmentRepository]) searched again — with the reranker and
 * without — and where the works judged stand. The works not judged are counted apart, neither
 * fitting nor not: a search that brings up works never seen is not told better or worse by them.
 */
@Component
class SearchQuality(private val stores: Stores, private val search: Search) {

    /** The measure of the type's search over the first [top] works found; null for no such type. */
    fun measure(typeId: String, top: Int): ApiSearchQuality? {
        val type = stores.type(typeId) ?: return null
        val rows = type.searchJudgments.all().toSortedMap().map { (query, judged) ->
            fun ranks(rerank: Boolean) = ranks(search.search(typeId, query, rerank)?.keys.orEmpty(), judged, top)
            ApiQueryQuality(query, judged.count { it.value }, judged.count { !it.value }, ranks(true), ranks(false))
        }
        return ApiSearchQuality(top, rows, sum(rows.map { it.reranked }, rows), sum(rows.map { it.plain }, rows))
    }

    private fun ranks(found: List<ItemKey>, judged: Map<ItemKey, Boolean>, top: Int): ApiQueryRanks {
        val first = found.indexOfFirst { judged[it] == true }.takeIf { it >= 0 }?.plus(1)
        val head = found.take(top)
        return ApiQueryRanks(
            relevantInTop = head.count { judged[it] == true },
            irrelevantInTop = head.count { judged[it] == false },
            unjudgedInTop = head.count { it !in judged },
            relevantFound = found.count { judged[it] == true },
            firstRelevant = first,
        )
    }

    private fun sum(ranks: List<ApiQueryRanks>, rows: List<ApiQueryQuality>) = ApiSearchQualitySum(
        meanReciprocalRank = if (ranks.isEmpty()) 0.0 else ranks.sumOf { r -> r.firstRelevant?.let { 1.0 / it } ?: 0.0 } / ranks.size,
        relevantInTop = ranks.sumOf { it.relevantInTop },
        irrelevantInTop = ranks.sumOf { it.irrelevantInTop },
        unjudgedInTop = ranks.sumOf { it.unjudgedInTop },
        relevantFound = ranks.sumOf { it.relevantFound },
        relevant = rows.sumOf { it.relevant },
    )
}
