package com.theundefined.eanalizer.domain

import com.theundefined.eanalizer.domain.TestData.at
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TariffsTest {
    private val table = TariffTable.default()

    @Test
    fun g11SingleZone() {
        val z = table.resolve(at(2025, 5, 1, 10), "G11")!!
        assertEquals("stala", z.zone)
        assertEquals(0.61254, z.energyPrice, 1e-9)
        assertEquals(0.35547, z.distPrice, 1e-9)
        assertEquals(0.96801, z.price, 1e-9)
    }

    @Test
    fun g12OvernightZones() {
        assertEquals("nocna", table.resolve(at(2025, 4, 2, 4), "G12")!!.zone)
        assertEquals("nocna", table.resolve(at(2025, 4, 2, 13), "G12")!!.zone)
        assertEquals("nocna", table.resolve(at(2025, 4, 2, 14), "G12")!!.zone)
        assertEquals("dzienna", table.resolve(at(2025, 4, 2, 15), "G12")!!.zone)
        assertEquals("dzienna", table.resolve(at(2025, 4, 2, 12), "G12")!!.zone)
        assertEquals("nocna", table.resolve(at(2025, 4, 2, 23), "G12")!!.zone)
        assertEquals("nocna", table.resolve(at(2025, 4, 2, 22), "G12")!!.zone)
        assertEquals("nocna", table.resolve(at(2025, 4, 2, 0), "G12")!!.zone)
        assertEquals("dzienna", table.resolve(at(2025, 4, 2, 6), "G12")!!.zone)
        // "all" rows ignore weekends/holidays
        assertEquals("dzienna", table.resolve(at(2025, 12, 25, 12), "G12")!!.zone)
    }

    @Test
    fun g12wWeekdaysWeekendsHolidays() {
        assertEquals("szczytowa", table.resolve(at(2025, 4, 2, 10), "G12w")!!.zone)
        assertEquals("pozaszczytowa", table.resolve(at(2025, 4, 2, 23), "G12w")!!.zone)
        assertEquals("szczytowa", table.resolve(at(2025, 4, 2, 20), "G12w")!!.zone)
        assertEquals("pozaszczytowa", table.resolve(at(2025, 4, 2, 21), "G12w")!!.zone)
        assertEquals("pozaszczytowa", table.resolve(at(2025, 4, 6, 10), "G12w")!!.zone)
        assertEquals("pozaszczytowa", table.resolve(at(2025, 5, 1, 10), "G12w")!!.zone)
        assertEquals("pozaszczytowa", table.resolve(at(2025, 12, 24, 10), "G12w")!!.zone)
        assertEquals("szczytowa", table.resolve(at(2024, 12, 24, 10), "G12w")!!.zone)
    }

    @Test
    fun caseInsensitiveAndUnknown() {
        assertEquals("szczytowa", table.resolve(at(2025, 4, 2, 10), "g12W")!!.zone)
        assertNull(table.resolve(at(2025, 4, 2, 10), "NIEISTNIEJACA"))
    }

    @Test
    fun customOvernightWeekdayZone() {
        val t =
            TariffTable.parseCsv(
                "${TariffTable.HEADER}\nX,noc,weekday,21,7,0.1,0.1,1\nX,dzien,weekday,7,21,0.5,0.1,1\n"
            )
        assertEquals("noc", t.resolve(at(2025, 4, 2, 21), "X")!!.zone)
        assertEquals("noc", t.resolve(at(2025, 4, 2, 6), "X")!!.zone)
        assertEquals("dzien", t.resolve(at(2025, 4, 2, 7), "X")!!.zone)
        assertNull(t.resolve(at(2025, 4, 5, 10), "X")) // Saturday, no weekend rows
    }

    @Test
    fun fixedFee() {
        assertEquals(43.4682, table.fixedFee("G11"), 1e-9)
        assertEquals(46.1004, table.fixedFee("G12"), 1e-9)
        assertEquals(55.0302, table.fixedFee("G12w"), 1e-9)
        assertEquals(55.0302, table.fixedFee("g12w"), 1e-9)
        assertEquals(0.0, table.fixedFee("NIEISTEJACA"), 0.0)
    }

    @Test
    fun tariffNames() {
        assertEquals(listOf("G11", "G12", "G12w"), table.tariffNames)
        assertEquals(9, table.zones.size)
    }

    @Test
    fun legacyDefaultDetected() {
        val legacy =
            TariffTable.parseCsv(
                """
                tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee
                G11,stala,all,0,24,0.61254,0.35547,43.4682
                G12,nocna,all,22,6,0.414387,0.165681,46.1004
                G12,dzienna,all,6,22,0.710817,0.395199,46.1004
                G12w,pozaszczytowa,weekday,0,6,0.426195,0.153381,55.0302
                G12w,szczytowa,weekday,6,22,0.801714,0.385728,55.0302
                G12w,pozaszczytowa,weekday,22,24,0.426195,0.153381,55.0302
                G12w,pozaszczytowa,weekend,0,24,0.426195,0.153381,55.0302
                """
                    .trimIndent()
            )
        assertTrue(TariffTable.isLegacyDefault(legacy))
        assertFalse(TariffTable.isLegacyDefault(table))
    }

    @Test
    fun csvRoundTrip() {
        assertEquals(TariffTable.DEFAULT_CSV, table.toCsv())
        assertEquals(table, TariffTable.parseCsv(table.toCsv()))
    }

    @Test
    fun parseSkipsBadRowsAndHandlesBomAndColumnOrder() {
        val csv =
            "﻿zone_name,tariff,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee\r\n" +
                "stala,G11,all,0,24,0.5,0.25,10\r\n" +
                "zla,G11,sometimes,0,24,0.5,0.25,10\r\n" +
                "zla,G11,all,x,24,0.5,0.25,10\r\n" +
                "krotka,G11,all\r\n"
        val t = TariffTable.parseCsv(csv)
        assertEquals(
            listOf(TariffZone("G11", "stala", DayType.ALL, 0, 24, 0.5, 0.25, 10.0)),
            t.zones,
        )
        assertEquals(
            "tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee\nG11,stala,all,0,24,0.5,0.25,10\n",
            t.toCsv()
        )
    }
}
