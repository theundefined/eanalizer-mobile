package com.theundefined.eanalizer.data.repository

import android.content.Context
import android.webkit.WebSettings
import com.theundefined.eanalizer.data.local.DataFiles
import com.theundefined.eanalizer.data.local.SettingsStore
import com.theundefined.eanalizer.data.local.WebViewCookieJar
import com.theundefined.eanalizer.data.remote.EneaClient
import com.theundefined.eanalizer.data.remote.EneaCustomer
import com.theundefined.eanalizer.data.remote.EneaProtocolException
import com.theundefined.eanalizer.data.remote.RceClient
import com.theundefined.eanalizer.data.remote.SessionExpiredException
import com.theundefined.eanalizer.domain.HourlyRecord
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Several customers on the account and none selected yet - the user has to pick one. */
class CustomerSelectionRequiredException(val customers: List<EneaCustomer>) :
    Exception("customer selection required")

/** Result of a sync: years downloaded now and years that Enea returned empty. */
data class SyncResult(val downloaded: List<Int>, val empty: List<Int>)

/**
 * Orchestrates eBOK session, CSV sync and local storage, plus PSE prices. Errors are typed
 * exceptions ([SessionExpiredException], [CustomerSelectionRequiredException],
 * [EneaProtocolException], [java.io.IOException]); user-facing text comes from the UI.
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
            if (!enea.isLoggedIn()) {
                settings.loggedIn = false
                throw SessionExpiredException()
            }
            settings.loggedIn = true
            selectCustomer()
            val info = enea.meterInfo()
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
            SyncResult(downloaded, empty)
        }

    /** Selects the configured customer; with one customer selects it, with none skips. */
    private fun selectCustomer() {
        val customers = enea.customers()
        settings.customers = customers
        if (customers.isEmpty()) return
        val chosen =
            customers.firstOrNull { it.number == settings.customerNumber }
                ?: customers.singleOrNull()
                ?: throw CustomerSelectionRequiredException(customers)
        settings.customerNumber = chosen.number
        enea.selectCustomer(chosen.guid)
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
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Pair<Map<LocalDateTime, Double>, Int> = rce.hourlyPrices(from, to, onProgress)

    suspend fun rcemPrices(months: Collection<YearMonth>): Map<YearMonth, Double> =
        rce.monthlyPrices(months)

    /** Writes [content] to `cacheDir/export/<name>` for sharing via FileProvider. */
    fun exportFile(name: String, content: String): File {
        val dir = File(appContext.cacheDir, "export").apply { mkdirs() }
        return File(dir, name).apply { writeText(content) }
    }
}
