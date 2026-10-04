package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import com.theundefined.eanalizer.domain.TestData.rec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EneaCsvParserTest {
    @Test
    fun loadsValidFile() {
        val r = TestData.records
        assertEquals(5, r.size)
        assertEquals(1.0, r[0].poborPrzed, 1e-9)
        assertEquals(at(2024, 5, 1, 4), r[0].timestamp)
        assertEquals(rec(at(2024, 5, 4, 10), 0.2, 5.0, 0.0, 4.8), r[4])
    }

    @Test
    fun handlesBomNullBytesAndCrLf() {
        val withJunk =
            "﻿" + TestData.ENEA_CSV.replace("\n", "\r\n").map { "$it\u0000" }.joinToString("")
        assertEquals(TestData.records, EneaCsvParser.parse(withJunk))
    }

    @Test
    fun invalidStructureGivesEmptyList() {
        val csv =
            "tariff,zone_name,day_type,start_hour,end_hour,price_per_kwh\nG11,stala,all,0,24,0.97\n"
        assertTrue(EneaCsvParser.parse(csv).isEmpty())
        assertTrue(EneaCsvParser.parse("").isEmpty())
    }

    @Test
    fun dropsBadRows() {
        val header = TestData.ENEA_CSV.lineSequence().first()
        val csv =
            header +
                "\n" +
                "\"=\"\"xxxx\"\"\";\"1,0\";\"0,0\";\"1,0\";\"0,0\"\n" +
                "\"=\"\"2024-05-01 05:59\"\"\";\"abc\";\"0,0\";\"1,0\";\"0,0\"\n" +
                "\"=\"\"2024-05-01 06:59\"\"\";\"1,0\"\n" +
                "\"=\"\"2024-05-01 07:59\"\"\";\"1,25\";\"0,5\";\"0,75\";\"0,0\"\n" +
                "\"=\"\"2024-05-01 08:59\"\"\";\"1,0\";\"---\";\"1,0\";\"0,0\"\n"
        assertEquals(listOf(rec(at(2024, 5, 1, 7), 1.25, 0.5, 0.75, 0.0)), EneaCsvParser.parse(csv))
    }

    @Test
    fun parsesTimestampVariants() {
        assertEquals(at(2024, 5, 1, 4), EneaCsvParser.parseTimestamp("=\"2024-05-01 04:59\""))
        assertEquals(at(2024, 5, 1, 23), EneaCsvParser.parseTimestamp("2024-05-01 23:59:59"))
        assertEquals(null, EneaCsvParser.parseTimestamp("=\"\""))
    }

    @Test
    fun mergeSortsAndDedupesKeepingLast() {
        val a = listOf(rec(at(2024, 1, 2), 1.0), rec(at(2024, 1, 1), 1.0))
        val b = listOf(rec(at(2024, 1, 2), 9.0), rec(at(2024, 1, 3), 1.0))
        val m = mergeRecords(listOf(a, b))
        assertEquals(listOf(at(2024, 1, 1), at(2024, 1, 2), at(2024, 1, 3)), m.map { it.timestamp })
        assertEquals(9.0, m[1].poborPrzed, 0.0)
    }
}
