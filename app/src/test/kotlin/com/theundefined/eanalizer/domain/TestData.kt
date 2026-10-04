package com.theundefined.eanalizer.domain

import java.time.LocalDateTime

/** Shared fixtures mirroring eanalizer `tests/test_data.csv` and the test tariff file. */
object TestData {
    const val ENEA_CSV =
        "Data;Wolumen energii elektrycznej pobranej z sieci przed bilansowaniem godzinowym;" +
            "Wolumen energii elektrycznej oddanej do sieci przed bilansowaniem godzinowym;" +
            "Wolumen energii elektrycznej pobranej z sieci po bilansowaniu godzinowym;" +
            "Wolumen energii elektrycznej oddanej do sieci po bilansowaniu godzinowym\n" +
            "\"=\"\"2024-05-01 04:59\"\"\";\"1,0\";\"0,0\";\"1,0\";\"0,0\"\n" +
            "\"=\"\"2024-05-01 10:59\"\"\";\"0,5\";\"3,0\";\"0,0\";\"2,5\"\n" +
            "\"=\"\"2024-05-01 22:59\"\"\";\"2,0\";\"0,0\";\"2,0\";\"0,0\"\n" +
            "\"=\"\"2024-05-02 11:59\"\"\";\"2,5\";\"0,0\";\"2,5\";\"0,0\"\n" +
            "\"=\"\"2024-05-04 10:59\"\"\";\"0,2\";\"5,0\";\"0,0\";\"4,8\"\n"

    /** Tariff used by eanalizer `test_core.py`. */
    val coreTariffs =
        TariffTable.parseCsv(
            """
            tariff,zone_name,day_type,start_hour,end_hour,energy_price,dist_price,dist_fee
            G12w,szczytowa,weekday,6,21,0.78,0.30,10.0
            G12w,pozaszczytowa,weekday,0,6,0.46,0.30,10.0
            G12w,pozaszczytowa,weekday,21,24,0.46,0.30,10.0
            G12w,pozaszczytowa,weekend,0,24,0.46,0.30,10.0
            """
                .trimIndent()
        )

    val records: List<HourlyRecord> by lazy { EneaCsvParser.parse(ENEA_CSV) }

    fun rec(
        ts: LocalDateTime,
        pp: Double = 0.0,
        op: Double = 0.0,
        p: Double = 0.0,
        o: Double = 0.0
    ) = HourlyRecord(ts, pp, op, p, o)

    fun at(y: Int, m: Int, d: Int, h: Int = 0): LocalDateTime = LocalDateTime.of(y, m, d, h, 0)
}
