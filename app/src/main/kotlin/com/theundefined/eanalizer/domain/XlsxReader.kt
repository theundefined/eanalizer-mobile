package com.theundefined.eanalizer.domain

import java.io.ByteArrayInputStream
import java.time.Duration
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Minimal .xlsx reader used to import an Enea CSV that Google Drive converted to a Google Sheet
 * (the only form in which Drive hands such a file over). Reads the first sheet and renders it as
 * the Enea CSV text (`;`-separated, quoted, comma decimals), so it goes through [EneaCsvParser].
 */
object XlsxReader {
    private const val MAX_ENTRY_BYTES = 40 * 1024 * 1024
    private val EPOCH = LocalDate.of(1899, 12, 30).atStartOfDay()

    /** True when [bytes] look like a ZIP container (`PK`), i.e. not a plain CSV. */
    fun looksLikeXlsx(bytes: ByteArray) =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()

    /**
     * Returns the first sheet as CSV bytes (UTF-8), or null when [bytes] is not a readable xlsx.
     */
    fun toCsv(bytes: ByteArray): ByteArray? {
        val entries = HashMap<String, ByteArray>()
        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/")) {
                        val data = zip.readNBytes(MAX_ENTRY_BYTES + 1)
                        if (data.size <= MAX_ENTRY_BYTES) entries[e.name] = data
                    }
                }
            }
        }
        val sheetName =
            entries.keys
                .filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
                .minByOrNull { it.filter(Char::isDigit).toInt() } ?: return null
        val shared = entries["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val rows = runCatching { sheetRows(entries.getValue(sheetName), shared) }.getOrNull()
        if (rows.isNullOrEmpty()) return null
        val sb = StringBuilder()
        for (row in rows) {
            sb.append(row.joinToString(";") { "\"" + it.replace("\"", "\"\"") + "\"" }).append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    private fun parse(data: ByteArray): Element {
        val f = DocumentBuilderFactory.newInstance()
        // Hardening against XXE; not every XML implementation knows these features.
        runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { f.isExpandEntityReferences = false }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(data)).documentElement
    }

    private fun Element.children(tag: String): List<Element> =
        (0 until childNodes.length).mapNotNull { i ->
            (childNodes.item(i) as? Element)?.takeIf { it.localName ?: it.tagName == tag }
        }

    private fun Node.text(): String {
        val sb = StringBuilder()
        fun walk(n: Node) {
            if (n is Element && (n.localName ?: n.tagName) == "t") sb.append(n.textContent)
            else for (i in 0 until n.childNodes.length) walk(n.childNodes.item(i))
        }
        walk(this)
        return unescape(sb.toString())
    }

    private fun sharedStrings(data: ByteArray): List<String> =
        runCatching { parse(data).children("si").map { it.text() } }.getOrDefault(emptyList())

    private val ESCAPE = Regex("_x([0-9A-Fa-f]{4})_")

    /**
     * Decodes OOXML `_xHHHH_` escapes of characters XML cannot hold - Google Sheets writes the NUL
     * bytes of old eBOK exports (2022) as `_x0000_`.
     */
    internal fun unescape(s: String): String =
        if (!s.contains("_x")) s
        else ESCAPE.replace(s) { it.groupValues[1].toInt(16).toChar().toString() }

    private fun sheetRows(data: ByteArray, shared: List<String>): List<List<String>> {
        val sheetData = parse(data).children("sheetData").firstOrNull() ?: return emptyList()
        return sheetData.children("row").map { row ->
            val cells = ArrayList<String>()
            for (c in row.children("c")) {
                val col = columnIndex(c.getAttribute("r"))
                if (col != null) while (cells.size < col) cells += ""
                cells += cellValue(c, shared, first = cells.isEmpty())
            }
            cells
        }
    }

    private fun cellValue(c: Element, shared: List<String>, first: Boolean): String {
        val v = c.children("v").firstOrNull()?.textContent.orEmpty()
        return when (c.getAttribute("t")) {
            "s" -> shared.getOrNull(v.toIntOrNull() ?: -1).orEmpty()
            "inlineStr" -> c.children("is").firstOrNull()?.text().orEmpty()
            "str" -> unescape(v)
            "b",
            "e" -> v
            else -> number(v, first)
        }
    }

    /** A numeric cell; in the first column a date-sized value is a date serial (Enea's `Data`). */
    private fun number(v: String, first: Boolean): String {
        val d = v.toDoubleOrNull() ?: return v
        if (first && d in 20000.0..80000.0) {
            val minutes = Math.round(d * 1440.0)
            val t = EPOCH.plus(Duration.ofMinutes(minutes))
            return "%04d-%02d-%02d %02d:%02d:00"
                .format(t.year, t.monthValue, t.dayOfMonth, t.hour, t.minute)
        }
        return v.replace('.', ',')
    }

    private fun columnIndex(ref: String): Int? {
        val letters = ref.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return null
        return letters.fold(0) { acc, ch -> acc * 26 + (ch - 'A' + 1) } - 1
    }
}
