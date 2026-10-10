package com.theundefined.eanalizer.domain

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Backup archive: `enea/<year>.csv` (Enea downloads), `enea/imported/<name>` (imported files) and
 * `settings.json`. No credentials and no session - only data and report settings. Names are
 * validated on read, so a crafted archive cannot write outside the data directory.
 */
object BackupZip {
    const val SETTINGS = "settings.json"
    const val MAX_ENTRY_BYTES = 20 * 1024 * 1024
    const val MAX_TOTAL_BYTES = 400L * 1024 * 1024

    /** [modified] = epoch millis of the file (kept so "most recently written wins" survives). */
    class Entry(val name: String, val bytes: ByteArray, val modified: Long = 0L)

    private val NAME =
        Regex(
            "^(settings\\.json|enea/[A-Za-z0-9_-][A-Za-z0-9._-]{0,99}|enea/imported/[A-Za-z0-9_-][A-Za-z0-9._-]{0,99})$"
        )

    fun isAllowed(name: String) = NAME.matches(name) && !name.endsWith(".tmp")

    fun write(out: OutputStream, entries: List<Entry>) {
        ZipOutputStream(out).use { zip ->
            entries.forEach { e ->
                zip.putNextEntry(ZipEntry(e.name).apply { if (e.modified > 0) time = e.modified })
                zip.write(e.bytes)
                zip.closeEntry()
            }
        }
    }

    /** Reads the allowed entries; others and entries over [MAX_ENTRY_BYTES] are skipped. */
    fun read(input: InputStream): List<Entry> {
        val out = ArrayList<Entry>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || !isAllowed(e.name)) continue
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(64 * 1024)
                var tooBig = false
                while (true) {
                    val n = zip.read(chunk)
                    if (n < 0) break
                    if (buf.size() + n > MAX_ENTRY_BYTES) {
                        tooBig = true
                        break
                    }
                    buf.write(chunk, 0, n)
                }
                if (tooBig) continue
                total += buf.size()
                if (total > MAX_TOTAL_BYTES) break
                out += Entry(e.name, buf.toByteArray(), e.time.coerceAtLeast(0L))
            }
        }
        return out
    }
}
