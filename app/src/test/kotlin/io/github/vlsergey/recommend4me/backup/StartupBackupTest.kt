package io.github.vlsergey.recommend4me.backup

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.zip.ZipFile
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The copies of the user's own data on start: which files, where, zipped later, the newest kept. */
class StartupBackupTest {

    @TempDir
    lateinit var data: Path

    private fun file(path: String, text: String = "data") = data.resolve(path).also {
        it.parent.createDirectories()
        it.writeText(text)
    }

    @Test
    fun `the user's own files are copied — the answers and the grades — never what is made again from them`() {
        file("app.mv.db")
        file("sources/site/corrections.mv.db", "answers")
        file("sources/site/ratings.mv.db", "grades")
        file("sources/site/source.mv.db")
        file("types/books/corrections.mv.db", "tags")
        file("types/books/model.mv.db")
        // An empty file is no data yet
        file("sources/other/ratings.mv.db", "")

        val kept = StartupBackup.personalFiles(data).map { data.relativize(it).toString().replace('\\', '/') }.toSet()
        assertEquals(setOf("app.mv.db", "sources/site/corrections.mv.db", "sources/site/ratings.mv.db", "types/books/corrections.mv.db"), kept)

        val copy = StartupBackup.copy(data, LocalDateTime.of(2026, 10, 14, 9, 30, 5))!!
        assertEquals("backup-20261014-093005", copy.name)
        assertEquals("answers", copy.resolve("sources/site/corrections.mv.db").readText())
        assertEquals("tags", copy.resolve("types/books/corrections.mv.db").readText())
        assertTrue(!copy.resolve("types/books/model.mv.db").exists())
        // No copy left half made
        assertTrue(copy.parent.listDirectoryEntries().none { it.name.endsWith(".tmp") })
    }

    @Test
    fun `nothing to keep, no copy`() {
        file("sources/site/source.mv.db")
        assertNull(StartupBackup.copy(data, LocalDateTime.now()))
    }

    @Test
    fun `the copies are zipped and the newest kept`() {
        file("sources/site/corrections.mv.db", "answers")
        val times = (1..4).map { LocalDateTime.of(2026, 10, it, 12, 0) }
        times.forEach { StartupBackup.copy(data, it) }
        val backups = data.resolve("backups")
        // A copy a stop left half made is not taken for one
        backups.resolve("backup-20261005-120000.tmp").createDirectories()

        StartupBackup.compress(backups, keep = 2)

        val zips = backups.listDirectoryEntries("*.zip").map { it.name }.sorted()
        assertEquals(listOf("backup-20261003-120000.zip", "backup-20261004-120000.zip"), zips)
        assertTrue(backups.listDirectoryEntries().none { Files.isDirectory(it) && !it.name.endsWith(".tmp") }, "the plain copies are gone once zipped")
        ZipFile(backups.resolve(zips.last()).toFile()).use { zip ->
            assertEquals(listOf("sources/site/corrections.mv.db"), zip.entries().toList().map { it.name })
            assertEquals("answers", zip.getInputStream(zip.getEntry("sources/site/corrections.mv.db")).readBytes().decodeToString())
        }
    }
}
