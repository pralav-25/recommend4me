package io.github.vlsergey.recommend4me.database

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.h2.engine.SessionLocal
import org.h2.jdbc.JdbcConnection
import org.h2.mvstore.MVStore
import org.h2.mvstore.MVStoreTool
import org.h2.mvstore.RandomAccessStore
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import java.nio.file.Files
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
    /** The values the application's migrations of the file are written with: `${source}` of a source's file. */
    private val placeholders: Map<String, String> = emptyMap(),
    /** Done with the file as it is, before its migrations: what a migration drops copied elsewhere first. */
    beforeMigrations: (DSLContext) -> Unit = {},
) : AutoCloseable {

    init {
        // Before the pool, which opens the file at once
        rewriteIfWasted(name, file)
    }

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

    /** Its bytes written as bytes, not as temporary LOBs ([InlineBlobs]). */
    val dsl: DSLContext = DSL.using(InlineBlobs(pool), SQLDialect.H2)

    init {
        beforeMigrations(dsl)
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
            .placeholders(if (shared) emptyMap() else placeholders)
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
        val until = System.nanoTime() + maxMillis * 1_000_000L
        try {
            // The chunks mostly unused written anew, their pages together
            store.compactFile(maxMillis)
            // And the chunks after the first free space moved into it, the file cut short: H2 does it
            // after the rewriting alone — a file of full chunks with holes between them, a file written
            // all day, never shrinks so — and at its closing; here a part at a time, while it shrinks
            val fileStore = mv.fileStore
            if (fileStore is RandomAccessStore) {
                mv.retentionTime = 0
                var size = fileStore.size()
                while (System.nanoTime() < until) {
                    fileStore.compactMoveChunks(100, MOVE_BYTES, mv)
                    val now = fileStore.size()
                    if (now >= size) break
                    size = now
                }
            }
        } finally {
            mv.retentionTime = retention
        }
        before to mv.fileStore.size()
    }

    override fun close() = pool.close()

    companion object {
        /**
         * A FILE MOSTLY UNUSED WRITTEN ANEW before it is opened: its live pages copied into a new file
         * that takes its place (H2's own [MVStoreTool.compact]) — when it is of [MIN_COMPACT_SIZE] or
         * more and less than [FILL_TO_COMPACT] % of its chunks live. Compacting an open file gives
         * back no space H2 still counts as used though its maps hold it no more: pages of the LOBs a
         * row once had above all, dead in the maps, live in the chunks' accounts, never rewritten. Of
         * f95zone's source, 7.9 GB written over two weeks, 3 GB were left, every row and LOB as before.
         * The new file is made beside it and put in its place in one move: a run stopped meanwhile
         * leaves the old file whole, the half-made one removed by H2 at the next opening.
         */
        private fun rewriteIfWasted(name: String, file: Path) {
            val mvFile = file.resolveSibling(file.fileName.toString() + ".mv.db")
            if (!Files.exists(mvFile) || Files.size(mvFile) < MIN_COMPACT_SIZE) return
            MVStoreTool.compactCleanUp(mvFile.toString())
            val live = MVStore.Builder().fileName(mvFile.toString()).readOnly().open().use { it.fileStore.chunksFillRate }
            if (live >= FILL_TO_COMPACT) return
            val before = Files.size(mvFile)
            val started = System.currentTimeMillis()
            MVStoreTool.compact(mvFile.toString(), true)
            log.info(
                "{}: written anew from {} MB to {} MB in {} s, {} % of its chunks live before",
                name, before shr 20, Files.size(mvFile) shr 20, (System.currentTimeMillis() - started) / 1000, live,
            )
        }

        private const val POOL = 6

        /** How long a connection asked for by nobody is kept: then closed, and the file with the last of them. */
        private const val IDLE_MS = 60_000L

        /** How long a file is compacted at its closing. */
        private const val CLOSE_COMPACT_MS = 2_000

        /** A file smaller is not compacted while open: what it would give back is little. */
        private const val MIN_COMPACT_SIZE = 64L shl 20

        /** A file of less than this share of it used, in per cent, is compacted while open. */
        private const val FILL_TO_COMPACT = 70

        /** The chunks moved to the free space before the file is cut short, at a time: the writes wait meanwhile. */
        private const val MOVE_BYTES = 512L shl 20
    }
}
