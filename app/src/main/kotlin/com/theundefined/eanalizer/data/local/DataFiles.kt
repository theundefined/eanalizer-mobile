package com.theundefined.eanalizer.data.local

import android.content.Context
import com.theundefined.eanalizer.domain.EneaCsvParser
import com.theundefined.eanalizer.domain.HourlyRecord
import com.theundefined.eanalizer.domain.mergeRecords
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Downloaded Enea CSVs, one file per year: `filesDir/enea/<year>.csv`. */
class DataFiles(context: Context) {
    private val dir = File(context.filesDir, "enea")

    data class YearFile(val year: Int, val file: File) {
        val modified: Instant
            get() = Instant.ofEpochMilli(file.lastModified())
    }

    fun list(): List<YearFile> =
        dir.listFiles { f -> f.name.endsWith(".csv") }
            ?.mapNotNull { f -> f.nameWithoutExtension.toIntOrNull()?.let { YearFile(it, f) } }
            ?.sortedBy { it.year } ?: emptyList()

    fun write(year: Int, content: String) {
        dir.mkdirs()
        val tmp = File(dir, "$year.csv.tmp")
        tmp.writeText(content)
        tmp.renameTo(File(dir, "$year.csv"))
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

    fun loadRecords(): List<HourlyRecord> =
        mergeRecords(
            list().map {
                runCatching { EneaCsvParser.parse(it.file.readText()) }.getOrDefault(emptyList())
            }
        )

    fun clear() {
        dir.deleteRecursively()
    }
}
