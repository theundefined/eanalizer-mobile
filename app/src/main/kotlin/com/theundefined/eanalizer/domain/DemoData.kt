package com.theundefined.eanalizer.domain

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/**
 * Synthetic hourly meter data for the demo mode (store reviewers, screenshots, trying the app
 * without an Enea eBOK account). Deterministic: the same [today] always gives the same records. A
 * household with a small PV installation: evening peak, summer production around noon.
 */
object DemoData {
    const val DAYS = 760

    fun records(today: LocalDate = LocalDate.now()): List<HourlyRecord> {
        val end = today.minusDays(1)
        val start = end.minusDays(DAYS - 1L)
        val out = ArrayList<HourlyRecord>(DAYS * 24)
        var day = start
        while (!day.isAfter(end)) {
            for (hour in 0 until 24) out +=
                hourly(LocalDateTime.of(day, java.time.LocalTime.of(hour, 0)))
            day = day.plusDays(1)
        }
        return out
    }

    private fun hourly(ts: LocalDateTime): HourlyRecord {
        val hour = ts.hour
        val winter = 0.5 + 0.5 * sin(2 * PI * (ts.dayOfYear + 80) / 365.0) // 1 = mid-winter
        val weekend = ts.dayOfWeek.value >= 6
        val base = 0.18 + 0.05 * noise(ts, 1)
        val morning = if (hour in 6..8) 0.35 else 0.0
        val evening = if (hour in 17..22) (if (weekend) 0.8 else 0.65) else 0.0
        val day = if (weekend && hour in 10..15) 0.3 else 0.0
        val heating = winter * (0.15 + if (hour in 17..22) 0.25 else 0.0)
        val consumption =
            (base + (morning + evening + day) * (0.7 + 0.6 * noise(ts, 2)) + heating).round3()
        // PV 4 kWp: bell around noon, stronger in summer, clouds as noise.
        val sun = if (hour in 5..19) max(0.0, sin(PI * (hour - 5) / 14.0)) else 0.0
        val production =
            (4.0 *
                    sun *
                    (1.0 - winter) *
                    0.8 *
                    (0.35 + 0.65 * noise(ts.toLocalDate().atStartOfDay(), 3)))
                .round3()
        return HourlyRecord(
            timestamp = ts,
            poborPrzed = consumption,
            oddaniePrzed = production,
            pobor = max(0.0, consumption - production).round3(),
            oddanie = max(0.0, production - consumption).round3(),
        )
    }

    /** Deterministic pseudo-random value in 0..1. */
    private fun noise(ts: LocalDateTime, salt: Int): Double {
        var h = ts.year * 31 + ts.dayOfYear
        h = h * 31 + ts.hour
        h = h * 31 + salt
        h = h xor (h ushr 15)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535.0
    }

    private fun Double.round3() = Math.round(this * 1000) / 1000.0
}
