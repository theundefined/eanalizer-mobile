package com.theundefined.eanalizer.data.local

import android.content.Context
import com.theundefined.eanalizer.domain.EneaCsvParser
import com.theundefined.eanalizer.domain.HourlyRecord
import com.theundefined.eanalizer.domain.mergeRecords
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The file does not look like an Enea hourly CSV (no expected columns or no valid rows). */
class InvalidCsvException(val fileName: String) : Exception("not an Enea CSV: $fileName")

/** A stored data file as shown in the settings. */
data class DataFileInfo(
    val name: String,
    /** Added by the user (not downloaded from Enea). */
    val imported: Boolean,
    /** Epoch ms of the last write. */
    val modified: Long,
    val from: LocalDate?,
    val to: LocalDate?,
    val hours: Int,
)

/** Parsed content of all stored files. */
class LocalData(val records: List<HourlyRecord>, val files: List<DataFileInfo>)

/**
 * Data files: Enea downloads one per year (`filesDir/enea/<year>.csv`, the CSV text from eBOK) and
 * files imported by the user (`filesDir/enea/imported/<name>`, bytes kept as uploaded).
 */
class DataFiles(context: Context) {
    private val dir = File(context.filesDir, "enea")
    private val importDir = File(dir, "imported")

    data class YearFile(val year: Int, val file: File) {
        val modified: Instant
            get() = Instant.ofEpochMilli(file.lastModified())
    }

    fun list(): List<YearFile> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".csv") }
            ?.mapNotNull { f -> f.nameWithoutExtension.toIntOrNull()?.let { YearFile(it, f) } }
            ?.sortedBy { it.year } ?: emptyList()

    private fun importedFiles(): List<File> =
        importDir.listFiles { f -> f.isFile && !f.name.endsWith(".tmp") }?.sortedBy { it.name }
            ?: emptyList()

    fun write(year: Int, content: String) {
        dir.mkdirs()
        val tmp = File(dir, "$year.csv.tmp")
        tmp.writeText(content)
        tmp.renameTo(File(dir, "$year.csv"))
    }

    /**
     * Stores an imported file under a sanitised [name]. A stored file with the same name is
     * replaced only when it covers the same range (an updated export); otherwise a `-2`, `-3`...
     * suffix is added, as eBOK may give different exports the same name. Throws
     * [InvalidCsvException] when it has no valid Enea rows. Returns the stored name.
     */
    fun import(name: String, bytes: ByteArray): String {
        val range = rangeOf(bytes) ?: throw InvalidCsvException(name)
        val safe = sanitize(name)
        val base = safe.substringBeforeLast('.')
        val ext = safe.removePrefix(base)
        val target =
            generateSequence(1) { it + 1 }
                .map { n -> File(importDir, if (n == 1) safe else "$base-$n$ext") }
                .first { f ->
                    !f.isFile || runCatching { rangeOf(f.readBytes()) }.getOrNull() == range
                }
        importDir.mkdirs()
        val tmp = File(importDir, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        tmp.renameTo(target)
        return target.name
    }

    private fun rangeOf(bytes: ByteArray) =
        EneaCsvParser.parse(EneaCsvParser.decode(bytes))
            .takeIf { it.isNotEmpty() }
            ?.let { recs -> recs.minOf { it.timestamp } to recs.maxOf { it.timestamp } }

    /** The stored file, or null when it does not exist. */
    fun file(name: String, imported: Boolean): File? =
        File(if (imported) importDir else dir, sanitize(name)).takeIf { it.isFile }

    fun delete(name: String, imported: Boolean) {
        file(name, imported)?.delete()
    }

    /**
     * Whether [year] needs (re)downloading: missing or invalid file (Enea fills unavailable values
     * with `---`), the current year, or a past year last written before Jan 15 of the next year
     * (late corrections).
     */
    fun needsDownload(year: Int, today: LocalDate = LocalDate.now()): Boolean {
        val f = File(dir, "$year.csv")
        if (!f.isFile || year >= today.year) return true
        if (runCatching { f.readText().contains("---") }.getOrDefault(true)) return true
        val written =
            Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()).toLocalDate()
        return written < LocalDate.of(year + 1, 1, 15)
    }

    /**
     * Parses every file. Where files overlap (an imported file and an Enea download of the same
     * hours), the most recently written one wins.
     */
    fun load(): LocalData {
        val parsed =
            (list().map { it.file to false } + importedFiles().map { it to true }).map { (f, imp) ->
                val recs =
                    runCatching { EneaCsvParser.parse(EneaCsvParser.decode(f.readBytes())) }
                        .getOrDefault(emptyList())
                Triple(f, imp, recs)
            }
        val infos =
            parsed.map { (f, imp, recs) ->
                DataFileInfo(
                    name = f.name,
                    imported = imp,
                    modified = f.lastModified(),
                    from = recs.minOfOrNull { it.timestamp }?.toLocalDate(),
                    to = recs.maxOfOrNull { it.timestamp }?.toLocalDate(),
                    hours = recs.size,
                )
            }
        val records = mergeRecords(parsed.sortedBy { it.first.lastModified() }.map { it.third })
        return LocalData(records, infos)
    }

    /** Removes the Enea downloads; imported files stay. */
    fun clearDownloaded() {
        list().forEach { it.file.delete() }
    }

    fun clear() {
        dir.deleteRecursively()
    }

    private fun sanitize(name: String): String =
        name
            .substringAfterLast('/')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .trimStart('.')
            .take(100)
            .ifEmpty { "dane.csv" }
            .let { if (it.endsWith(".tmp")) "$it.csv" else it }
}
