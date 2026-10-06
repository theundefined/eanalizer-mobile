package com.theundefined.eanalizer.data.remote

import com.theundefined.eanalizer.domain.RceAnalysis
import com.theundefined.eanalizer.domain.RcemParser
import io.sentry.Sentry
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
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

    private val lastFetchFile = File(cacheDir, "last_fetch")

    /** Days whose download failed, with the date of the attempt (not retried the same day). */
    private val failedDays = ConcurrentHashMap<LocalDate, LocalDate>()

    /** Date of a failed RCEm page download (not retried the same day). */
    @Volatile private var rcemFailedOn: LocalDate? = null

    /** Time (epoch ms) of the last successful download from PSE, 0 = never. */
    val lastFetch: Long
        get() = runCatching { lastFetchFile.readText().trim().toLong() }.getOrDefault(0L)

    private fun markFetched() {
        cacheDir.mkdirs()
        lastFetchFile.writeText(System.currentTimeMillis().toString())
    }

    /**
     * Hourly RCE prices (zł/kWh) for `[from, to]`. Days before 07.2024 have no RCE. Days that fail
     * to download are skipped (not cached) and counted in the second value; they are tried again
     * the next day or with [force].
     */
    suspend fun hourlyPrices(
        from: LocalDate,
        to: LocalDate,
        force: Boolean = false,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Pair<Map<LocalDateTime, Double>, Int> = coroutineScope {
        val start = maxOf(from, DATA_START)
        val days = generateSequence(start) { it.plusDays(1) }.takeWhile { it <= to }.toList()
        val today = LocalDate.now()
        if (force) failedDays.clear()
        val semaphore = Semaphore(6)
        var done = 0
        var error: Throwable? = null
        val results =
            days
                .map { day ->
                    async(Dispatchers.IO) {
                        semaphore.withPermit {
                            val entries =
                                if (failedDays[day] == today) null
                                else
                                    try {
                                        day(day)
                                    } catch (e: IOException) {
                                        failedDays[day] = today
                                        synchronized(this@RceClient) {
                                            if (error == null) error = e
                                        }
                                        null
                                    }
                            synchronized(this@RceClient) { onProgress(++done, days.size) }
                            entries
                        }
                    }
                }
                .awaitAll()
        // One event per batch, not per day.
        error?.let { Sentry.captureException(it) }
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
            try {
                json.parseToJsonElement(body).jsonObject["value"] as? JsonArray
            } catch (e: IllegalArgumentException) {
                throw IOException("PSE invalid JSON", e)
            } ?: JsonArray(emptyList())
        val entries =
            value.mapNotNull { e ->
                val o = e.jsonObject
                val dtime = o["dtime"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val price = o["rce_pln"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                Entry(dtime, price)
            }
        // Prices for a day not published yet come back empty - don't cache those.
        if (entries.isNotEmpty()) {
            markFetched()
            if (day < LocalDate.now()) cache.writeText(json.encodeToString(entries))
        }
        return entries
    }

    /**
     * Monthly RCEm (zł/kWh) for [months]. The PSE page is downloaded again only when a needed month
     * is missing and the cache is from before today; with [force] it is always downloaded and a
     * failure is thrown. Otherwise missing months are simply absent from the map.
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
            val today = LocalDate.now()
            val fresh =
                rcemFailedOn == today || (cache != null && cache.fetchedAt.toLocalDate() >= today)
            if (force || (months.any { it !in prices } && !fresh)) {
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
                            .ifEmpty { throw IOException("PSE RCEm page without prices") }
                    } catch (e: IOException) {
                        rcemFailedOn = today
                        Sentry.captureException(e)
                        if (force) throw e
                        emptyMap()
                    }
                if (parsed.isNotEmpty()) {
                    rcemFailedOn = null
                    markFetched()
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

    private fun Long.toLocalDate(): LocalDate =
        Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    private companion object {
        const val FETCH_ATTEMPTS = 3
        const val API = "https://api.raporty.pse.pl/api/rce-pln"
        const val RCEM_URL =
            "https://www.pse.pl/oire/rcem-rynkowa-miesieczna-cena-energii-elektrycznej"
        val DATA_START: LocalDate = LocalDate.of(2024, 7, 1)
    }
}
