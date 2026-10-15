package io.github.vlsergey.recommend4me.backup

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent
import org.springframework.context.ApplicationListener
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * A rolling backup of what nothing could make good — the user's grades, marks and corrections,
 * the dictionaries of tags and universes, the user's preferences and the settings — taken on every start before anything opens the databases. The scrape and the
 * model are left out: a scrape is downloaded again, a model trained again.
 *
 * The start waits only for plain copies of the files; they are zipped into one archive
 * (`backups/backup-<time>.zip`) on a thread of their own while the application runs. Copies left
 * unzipped by a stop are zipped on the next start. The newest `recommend4me.backup.keep` stay.
 */
class StartupBackup : ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    override fun onApplicationEvent(event: ApplicationEnvironmentPreparedEvent) {
        val env = event.environment
        val dataDir = Path.of(env.getRequiredProperty("recommend4me.data-dir"))
        val keep = env.getProperty("recommend4me.backup.keep", Int::class.java, 10)
        try {
            copy(dataDir, LocalDateTime.now())
        } catch (e: Exception) {
            // A failed backup must not prevent the application from starting
            log.error("Unable to back up the databases in {}", dataDir.toAbsolutePath(), e)
        }
        val backupDir = dataDir.resolve(BACKUPS)
        if (!backupDir.exists()) return
        Thread({
            try {
                compress(backupDir, keep)
            } catch (e: Exception) {
                log.error("Unable to zip the backups in {}", backupDir.toAbsolutePath(), e)
            }
        }, "backup").apply { isDaemon = true }.start()
    }

    companion object {
        private val log = LoggerFactory.getLogger(StartupBackup::class.java)
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        private const val BACKUPS = "backups"
        private const val PREFIX = "backup-"
        private const val ZIP = ".zip"
        private const val TMP = ".tmp"

        /** The files to keep, by their path inside the data folder. */
        fun personalFiles(dataDir: Path): List<Path> {
            val out = ArrayList<Path>()
            listOf("app.mv.db", "dictionaries.mv.db", "preferences.mv.db").map(dataDir::resolve).filter { it.exists() }.forEach(out::add)
            // A type's corrections of before too: kept on the start it is moved on ([TypeCorrectionsMove])
            for (level in listOf("sources", "types")) {
                val dir = dataDir.resolve(level)
                if (!dir.isDirectory()) continue
                dir.listDirectoryEntries().filter { it.isDirectory() }.forEach { owner ->
                    listOf("corrections.mv.db", "ratings.mv.db").map(owner::resolve).filter { it.exists() && it.fileSize() > 0 }.forEach(out::add)
                }
            }
            return out
        }

        /** Plain copies of the files into `backups/backup-<time>/`; null when there is nothing yet. */
        fun copy(dataDir: Path, now: LocalDateTime): Path? {
            val files = personalFiles(dataDir)
            if (files.isEmpty()) return null
            val target = dataDir.resolve(BACKUPS).resolve(PREFIX + STAMP.format(now))
            val tmp = target.resolveSibling(target.name + TMP)
            files.forEach { file ->
                val to = tmp.resolve(dataDir.relativize(file).toString())
                Files.createDirectories(to.parent)
                Files.copy(file, to, StandardCopyOption.REPLACE_EXISTING)
            }
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            log.info("{} files of the user's own data copied to {}", files.size, target.toAbsolutePath())
            return target
        }

        /** Zips every plain copy in [backupDir] and keeps the newest [keep] archives. */
        fun compress(backupDir: Path, keep: Int) {
            backupDir.listDirectoryEntries("$PREFIX*").filter { it.isDirectory() && !it.name.endsWith(TMP) }.sortedBy { it.name }.forEach { copy ->
                val started = System.nanoTime()
                val target = copy.resolveSibling(copy.name + ZIP)
                val tmp = target.resolveSibling(target.name + TMP)
                ZipOutputStream(Files.newOutputStream(tmp).buffered(1 shl 20)).use { zip ->
                    Files.walk(copy).use { paths ->
                        paths.filter { Files.isRegularFile(it) }.sorted().forEach { file ->
                            zip.putNextEntry(ZipEntry(copy.relativize(file).toString().replace('\\', '/')))
                            Files.copy(file, zip)
                            zip.closeEntry()
                        }
                    }
                }
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
                copy.toFile().deleteRecursively()
                log.info("Backup zipped to {} in {} s", target.name, (System.nanoTime() - started) / 1_000_000_000)
            }
            // Names embed a sortable timestamp, so lexical order is chronological
            backupDir.listDirectoryEntries("$PREFIX*$ZIP")
                .sortedByDescending { it.name }
                .drop(keep.coerceAtLeast(1))
                .forEach {
                    Files.deleteIfExists(it)
                    log.info("Old backup {} removed", it.name)
                }
        }
    }
}
