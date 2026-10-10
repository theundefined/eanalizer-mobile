package com.theundefined.eanalizer.domain

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
}
