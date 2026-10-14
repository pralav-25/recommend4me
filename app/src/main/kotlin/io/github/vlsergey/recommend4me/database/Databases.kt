package io.github.vlsergey.recommend4me.database

import io.github.vlsergey.recommend4me.folder.DataFolder
import io.github.vlsergey.recommend4me.plugin.Plugins
import org.jooq.DSLContext
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/** The three files of a source: what the site says, what the user corrected, what the user graded. */
class SourceDatabases(val source: Database, val corrections: Database, val ratings: Database)

/** The two files of a content type: the links across its sources, and what is learnt. */
class TypeDatabases(val corrections: Database, val model: Database)

/**
 * Every database file of every source and content type, opened and migrated on start and held
 * open until the end: an open file is a pool of connections, not a copy of its data.
 */
@Component
class Databases(folder: DataFolder, plugins: Plugins) : DisposableBean {

    private val sources: Map<String, SourceDatabases> = plugins.sources.associate { s ->
        val dir = folder.source(s.id)
        s.id to SourceDatabases(
            source = Database("${s.id}/source", dir.resolve("source"), "source", s.migrations),
            corrections = Database("${s.id}/corrections", dir.resolve("corrections"), "corrections"),
            ratings = Database("${s.id}/ratings", dir.resolve("ratings"), "ratings"),
        )
    }

    private val types: Map<String, TypeDatabases> = plugins.types.associate { t ->
        val dir = folder.type(t.id)
        t.id to TypeDatabases(
            corrections = Database("${t.id}/corrections", dir.resolve("corrections"), "typecorrections"),
            model = Database("${t.id}/model", dir.resolve("model"), "model"),
        )
    }

    fun of(sourceId: String): SourceDatabases = sources[sourceId] ?: error("No such source: $sourceId")

    fun source(sourceId: String): DSLContext = of(sourceId).source.dsl
    fun corrections(sourceId: String): DSLContext = of(sourceId).corrections.dsl
    fun ratings(sourceId: String): DSLContext = of(sourceId).ratings.dsl

    fun type(typeId: String): TypeDatabases = types[typeId] ?: error("No such content type: $typeId")

    fun model(typeId: String): DSLContext = type(typeId).model.dsl
    fun typeCorrections(typeId: String): DSLContext = type(typeId).corrections.dsl

    /** Every database file. */
    fun all(): List<Database> =
        sources.values.flatMap { listOf(it.source, it.corrections, it.ratings) } + types.values.flatMap { listOf(it.corrections, it.model) }

    override fun destroy() {
        sources.values.forEach { listOf(it.source, it.corrections, it.ratings).forEach(Database::close) }
        types.values.forEach { listOf(it.corrections, it.model).forEach(Database::close) }
    }
}
