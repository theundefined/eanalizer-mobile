package com.theundefined.eanalizer.domain

import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** Old eBOK exports (2022) have stray NUL bytes around the date field of every row. */
class EneaCsvNulRowsTest {
    private val header =
        "Data;\"Wolumen energii elektrycznej pobranej z sieci przed bilansowaniem godzinowym\";" +
            "\"Wolumen energii elektrycznej oddanej do sieci przed bilansowaniem godzinowym\";" +
            "\"Wolumen energii elektrycznej pobranej z sieci po bilansowaniu godzinowym\";" +
            "\"Wolumen energii elektrycznej oddanej do sieci po bilansowaniu godzinowym\""

    private fun row(ts: String) = "\u0000\"=\"\"$ts\"\"\"\u0000;\"0,329\";\"0\";\"0,329\";\"0\""

    @Test
    fun parsesRowsWithStrayNulBytesAfterDecode() {
        val text = header + "\n" + row("2022-07-01 00:59") + "\n" + row("2022-07-01 01:59") + "\n"
        val recs = EneaCsvParser.parse(EneaCsvParser.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(2, recs.size)
        assertEquals(0.329, recs[0].pobor, 1e-9)
    }

    /** The same 2022 file uploaded to Google Drive, opened in Sheets and downloaded as CSV. */
    @Test
    fun parsesGoogleSheetsCsvExport() {
        val sheetsHeader = header.replace("\";\"", ",").replace("\"", "").replace(';', ',')
        fun sheetsRow(ts: String) =
            "\"\u0000\"\"=\"\"\"\"$ts\"\"\"\"\"\"\u0000\",\"0,329\",0,\"0,329\",0"
        val text =
            sheetsHeader +
                "\r\n" +
                sheetsRow("2022-07-01 00:59") +
                "\r\n" +
                sheetsRow("2022-07-01 01:59")
        val recs = EneaCsvParser.parse(EneaCsvParser.decode(text.toByteArray(Charsets.UTF_8)))
        assertEquals(2, recs.size)
        assertEquals(LocalDateTime.of(2022, 7, 1, 1, 0), recs[1].timestamp)
        assertEquals(0.329, recs[0].pobor, 1e-9)
        assertEquals(0.0, recs[0].oddanie, 1e-9)
    }
}
