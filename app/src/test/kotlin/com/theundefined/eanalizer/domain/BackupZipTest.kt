package com.theundefined.eanalizer.domain

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupZipTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            files.forEach { (n, b) ->
                z.putNextEntry(ZipEntry(n))
                z.write(b)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun roundTripKeepsContentAndTime() {
        val out = ByteArrayOutputStream()
        val t = 1_700_000_000_000L
        BackupZip.write(
            out,
            listOf(
                BackupZip.Entry("enea/2025.csv", "a;b".toByteArray(), t),
                BackupZip.Entry("enea/imported/moje.csv", byteArrayOf(1, 2, 3), t),
                BackupZip.Entry(BackupZip.SETTINGS, "{}".toByteArray()),
            ),
        )
        val back = BackupZip.read(ByteArrayInputStream(out.toByteArray()))
        assertEquals(
            listOf("enea/2025.csv", "enea/imported/moje.csv", "settings.json"),
            back.map { it.name }
        )
        assertArrayEquals(byteArrayOf(1, 2, 3), back[1].bytes)
        assertEquals(t / 2000 * 2000, back[0].modified / 2000 * 2000) // zip time has 2 s resolution
    }

    @Test
    fun rejectsUnsafeAndForeignNames() {
        val data =
            zip(
                "../evil.csv" to byteArrayOf(1),
                "enea/../../x" to byteArrayOf(1),
                "/abs.csv" to byteArrayOf(1),
                "enea/.hidden" to byteArrayOf(1),
                "enea/a.tmp" to byteArrayOf(1),
                "shared_prefs/credentials.xml" to byteArrayOf(1),
                "enea/2024.csv" to byteArrayOf(1),
            )
        assertEquals(
            listOf("enea/2024.csv"),
            BackupZip.read(ByteArrayInputStream(data)).map { it.name }
        )
    }

    @Test
    fun skipsOversizedEntries() {
        val big = ByteArray(BackupZip.MAX_ENTRY_BYTES + 1)
        val data = zip("enea/big.csv" to big, "enea/ok.csv" to byteArrayOf(7))
        assertEquals(
            listOf("enea/ok.csv"),
            BackupZip.read(ByteArrayInputStream(data)).map { it.name }
        )
    }

    @Test
    fun allowedNames() {
        assertTrue(BackupZip.isAllowed("settings.json"))
        assertTrue(BackupZip.isAllowed("enea/imported/Dane_2024-01.csv"))
        assertFalse(BackupZip.isAllowed("enea/imported/sub/x.csv"))
    }
}
