package com.theundefined.eanalizer.ui.components

import com.theundefined.eanalizer.domain.TariffTable
import org.junit.Assert.assertEquals
import org.junit.Test

class ZoneHoursTest {
    private val table = TariffTable.default()

    @Test
    fun rangesAcrossMidnightAreJoined() {
        val g12 = table.hourlyZones("G12", weekend = false)
        assertEquals("22–6, 13–15", zoneHours(g12, "nocna"))
        assertEquals("6–13, 15–22", zoneHours(g12, "dzienna"))
        assertEquals("21–6", zoneHours(table.hourlyZones("G12w", false), "pozaszczytowa"))
        assertEquals("0–24", zoneHours(table.hourlyZones("G11", false), "stala"))
    }
}
