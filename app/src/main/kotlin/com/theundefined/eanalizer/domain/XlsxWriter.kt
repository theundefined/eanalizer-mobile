package com.theundefined.eanalizer.domain

import java.io.OutputStream
import java.io.Writer
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal Office Open XML (.xlsx) writer: plain sheets of numbers, text and dates, first row bold
 * and frozen. Opens in Excel, Google Sheets and LibreOffice. Cells: [Number], [LocalDateTime],
 * [LocalDate], anything else is written as text; null is an empty cell.
 */
class XlsxSheet(val name: String, val rows: Sequence<List<Any?>>)

object XlsxWriter {
    private val EPOCH = LocalDate.of(1899, 12, 30).atStartOfDay()

    fun write(sheets: List<XlsxSheet>, out: OutputStream) {
        val zip = ZipOutputStream(out)
        val w = zip.writer(Charsets.UTF_8)
        fun entry(name: String, body: Writer.() -> Unit) {
            zip.putNextEntry(ZipEntry(name))
            w.body()
            w.flush()
            zip.closeEntry()
        }
        entry("[Content_Types].xml") {
            write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
"""
            )
            sheets.indices.forEach {
                write(
                    "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>\n"
                )
            }
            write("</Types>")
        }
        entry("_rels/.rels") {
            write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
            )
        }
        entry("xl/workbook.xml") {
            write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>"""
            )
            sheets.forEachIndexed { i, s ->
                write(
                    "<sheet name=\"${esc(sheetName(s.name))}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>"
                )
            }
            write("</sheets></workbook>")
        }
        entry("xl/_rels/workbook.xml.rels") {
            write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">"""
            )
            sheets.indices.forEach {
                write(
                    "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>"
                )
            }
            write(
                "<Relationship Id=\"rId${sheets.size + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>"
            )
        }
        // Styles: 0 default, 1 bold (header), 2 date+time, 3 date.
        entry("xl/styles.xml") {
            write(
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="2"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm"/><numFmt numFmtId="165" formatCode="yyyy-mm-dd"/></numFmts>
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
            )
        }
        sheets.forEachIndexed { i, sheet ->
            entry("xl/worksheets/sheet${i + 1}.xml") {
                write(
                    """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="18" customWidth="1"/></cols><sheetData>"""
                )
                sheet.rows.forEachIndexed { r, cells ->
                    write("<row r=\"${r + 1}\">")
                    cells.forEachIndexed { c, v -> cell(this, column(c) + (r + 1), v, r == 0) }
                    write("</row>")
                }
                write("</sheetData></worksheet>")
            }
        }
        zip.finish()
        w.flush()
    }

    private fun cell(w: Writer, ref: String, v: Any?, header: Boolean) {
        when (v) {
            null -> {}
            is Number ->
                w.write("<c r=\"$ref\"${if (header) " s=\"1\"" else ""}><v>${number(v)}</v></c>")
            is LocalDateTime -> w.write("<c r=\"$ref\" s=\"2\"><v>${number(serial(v))}</v></c>")
            is LocalDate ->
                w.write("<c r=\"$ref\" s=\"3\"><v>${number(serial(v.atStartOfDay()))}</v></c>")
            else ->
                w.write(
                    "<c r=\"$ref\" t=\"inlineStr\"${if (header) " s=\"1\"" else ""}><is><t>${esc(v.toString())}</t></is></c>"
                )
        }
    }

    /** Excel serial date (days since 1899-12-30). */
    internal fun serial(t: LocalDateTime): Double = Duration.between(EPOCH, t).toMinutes() / 1440.0

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    internal fun column(i: Int): String {
        var n = i + 1
        val sb = StringBuilder()
        while (n > 0) {
            val r = (n - 1) % 26
            sb.append('A' + r)
            n = (n - 1) / 26
        }
        return sb.reverse().toString()
    }

    private fun number(v: Number): String {
        val d = v.toDouble()
        if (d.isNaN() || d.isInfinite()) return "0"
        return if (d == Math.rint(d) && Math.abs(d) < 1e15) d.toLong().toString()
        else String.format(Locale.ROOT, "%.6f", d).trimEnd('0').trimEnd('.')
    }

    /** Sheet names: max 31 chars, no `[]:*?/\`. */
    private fun sheetName(s: String) = s.replace(Regex("[\\[\\]:*?/\\\\]"), " ").take(31)

    private fun esc(s: String): String =
        buildString(s.length) {
            for (ch in s) {
                when {
                    ch == '&' -> append("&amp;")
                    ch == '<' -> append("&lt;")
                    ch == '>' -> append("&gt;")
                    ch == '"' -> append("&quot;")
                    ch < ' ' && ch != '\t' && ch != '\n' && ch != '\r' -> {}
                    else -> append(ch)
                }
            }
        }
}
