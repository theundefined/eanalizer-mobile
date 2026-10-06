package com.theundefined.eanalizer.data.remote

import com.theundefined.eanalizer.domain.RceAnalysis
import com.theundefined.eanalizer.domain.RcemParser
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * PSE market prices (port of eanalizer `price_fetcher.py`): hourly RCE from the PSE API (one
 * request per day, cached per day in [cacheDir]) and monthly RCEm scraped from the PSE page.
 */
class RceClient(private val cacheDir: File) {
    private val http =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable private data class Entry(val dtime: String, val price: Double)

    @Serializable
    private data class RcemCache(val fetchedAt: Long, val prices: Map<String, Double>)

    /**
     * Hourly RCE prices (zł/kWh) for `[from, to]`. Days before 07.2024 have no RCE. Days that fail
     * to download are skipped (not cached) and counted in the second value.
     */
    suspend fun hourlyPrices(
        from: LocalDate,
        to: LocalDate,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<Map<LocalDateTime, Double>, Int> = coroutineScope {
        val start = maxOf(from, DATA_START)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= to }.toList()
        val semaphore = Semaphore(6)
        var done = 0
        val results =
            days
                .map { day ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val entries = runCatching { day(day) }.getOrNull()
                            synchronized(this@RceClient) { onProgress(++done, days.size) }
                            entries
                        }
                    }
                }
                .awaitAll()
        val entries = results.filterNotNull().flatten().map { it.dtime to it.price }
        RceAnalysis.hourlyPrices(entries) to results.count { it == null }
    }

    private suspend fun day(day: LocalDate): List<Entry> {
        val dir = File(cacheDir, "rce").apply { mkdirs() }
        val cache = File(dir, "$day.json")
        if (cache.isFile) {
            runCatching { json.decodeFromString<List<Entry>>(cache.readText()) }
                .getOrNull()
                ?.let {
                    return it
                }
        }
        val filter = URLEncoder.encode("business_date eq '$day'", "UTF-8").replace("+", "%20")
        val url = "$API?\$filter=$filter&\$orderby=business_date%20asc&\$first=20000"
        val body = fetch(Request.Builder().url(url).build())
        val value =
            json.parseToJsonElement(body).jsonObject["value"] as? JsonArray
                ?: JsonArray(emptyList())
        val entries =
            value.mapNotNull { e ->
                val o = e.jsonObject
                val dtime = o["dtime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val price = o["rce_pln"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                Entry(dtime, price)
            }
        // Prices for a day not published yet come back empty - don't cache those.
        if (entries.isNotEmpty() && day < LocalDate.now()) {
            cache.writeText(json.encodeToString(entries))
        }
        return entries
    }

    /**
     * Monthly RCEm (zł/kWh) for [months]. The PSE page is downloaded again only when a needed month
     * is missing and the cache is older than 12 h (or regardless of its age with [force]). Missing
     * months are simply absent from the map.
     */
    suspend fun monthlyPrices(
        months: Collection<YearMonth>,
        force: Boolean = false,
    ): Map<YearMonth, Double> =
        withContext(Dispatchers.IO) {
            val cacheFile = File(cacheDir, "rcem.json")
            val cache =
                runCatching { json.decodeFromString<RcemCache>(cacheFile.readText()) }.getOrNull()
            var prices = cache?.prices?.mapKeys { YearMonth.parse(it.key) } ?: emptyMap()
            val fresh =
                cache != null && System.currentTimeMillis() - cache.fetchedAt < RCEM_MAX_AGE_MS
            if (months.any { it !in prices } && (force || !fresh)) {
                val parsed =
                    try {
                        RcemParser.parse(
                            fetch(
                                Request.Builder()
                                    .url(RCEM_URL)
                                    .header("User-Agent", "Mozilla/5.0")
                                    .build()
                            )
                        )
                    } catch (e: IOException) {
                        emptyMap()
                    }
                if (parsed.isNotEmpty()) {
                    prices = prices + parsed
                    cacheDir.mkdirs()
                    cacheFile.writeText(
                        json.encodeToString(
                            RcemCache(
                                System.currentTimeMillis(),
                                prices.mapKeys { it.key.toString() },
                            )
                        )
                    )
                }
            }
            prices.filterKeys { it in months }
        }

    /** GET with a few retries - PSE occasionally times out or answers 5xx. */
    private suspend fun fetch(request: Request): String {
        var attempt = 0
        while (true) {
            try {
                return http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("PSE HTTP ${resp.code}")
                    resp.body?.string() ?: ""
                }
            } catch (e: IOException) {
                if (++attempt >= FETCH_ATTEMPTS) throw e
                delay(1000L * attempt)
            }
        }
    }

    private companion object {
        const val FETCH_ATTEMPTS = 3
        const val API = "https://api.raporty.pse.pl/api/rce-pln"
        const val RCEM_URL =
            "https://www.pse.pl/oire/rcem-rynkowa-miesieczna-cena-energii-elektrycznej"
        const val RCEM_MAX_AGE_MS = 12 * 3600 * 1000L
        val DATA_START: LocalDate = LocalDate.of(2024, 7, 1)
    }
}
