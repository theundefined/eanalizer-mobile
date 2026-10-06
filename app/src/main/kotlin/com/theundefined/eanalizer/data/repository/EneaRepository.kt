package com.theundefined.eanalizer.data.repository

import android.content.Context
import android.net.Uri
import android.webkit.WebSettings
import com.theundefined.eanalizer.data.local.DataFiles
import com.theundefined.eanalizer.data.local.SettingsStore
import com.theundefined.eanalizer.data.local.WebViewCookieJar
import com.theundefined.eanalizer.data.remote.EneaClient
import com.theundefined.eanalizer.data.remote.EneaCustomer
import com.theundefined.eanalizer.data.remote.EneaMeterInfo
import com.theundefined.eanalizer.data.remote.EneaProtocolException
import com.theundefined.eanalizer.data.remote.RceClient
import com.theundefined.eanalizer.data.remote.SessionExpiredException
import com.theundefined.eanalizer.domain.HourlyRecord
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Several customers on the account and none selected yet - the user has to pick one. */
class CustomerSelectionRequiredException(val customers: List<EneaCustomer>) :
    Exception("customer selection required")

/** No customer on the account has hourly meter data. */
class NoMeterDataException : Exception("no customer with hourly data")

private val syncLock = Mutex()

/** Result of a sync: years downloaded now and years that Enea returned empty. */
data class SyncResult(val downloaded: List<Int>, val empty: List<Int>)

/**
 * Orchestrates eBOK session, CSV sync and local storage, plus PSE prices. Errors are typed
 * exceptions ([SessionExpiredException], [CustomerSelectionRequiredException],
 * [NoMeterDataException], [EneaProtocolException], [java.io.IOException]); user-facing text comes
 * from the UI.
 */
class EneaRepository(context: Context) {
    private val appContext = context.applicationContext
    val settings = SettingsStore(appContext)
    private val files = DataFiles(appContext)
    private val cookieJar by lazy { WebViewCookieJar() }
    private val userAgent by lazy { WebSettings.getDefaultUserAgent(appContext) }
    private val enea by lazy { EneaClient(cookieJar) { userAgent } }
    private val rce = RceClient(File(appContext.filesDir, "prices"))

    suspend fun loadRecords(): List<HourlyRecord> =
        withContext(Dispatchers.IO) { files.loadRecords() }

    fun dataFiles(): List<DataFiles.YearFile> = files.list()

    /**
     * Downloads missing/outdated years. With [force] every available year is downloaded again.
     * Throws [SessionExpiredException] when the user must log in in the WebView.
     */
    suspend fun sync(
        force: Boolean = false,
        onYear: (year: Int) -> Unit = {},
    ): SyncResult =
        withContext(Dispatchers.IO) {
            // The app and the background job may sync at the same time.
            syncLock.withLock { syncLocked(force, onYear) }
        }

    private fun syncLocked(force: Boolean, onYear: (year: Int) -> Unit): SyncResult {
        if (!enea.isLoggedIn()) {
            settings.loggedIn = false
            throw SessionExpiredException()
        }
        settings.loggedIn = true
        val info = selectCustomer() ?: throw NoMeterDataException()
        val downloaded = ArrayList<Int>()
        val empty = ArrayList<Int>()
        for (year in info.minYear..info.maxYear) {
            if (!force && !files.needsDownload(year)) continue
            onYear(year)
            val csv = enea.downloadYear(year, info.pointOfDeliveryId)
            if (csv == null) empty += year
            else {
                files.write(year, csv)
                downloaded += year
            }
        }
        settings.lastSync = System.currentTimeMillis()
        return SyncResult(downloaded, empty)
    }

    /**
     * Selects the configured customer and returns its meter info. Like eanalizer's setup, only
     * customers with hourly data (a point of delivery on the meter page) are offered: when the
     * configured one is missing or has no data, every customer is probed; a single one with data is
     * chosen automatically, several make the user pick. Without the selection page (one customer)
     * the current one is used.
     */
    private fun selectCustomer(): EneaMeterInfo? {
        val customers = enea.customers()
        if (customers.isEmpty()) {
            settings.customers = customers
            return enea.meterInfoOrNull()
        }
        customers
            .firstOrNull { it.number == settings.customerNumber }
            ?.let { configured ->
                enea.selectCustomer(configured.guid)
                enea.meterInfoOrNull()?.let { info ->
                    settings.customers =
                        settings.customers.filter { it in customers }.ifEmpty { listOf(configured) }
                    return info
                }
            }
        val withData =
            customers.mapNotNull { c ->
                enea.selectCustomer(c.guid)
                enea.meterInfoOrNull()?.let { c to it }
            }
        settings.customers = withData.map { it.first }
        val (chosen, info) =
            when (withData.size) {
                0 -> throw NoMeterDataException()
                1 -> withData.single()
                else -> throw CustomerSelectionRequiredException(withData.map { it.first })
            }
        settings.customerNumber = chosen.number
        // The probe left the last customer selected on the server.
        if (chosen != customers.last()) enea.selectCustomer(chosen.guid)
        return info
    }

    /** Switching customers drops data downloaded for the previous one. */
    fun chooseCustomer(number: String) {
        if (settings.customerNumber != number) {
            files.clear()
            settings.lastSync = 0L
        }
        settings.customerNumber = number
    }

    /** Called by the login WebView once it reached the logged-in eBOK. */
    fun onWebLoginFinished() {
        settings.loggedIn = true
        settings.sessionExpiryNotified = false
    }

    /** Forgets the session cookies; downloaded data stays. */
    fun logout() {
        cookieJar.clear()
        settings.loggedIn = false
    }

    /** Removes downloaded data, session, credentials and customer choice. */
    fun clearAll() {
        logout()
        files.clear()
        settings.customers = emptyList()
        settings.customerNumber = null
        settings.lastSync = 0L
        settings.email = ""
        settings.password = ""
    }

    suspend fun rcePrices(
        from: LocalDate,
        to: LocalDate,
        force: Boolean = false,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<Map<LocalDateTime, Double>, Int> = rce.hourlyPrices(from, to, force, onProgress)

    /** Time (epoch ms) of the last successful PSE download, 0 = never. */
    suspend fun pricesFetchedAt(): Long = withContext(Dispatchers.IO) { rce.lastFetch }

    suspend fun rcemPrices(
        months: Collection<YearMonth>,
        force: Boolean = false,
    ): Map<YearMonth, Double> = rce.monthlyPrices(months, force)

    /** Writes `cacheDir/export/<name>` with [write] for sharing via FileProvider. */
    fun exportFile(name: String, write: (OutputStream) -> Unit): File {
        val dir = File(appContext.cacheDir, "export").apply { mkdirs() }
        return File(dir, name).apply { outputStream().buffered().use(write) }
    }

    /** Copies [file] to a document picked by the user (Storage Access Framework). */
    fun copyTo(file: File, target: Uri) {
        val out =
            appContext.contentResolver.openOutputStream(target, "wt")
                ?: throw IOException("cannot open $target")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }
}
