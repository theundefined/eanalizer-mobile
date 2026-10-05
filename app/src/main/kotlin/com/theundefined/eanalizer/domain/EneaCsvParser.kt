package com.theundefined.eanalizer.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Parser of the hourly CSV exported by Enea eBOK (`;`-separated, quoted, comma decimals). */
object EneaCsvParser {
    private const val COL_DATE = "Data"
    private const val COL_POBOR_PRZED =
        "Wolumen energii elektrycznej pobranej z sieci przed bilansowaniem godzinowym"
    private const val COL_ODDANIE_PRZED =
        "Wolumen energii elektrycznej oddanej do sieci przed bilansowaniem godzinowym"
    private const val COL_POBOR =
        "Wolumen energii elektrycznej pobranej z sieci po bilansowaniu godzinowym"
    private const val COL_ODDANIE =
        "Wolumen energii elektrycznej oddanej do sieci po bilansowaniu godzinowym"

    private val formats =
        listOf("yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd HH:mm").map { DateTimeFormatter.ofPattern(it) }

    /**
     * Parses file content. Returns an empty list if the header lacks the expected columns; rows
     * with an unparsable date or number are dropped.
     */
    fun parse(text: String): List<HourlyRecord> {
        val lines =
            text.replace("\u0000", "").removePrefix("\uFEFF").lineSequence().filter {
                it.isNotBlank()
            }
        val iter = lines.iterator()
        if (!iter.hasNext()) return emptyList()
        val header = splitLine(iter.next()).map { it.trim().removePrefix("\uFEFF") }
        val idx =
            listOf(COL_DATE, COL_POBOR_PRZED, COL_ODDANIE_PRZED, COL_POBOR, COL_ODDANIE).map { name
                ->
                header.indexOfFirst { it.equals(name, ignoreCase = true) }
            }
        if (idx.any { it < 0 }) return emptyList()
        val out = ArrayList<HourlyRecord>()
        for (line in iter) {
            val f = splitLine(line)
            if (f.size <= idx.max()) continue
            val ts = parseTimestamp(f[idx[0]]) ?: continue
            val nums = idx.drop(1).map { parseNumber(f[it]) }
            if (nums.any { it == null }) continue
            out += HourlyRecord(ts, nums[0]!!, nums[1]!!, nums[2]!!, nums[3]!!)
        }
        return out
    }

    /** Parses `="2024-05-01 04:59"`-style values, floored to the hour. */
    internal fun parseTimestamp(raw: String): LocalDateTime? {
        val s = raw.replace("=", "").replace("\"", "").trim().replace('T', ' ')
        if (s.isEmpty()) return null
        for (fmt in formats) {
            runCatching {
                return LocalDateTime.parse(s, fmt).truncatedTo(ChronoUnit.HOURS)
            }
        }
        return runCatching { LocalDate.parse(s).atStartOfDay() }.getOrNull()
    }

    internal fun parseNumber(raw: String): Double? =
        raw.replace("\"", "").trim().replace(',', '.').toDoubleOrNull()?.takeIf { !it.isNaN() }

    /** Splits a `;`-separated line honouring `"` quoting with `""` escapes. */
    internal fun splitLine(line: String, sep: Char = ';'): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        val l = line.trimEnd('\r')
        while (i < l.length) {
            val c = l[i]
            when {
                quoted && c == '"' && i + 1 < l.length && l[i + 1] == '"' -> {
                    sb.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == sep && !quoted -> {
                    out += sb.toString()
                    sb.clear()
                }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
