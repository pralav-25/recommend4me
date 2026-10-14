package io.github.vlsergey.recommend4me.dictionary

import io.github.vlsergey.recommend4me.database.Database
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.SITE_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.UNIVERSE
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The tags and universes of a type's files moved to the dictionaries common to every type. */
class DictionaryMoveTest {

    @TempDir
    lateinit var folder: Path

    /** A type's corrections as they were before the move: every migration up to the last that had the tags. */
    private fun oldCorrections(name: String): DSLContext {
        val connection = DriverManager.getConnection("jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1")
        val db = DSL.using(connection, SQLDialect.H2)
        (1..18).forEach { v ->
            val file = javaClass.classLoader.getResource("db/typecorrections")!!.let { Path.of(it.toURI()) }
                .toFile().listFiles { f -> f.name.startsWith("V${v}__") }!!.single()
            db.execute("RUNSCRIPT FROM '${file.absolutePath.replace('\\', '/')}' CHARSET 'UTF-8'")
        }
        return db
    }

    @Test
    fun `a type's tags and universes are moved once, numbered on after them`() {
        val old = oldCorrections("books")
        old.execute("INSERT INTO normalized_tag (id, name, ignored) VALUES (7, 'Hulls', TRUE)")
        old.execute("INSERT INTO site_tag (source, tag, normalized, bound_at) VALUES ('site', 'hulls', 7, CURRENT_TIMESTAMP)")
        old.execute("INSERT INTO universe (catalogue, universe_id, name, url, added_at, refreshed_at) VALUES ('wikidata', 'Q1', 'Space', 'u', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)")
        Database("dictionaries", folder.resolve("dictionaries"), "dictionaries").use { dictionaries ->
            val to = dictionaries.dsl
            DictionaryMove.copy("books/corrections", old, to, DictionaryMove.CORRECTIONS)
            // Started again before the type's tables were dropped: not copied twice
            DictionaryMove.copy("books/corrections", old, to, DictionaryMove.CORRECTIONS)
            assertEquals(listOf(Triple(7L, "Hulls", true)), to.selectFrom(NORMALIZED_TAG).fetch { Triple(it.id, it.name, it.ignored) })
            assertEquals(listOf(7L), to.selectFrom(SITE_TAG).fetch { it.normalized })
            assertEquals(listOf("Space"), to.selectFrom(UNIVERSE).fetch { it.name })
            // A tag made now is numbered after the moved ones
            val made = to.insertInto(NORMALIZED_TAG).defaultValues().returning(NORMALIZED_TAG.ID).fetchOne()!!.id
            assertEquals(8L, made)

            // Another type with tags of its own: the start stops rather than mix them by their numbers
            val games = oldCorrections("games")
            games.execute("INSERT INTO normalized_tag (id, name) VALUES (7, 'Sandbox')")
            assertFailsWith<IllegalStateException> { DictionaryMove.copy("games/corrections", games, to, DictionaryMove.CORRECTIONS) }
            assertEquals(listOf("Hulls", null), to.selectFrom(NORMALIZED_TAG).orderBy(NORMALIZED_TAG.ID).fetch { it.name })
        }
    }

    @Test
    fun `a file whose tables are gone gives nothing`() {
        Database("dictionaries", folder.resolve("dictionaries2"), "dictionaries").use { dictionaries ->
            Database("books/corrections", folder.resolve("corrections"), "typecorrections").use { migrated ->
                DictionaryMove.copy("books/corrections", migrated.dsl, dictionaries.dsl, DictionaryMove.CORRECTIONS)
            }
            assertEquals(0, dictionaries.dsl.fetchCount(NORMALIZED_TAG))
        }
    }
}
