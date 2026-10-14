package io.github.vlsergey.recommend4me.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.h2.engine.SessionLocal
import org.h2.jdbc.JdbcConnection
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.nio.file.Path

private val log = LoggerFactory.getLogger(Database::class.java)

/**
 * One H2 file — the data of one level of one source or content type — with its own pool of
 * connections and its own migrations: the application's (`classpath:db/<kind>`), and for a source
 * database the source's own ([pluginMigrations]) with a history table of their own.
 */
class Database(
    val name: String,
    /** The file without its `.mv.db`. */
    val file: Path,
    kind: String,
    pluginMigrations: List<String> = emptyList(),
    classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
) : AutoCloseable {

    private val pool = HikariDataSource(HikariConfig().apply {
        // A writer waits for a row another transaction holds up to 10 s (2 s by default). The file
        // is compressed: the texts of chapters, the pages kept, the reviews take a fraction of the
        // space. Closed, it is compacted for up to [CLOSE_COMPACT_MS]: what a long run of writes left
        // unused is given back
        jdbcUrl = "jdbc:h2:file:${file.toAbsolutePath().toString().replace('\\', '/')};DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=10000;COMPRESS=TRUE" +
            ";MAX_COMPACT_TIME=$CLOSE_COMPACT_MS"
        username = "sa"
        password = ""
        poolName = name
        maximumPoolSize = POOL
        // AN IDLE FILE IS CLOSED: no connection is kept once none was asked for [IDLE_MS], and H2
        // closes a file of no connection — compacted, written whole. The user's answers and grades are
        // asked for now and then: most of the time their files are closed, and a computer switched off
        // the wrong way finds them so. The next request opens the file again, in a few milliseconds
        minimumIdle = 0
        idleTimeout = IDLE_MS
    })

    val dsl: DSLContext = DSL.using(pool, SQLDialect.H2)

    init {
        migrate(listOf("classpath:db/$kind"), "flyway_schema_history", classLoader, shared = false)
        if (pluginMigrations.isNotEmpty()) migrate(pluginMigrations, "plugin_schema_history", classLoader, shared = true)
    }

    /**
     * Applies the migrations of [locations] with their own history [table]. The source's own
     * migrations come to a schema the application's have filled already ([shared]): their history
     * starts from an empty baseline, version 0, so that their first migration is applied too.
     */
    private fun migrate(locations: List<String>, table: String, classLoader: ClassLoader, shared: Boolean) {
        val result = Flyway.configure(classLoader)
            .dataSource(pool)
            .locations(*locations.toTypedArray())
            .table(table)
            .baselineOnMigrate(shared)
            .baselineVersion("0")
            .load()
            .migrate()
        if (result.migrationsExecuted > 0) log.info("{}: {} migrations of {} applied", name, result.migrationsExecuted, locations)
    }

    /**
     * COMPACTS THE FILE WHILE IT IS OPEN, for at most [maxMillis]: H2 writes every change at the
     * end of its file and leaves the old pages unused, and a file written all day — the model's —
     * is never idle long enough to be closed. The pages still used are moved together and the file
     * cut short, as on its closing; writes wait meanwhile. Done only where it gives back much: a file
     * of [MIN_COMPACT_SIZE] or more, less than [FILL_TO_COMPACT] % of it used. The time H2 keeps the
     * old pages for — what a crash falls back to — is set back after it.
     *
     * Returns the size before and after; null when it was not needed. H2's own classes: its API
     * compacts a file at its closing alone.
     */
    /** The file's size and the share of it used, in per cent, as H2 counts them now. */
    fun usage(): Triple<Long, Int, Int>? = pool.connection.use { c ->
        val mv = (c.unwrap(JdbcConnection::class.java).session as? SessionLocal)?.database?.store?.mvStore ?: return null
        Triple(mv.fileStore.size(), mv.fillRate, mv.fileStore.chunksFillRate)
    }

    fun compact(maxMillis: Int): Pair<Long, Long>? = pool.connection.use { c ->
        val session = c.unwrap(JdbcConnection::class.java).session as? SessionLocal ?: return null
        val store = session.database.store ?: return null
        val mv = store.mvStore
        val before = mv.fileStore.size()
        // The file's blocks in use, and the live pages within the chunks in them: either low, much to give back
        if (before < MIN_COMPACT_SIZE || minOf(mv.fillRate, mv.fileStore.chunksFillRate) >= FILL_TO_COMPACT) return null
        val retention = mv.retentionTime
        try {
            store.compactFile(maxMillis)
        } finally {
            mv.retentionTime = retention
        }
        before to mv.fileStore.size()
    }

    override fun close() = pool.close()

    companion object {
        private const val POOL = 6

        /** How long a connection asked for by nobody is kept: then closed, and the file with the last of them. */
        private const val IDLE_MS = 60_000L

        /** How long a file is compacted at its closing. */
        private const val CLOSE_COMPACT_MS = 2_000

        /** A file smaller is not compacted while open: what it would give back is little. */
        private const val MIN_COMPACT_SIZE = 64L shl 20

        /** A file of less than this share of it used, in per cent, is compacted while open. */
        private const val FILL_TO_COMPACT = 70
    }
}
