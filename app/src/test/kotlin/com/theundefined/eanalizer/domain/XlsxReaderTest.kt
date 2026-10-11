package com.theundefined.eanalizer.domain

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XlsxReaderTest {
    private val header =
        listOf(
            "Data",
            "Wolumen energii elektrycznej pobranej z sieci przed bilansowaniem godzinowym",
            "Wolumen energii elektrycznej oddanej do sieci przed bilansowaniem godzinowym",
            "Wolumen energii elektrycznej pobranej z sieci po bilansowaniu godzinowym",
            "Wolumen energii elektrycznej oddanej do sieci po bilansowaniu godzinowym",
        )

    private fun parsed(xlsx: ByteArray) =
        EneaCsvParser.parse(EneaCsvParser.decode(XlsxReader.toCsv(xlsx)!!))

    @Test
    fun roundTripWithOwnWriter() {
        val rows =
            sequenceOf<List<Any?>>(
                header,
                listOf(LocalDateTime.of(2022, 3, 4, 5, 0), 0.5, 0.0, 0.25, 1.5),
                listOf(LocalDateTime.of(2022, 3, 4, 6, 0), 1.0, 2.0, 3.0, 4.0),
            )
        val out = ByteArrayOutputStream()
        XlsxWriter.write(listOf(XlsxSheet("Dane", rows)), out)
        val recs = parsed(out.toByteArray())
        assertEquals(2, recs.size)
        assertEquals(LocalDateTime.of(2022, 3, 4, 5, 0), recs[0].timestamp)
        assertEquals(0.25, recs[0].pobor, 1e-9)
        assertEquals(1.5, recs[0].oddanie, 1e-9)
    }

    @Test
    fun sharedStringsDateAsTextAndSparseCells() {
        val sheet =
            """<worksheet><sheetData>
            <row r="1">${header.indices.joinToString("") { """<c r="${'A' + it}1" t="s"><v>${it + 1}</v></c>""" }}</row>
            <row r="2"><c r="A2" t="s"><v>0</v></c><c r="B2"><v>0.5</v></c><c r="C2"><v>0</v></c><c r="D2"><v>1.25</v></c><c r="E2"><v>0</v></c></row>
            </sheetData></worksheet>"""
        val strings =
            "<sst>" +
                (listOf("=\"2022-01-01 00:59\"") + header).joinToString("") {
                    "<si><t>${it.replace("\"", "&quot;")}</t></si>"
                } +
                "</sst>"
        val recs =
            parsed(zip("xl/worksheets/sheet1.xml" to sheet, "xl/sharedStrings.xml" to strings))
        assertEquals(1, recs.size)
        assertEquals(LocalDateTime.of(2022, 1, 1, 0, 0), recs[0].timestamp)
        assertEquals(1.25, recs[0].pobor, 1e-9)
    }

    /** Google Sheets' export of a 2022 eBOK file: namespaced XML, NUL bytes as `_x0000_`. */
    @Test
    fun googleSheetsExportWithEscapedNulBytes() {
        val ns = "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
        val sheet =
            """<worksheet $ns><sheetData>
            <row r="1">${header.indices.joinToString("") { """<c r="${'A' + it}1" s="1" t="s"><v>$it</v></c>""" }}</row>
            <row r="2"><c r="A2" s="1" t="s"><v>5</v></c><c r="B2" s="1"><v>0.329</v></c><c r="C2" s="1"><v>0.0</v></c><c r="D2" s="1"><v>0.329</v></c><c r="E2" s="1"><v>0.0</v></c></row>
            </sheetData></worksheet>"""
        val date = "_x0000_&quot;=&quot;&quot;2022-07-01 00:59&quot;&quot;&quot;_x0000_"
        val strings =
            "<sst $ns>" + (header + date).joinToString("") { "<si><t>$it</t></si>" } + "</sst>"
        val recs =
            parsed(zip("xl/worksheets/sheet1.xml" to sheet, "xl/sharedStrings.xml" to strings))
        assertEquals(1, recs.size)
        assertEquals(LocalDateTime.of(2022, 7, 1, 0, 0), recs[0].timestamp)
        assertEquals(0.329, recs[0].pobor, 1e-9)
        assertEquals("a\u0000b", XlsxReader.unescape("a_x0000_b"))
    }

    @Test
    fun notXlsx() {
        assertNull(XlsxReader.toCsv("a;b\n1;2".toByteArray()))
        assertTrue(XlsxReader.looksLikeXlsx(zip("x" to "y")))
    }

    private fun zip(vararg files: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((n, c) in files) {
                z.putNextEntry(ZipEntry(n))
                z.write(c.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
