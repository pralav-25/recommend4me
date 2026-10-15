package io.github.vlsergey.recommend4me.database

import org.jooq.DSLContext
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.fail

/**
 * THE SCHEMA OF EVERY DATABASE OF THE APPLICATION IS DOCUMENTED AS IT IS: docs/database/<kind>.md
 * holds a diagram of its tables (Mermaid, drawn by GitHub) made of the database its migrations make —
 * the application's own, not the plugins'. A migration that changes a table and a document that is
 * not changed with it fail here; the diagram expected is written to build/schema-docs/<kind>.md.
 */
class SchemaDocsTest {

    @TempDir
    lateinit var folder: Path

    private val docs = Path.of("../docs/database").toAbsolutePath().normalize()

    @Test
    fun `every database's tables are documented as its migrations make them`() {
        // Every kind of database: a folder of migrations of the application, none forgotten
        val folders = Files.list(Path.of("src/main/resources/db")).use { s -> s.map { it.fileName.toString() }.sorted().toList() }
        if (folders != KINDS.sorted()) fail("The kinds of databases $folders are not the documented ones ${KINDS.sorted()}")
        val wrong = KINDS.filter { kind ->
            val expected = Database(kind, folder.resolve(kind), kind).use { diagram(it.dsl) }
            val file = docs.resolve("$kind.md")
            val written = if (Files.exists(file)) mermaid(Files.readString(file)) else null
            if (written == expected) return@filter false
            val out = Path.of("build/schema-docs").resolve("$kind.md")
            Files.createDirectories(out.parent)
            Files.writeString(out, "```mermaid\n$expected\n```\n")
            true
        }
        if (wrong.isNotEmpty()) {
            fail("The diagrams of $wrong in docs/database differ from the databases the migrations make: the expected ones are in app/build/schema-docs")
        }
    }

    /** The first Mermaid block of a document, its lines trimmed of trailing spaces. */
    private fun mermaid(markdown: String): String? =
        Regex("```mermaid\\r?\\n(.*?)\\r?\\n```", RegexOption.DOT_MATCHES_ALL).find(markdown)?.groupValues?.get(1)
            ?.lines()?.joinToString("\n") { it.trimEnd() }

    private class Column(val name: String, val type: String, val nullable: Boolean)

    /** The diagram of the database's tables: their columns in order, keys, and the references between them. */
    private fun diagram(db: DSLContext): String {
        val tables = db.fetch(
            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = 'PUBLIC' AND TABLE_TYPE = 'BASE TABLE' " +
                "AND TABLE_NAME NOT IN ('flyway_schema_history', 'plugin_schema_history') ORDER BY TABLE_NAME",
        ).map { it.get(0, String::class.java) }
        val columns = db.fetch(
            "SELECT c.TABLE_NAME, c.COLUMN_NAME, c.DATA_TYPE, c.IS_NULLABLE, e.DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS c " +
                "LEFT JOIN INFORMATION_SCHEMA.ELEMENT_TYPES e ON e.OBJECT_SCHEMA = c.TABLE_SCHEMA AND e.OBJECT_NAME = c.TABLE_NAME " +
                "AND e.COLLECTION_TYPE_IDENTIFIER = c.DTD_IDENTIFIER " +
                "WHERE c.TABLE_SCHEMA = 'PUBLIC' ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION",
        ).groupBy({ it.get(0, String::class.java) }) {
            val type = it.get(2, String::class.java)
            val element = it.get(4, String::class.java)
            Column(it.get(1, String::class.java), if (type == "ARRAY" && element != null) "${short(element)}_ARRAY" else short(type), it.get(3, String::class.java) == "YES")
        }
        fun keyColumns(type: String) = db.fetch(
            "SELECT k.TABLE_NAME, k.COLUMN_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS t JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k " +
                "ON k.CONSTRAINT_SCHEMA = t.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = t.CONSTRAINT_NAME " +
                "WHERE t.CONSTRAINT_SCHEMA = 'PUBLIC' AND t.CONSTRAINT_TYPE = ?",
            type,
        ).map { it.get(0, String::class.java) to it.get(1, String::class.java) }.toSet()
        val primary = keyColumns("PRIMARY KEY")
        val foreign = keyColumns("FOREIGN KEY")
        // Every reference: the table referring, the one referred to, the columns referring
        val references = db.fetch(
            "SELECT k.TABLE_NAME, u.TABLE_NAME, k.COLUMN_NAME, k.CONSTRAINT_NAME FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS r " +
                "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE k ON k.CONSTRAINT_SCHEMA = r.CONSTRAINT_SCHEMA AND k.CONSTRAINT_NAME = r.CONSTRAINT_NAME " +
                "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS u ON u.CONSTRAINT_SCHEMA = r.UNIQUE_CONSTRAINT_SCHEMA AND u.CONSTRAINT_NAME = r.UNIQUE_CONSTRAINT_NAME " +
                "WHERE r.CONSTRAINT_SCHEMA = 'PUBLIC' ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME, k.ORDINAL_POSITION",
        ).groupBy({ it.get(3, String::class.java) }) { Triple(it.get(0, String::class.java), it.get(1, String::class.java), it.get(2, String::class.java)) }
            .values.map { cols -> Triple(cols.first().first, cols.first().second, cols.joinToString(", ") { it.third.lowercase() }) }
            .sortedWith(compareBy({ it.second }, { it.first }, { it.third }))

        val out = StringBuilder("erDiagram")
        tables.forEach { table ->
            out.append("\n    ").append(table.lowercase()).append(" {")
            columns[table].orEmpty().forEach { c ->
                val keys = listOfNotNull("PK".takeIf { (table to c.name) in primary }, "FK".takeIf { (table to c.name) in foreign })
                out.append("\n        ").append(c.type).append(' ').append(c.name.lowercase())
                if (keys.isNotEmpty()) out.append(' ').append(keys.joinToString(", "))
                if (c.nullable) out.append(" \"null\"")
            }
            out.append("\n    }")
        }
        references.forEach { (from, to, cols) ->
            out.append("\n    ").append(to.lowercase()).append(" ||--o{ ").append(from.lowercase()).append(" : \"").append(cols).append('"')
        }
        return out.toString()
    }

    /** A type as the diagram writes it: one word. */
    private fun short(type: String): String = when (type) {
        "CHARACTER VARYING" -> "VARCHAR"
        "CHARACTER" -> "CHAR"
        "CHARACTER LARGE OBJECT" -> "CLOB"
        "BINARY VARYING" -> "VARBINARY"
        "BINARY LARGE OBJECT" -> "BLOB"
        "TIMESTAMP WITH TIME ZONE" -> "TIMESTAMPTZ"
        "DOUBLE PRECISION" -> "DOUBLE"
        else -> type.replace(' ', '_')
    }

    private companion object {
        /** Every kind of database of the application: the folders of its migrations. */
        val KINDS = listOf("app", "source", "corrections", "ratings", "typecorrections", "model", "dictionaries")
    }
}
