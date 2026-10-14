package io.github.vlsergey.recommend4me.history

import io.github.vlsergey.recommend4me.api.HistoryApi
import io.github.vlsergey.recommend4me.api.model.HistoryEntry
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.source.Stores
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

/**
 * THE USER'S HISTORY: every grade and mark of interest as it stands, of every source of every type,
 * the newest first — a grade given by mistake, its work gone from the list it was given in, is found
 * here and taken back in the work's window. One read of each source's grades, marks and titles.
 */
@RestController
class HistoryController(private val stores: Stores) : HistoryApi {

    override fun listHistory(limit: Int): ResponseEntity<List<HistoryEntry>> {
        val entries = stores.types.flatMap { type ->
            type.sources.flatMap { s ->
                val grades = s.ratings.all().map { r ->
                    Triple(r.itemId, r.ratedAt) { title: String ->
                        HistoryEntry(type.id, s.id, r.itemId, title, HistoryEntry.Kind.GRADE, r.ratedAt.atOffset(ZoneOffset.UTC), grade = r.grade, version = r.version)
                    }
                }
                val marks = s.interest.all().values.map { m ->
                    Triple(m.itemId, m.markedAt) { title: String ->
                        HistoryEntry(type.id, s.id, m.itemId, title, HistoryEntry.Kind.INTEREST, m.markedAt.atOffset(ZoneOffset.UTC), interesting = m.interesting)
                    }
                }
                val latest = (grades + marks).sortedByDescending { it.second }.take(limit.coerceIn(1, 1000))
                if (latest.isEmpty()) return@flatMap emptyList()
                val heads = s.items.heads(latest.map { it.first }.toSet())
                val fields = s.corrections.allFields()
                latest.map { (id, at, entry) -> at to entry(heads[id]?.let { Corrected.title(it.title, fields[id].orEmpty()) } ?: id) }
            }
        }
        return ResponseEntity.ok(entries.sortedByDescending { it.first }.take(limit.coerceIn(1, 1000)).map { it.second })
    }
}
