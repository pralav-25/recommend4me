package io.github.vlsergey.recommend4me.search

/**
 * The models of the source's facets are about to be fitted on what its works are now: the search
 * index is brought to them first ([Search.current]) — told by the refresh, heard in its own thread.
 */
data class ModelsRefreshing(val source: String)
