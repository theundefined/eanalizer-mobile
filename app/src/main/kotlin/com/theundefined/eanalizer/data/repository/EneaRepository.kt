package com.theundefined.eanalizer.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.WebSettings
import com.theundefined.eanalizer.data.local.DataFiles
import com.theundefined.eanalizer.data.local.InvalidCsvException
import com.theundefined.eanalizer.data.local.LocalData
import com.theundefined.eanalizer.data.local.SettingsStore
import com.theundefined.eanalizer.data.local.WebViewCookieJar
import com.theundefined.eanalizer.data.remote.EneaClient
import com.theundefined.eanalizer.data.remote.EneaCustomer
import com.theundefined.eanalizer.data.remote.EneaMeterInfo
import com.theundefined.eanalizer.data.remote.EneaProtocolException
import com.theundefined.eanalizer.data.remote.RceClient
import com.theundefined.eanalizer.data.remote.SessionExpiredException
import com.theundefined.eanalizer.domain.BackupZip
import com.theundefined.eanalizer.domain.DemoData
import io.sentry.Sentry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
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

/** Imported files are a year of hourly rows (~1 MB); anything far larger is not an Enea CSV. */
private const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

/** `readNBytes` needs API 33. */
private fun InputStream.readAtMost(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buf = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

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

    suspend fun loadData(): LocalData =
        withContext(Dispatchers.IO) {
            if (settings.demoMode) LocalData(DemoData.records(), emptyList()) else files.load()
        }

    /**
     * Imports CSV files picked by the user (e.g. downloaded from the eBOK page by hand). Returns
     * the names that were rejected ([InvalidCsvException], too large or unreadable).
     */
    suspend fun importFiles(uris: List<Uri>): List<String> =
        withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                val name = displayName(uri) ?: uri.lastPathSegment ?: "dane.csv"
                try {
                    val bytes =
                        appContext.contentResolver.openInputStream(uri)?.use {
                            it.readAtMost(MAX_IMPORT_BYTES + 1)
                        } ?: throw IOException("cannot open $uri")
                    if (bytes.size > MAX_IMPORT_BYTES) throw IOException("too large")
                    files.import(name, bytes)
                    null
                } catch (e: InvalidCsvException) {
                    name
                } catch (e: IOException) {
                    Sentry.captureException(e)
                    "$name (${e.javaClass.simpleName}: ${e.message})"
                } catch (e: SecurityException) {
                    Sentry.captureException(e)
                    "$name (${e.javaClass.simpleName}: ${e.message})"
                }
            }
        }

    private fun displayName(uri: Uri): String? =
        runCatching {
                appContext.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            }
            .getOrNull()

    /** Writes a ZIP with all data files and the report settings; returns the number of files. */
    suspend fun createBackup(target: Uri): Int =
        withContext(Dispatchers.IO) {
            val data = files.backupEntries()
            val entries =
                data + BackupZip.Entry(BackupZip.SETTINGS, settings.backupJson().toByteArray())
            val out =
                appContext.contentResolver.openOutputStream(target, "wt")
                    ?: throw IOException("cannot open $target")
            out.use { BackupZip.write(it, entries) }
            data.size
        }

    /**
     * Restores a backup ZIP: data files are only added or enlarged, never lost, and the report
     * settings are applied. Returns the number of files written; throws [IOException] when the
     * archive holds nothing recognisable.
     */
    suspend fun restoreBackup(source: Uri): Int =
        withContext(Dispatchers.IO) {
            val entries =
                appContext.contentResolver.openInputStream(source)?.use { BackupZip.read(it) }
                    ?: throw IOException("cannot open $source")
            if (entries.isEmpty()) throw IOException("not a backup")
            entries
                .firstOrNull { it.name == BackupZip.SETTINGS }
                ?.let { settings.restoreBackup(String(it.bytes)) }
            files.restore(entries)
        }

    /** The stored data file [name]; null when it is gone. */
    fun dataFile(name: String, imported: Boolean): File? = files.file(name, imported)

    fun deleteDataFile(name: String, imported: Boolean) = files.delete(name, imported)
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
        if (settings.localOnly) return SyncResult(emptyList(), emptyList())
        if (!enea.isLoggedIn()) {
            settings.loggedIn = false
            throw SessionExpiredException()
        }
        settings.loggedIn = true
        settings.sessionCheckedAt = System.currentTimeMillis()
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

    /** Switching customers drops data downloaded for the previous one (imported files stay). */
    fun chooseCustomer(number: String) {
        if (settings.customerNumber != number) {
            files.clearDownloaded()
            settings.lastSync = 0L
        }
        settings.customerNumber = number
    }

    /** Called by the login WebView once it reached the logged-in eBOK. */
    fun onWebLoginFinished() {
        val now = System.currentTimeMillis()
        settings.loggedIn = true
        settings.loginAt = now
        settings.sessionCheckedAt = now
        settings.sessionExpiryNotified = false
    }

    /** Forgets the session cookies; downloaded data stays. */
    fun logout() {
        cookieJar.clear()
        settings.loggedIn = false
        settings.loginAt = 0L
        settings.sessionCheckedAt = 0L
    }

    /** Removes downloaded and imported data, session, credentials and customer choice. */
    fun clearAll() {
        logout()
        files.clear()
        settings.customers = emptyList()
        settings.customerNumber = null
        settings.lastSync = 0L
        settings.email = ""
        settings.password = ""
        settings.demoMode = false
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
