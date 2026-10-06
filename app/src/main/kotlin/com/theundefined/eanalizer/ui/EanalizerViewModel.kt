package com.theundefined.eanalizer.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theundefined.eanalizer.data.local.AnalysisPrefs
import com.theundefined.eanalizer.data.local.SettlementMode
import com.theundefined.eanalizer.data.remote.EneaProtocolException
import com.theundefined.eanalizer.data.remote.SessionExpiredException
import com.theundefined.eanalizer.data.repository.CustomerSelectionRequiredException
import com.theundefined.eanalizer.data.repository.EneaRepository
import com.theundefined.eanalizer.data.repository.NoMeterDataException
import com.theundefined.eanalizer.domain.Aggregation
import com.theundefined.eanalizer.domain.AnalysisResult
import com.theundefined.eanalizer.domain.Analyzer
import com.theundefined.eanalizer.domain.CsvExport
import com.theundefined.eanalizer.domain.HourlyRecord
import com.theundefined.eanalizer.domain.NetBilling
import com.theundefined.eanalizer.domain.NetBillingValuation
import com.theundefined.eanalizer.domain.Periods
import com.theundefined.eanalizer.domain.RceAnalysis
import com.theundefined.eanalizer.domain.TariffTable
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.YearMonth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AnalysisInput(
    val records: List<HourlyRecord>?,
    val prefs: AnalysisPrefs,
    val tariffs: TariffTable,
    val pricesRetry: Int,
)

class EanalizerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = EneaRepository(application)
    private val settings = repo.settings

    sealed interface UiEvent {
        data class SyncFinished(val downloadedYears: Int) : UiEvent

        data class Error(val kind: ErrorKind, val detail: String? = null) : UiEvent

        /** The session is gone - show the login WebView. */
        data object LoginRequired : UiEvent

        data class Share(val file: File) : UiEvent
    }

    private val _uiState =
        MutableStateFlow(
            UiState(
                loggedIn = settings.loggedIn,
                lastSync = settings.lastSync,
                prefs = settings.prefs,
                tariffs = settings.tariffs,
                customers = settings.customers,
                customerNumber = settings.customerNumber,
            )
        )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val records = MutableStateFlow<List<HourlyRecord>?>(null)
    private var syncJob: Job? = null
    private var rceJob: Job? = null

    /** Bumped by [retryPrices] to recompute the analysis with freshly downloaded PSE prices. */
    private val pricesRetry = MutableStateFlow(0)
    private var pricesRetryDone = 0

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val email = settings.email
            val hasPassword = settings.password.isNotEmpty()
            _uiState.update { it.copy(email = email, hasPassword = hasPassword) }
        }
        // Cache-then-refresh: local data first, then a background sync when logged in.
        viewModelScope.launch {
            reloadRecords()
            if (settings.loggedIn) sync(quiet = true)
        }
        viewModelScope.launch {
            // Only records/prefs/tariffs matter; other state changes must not restart analysis.
            combine(records, _uiState, pricesRetry) { recs, s, retry ->
                    AnalysisInput(recs, s.prefs, s.tariffs, retry)
                }
                .distinctUntilChanged()
                .collectLatest { (recs, prefs, tariffs, retry) ->
                    if (recs == null) return@collectLatest
                    if (recs.isEmpty()) {
                        _uiState.update { it.copy(analysis = null, analyzing = false) }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(analyzing = true) }
                    val force = retry > pricesRetryDone
                    val analysis = analyze(recs, prefs, tariffs, forcePrices = force)
                    pricesRetryDone = retry
                    _uiState.update { it.copy(analysis = analysis, analyzing = false) }
                }
        }
    }

    private suspend fun reloadRecords() {
        val recs = repo.loadRecords()
        _uiState.update {
            it.copy(
                loadingLocal = false,
                hasData = recs.isNotEmpty(),
                dataStart = recs.firstOrNull()?.timestamp?.toLocalDate(),
                dataEnd = recs.lastOrNull()?.timestamp?.toLocalDate(),
                dataYears = repo.dataFiles().map { f -> f.year },
                rce = RceState(),
            )
        }
        records.value = recs
    }

    private suspend fun analyze(
        all: List<HourlyRecord>,
        prefs: AnalysisPrefs,
        tariffs: TariffTable,
        forcePrices: Boolean = false,
    ): Analysis? {
        val (from, to) =
            Periods.resolve(
                prefs.period,
                all.first().timestamp.toLocalDate(),
                all.last().timestamp.toLocalDate()
            )
        val recs = Periods.filter(all, from, to)
        if (recs.isEmpty()) return null
        val tariff =
            tariffs.tariffNames.firstOrNull { it.equals(prefs.tariff, ignoreCase = true) }
                ?: tariffs.tariffNames.firstOrNull()
                ?: return null
        val ratio = prefs.netMeteringRatio.takeIf { prefs.mode == SettlementMode.NET_METERING }

        // Net-billing prices (network, cached) before the CPU-heavy part.
        var rce = emptyMap<LocalDateTime, Double>()
        var rcem = emptyMap<YearMonth, Double>()
        var pricesUnavailable = false
        if (prefs.mode == SettlementMode.NET_BILLING) {
            val months = NetBilling.monthsOf(recs)
            rcem = runCatching { repo.rcemPrices(months, forcePrices) }.getOrDefault(emptyMap())
            if (prefs.valuation == NetBillingValuation.RCE) {
                rce = runCatching { repo.rcePrices(from, to).first }.getOrDefault(emptyMap())
            }
            pricesUnavailable = rcem.isEmpty() && rce.isEmpty()
        }

        return withContext(Dispatchers.Default) {
            fun settle(r: AnalysisResult) =
                if (prefs.mode == SettlementMode.NET_BILLING)
                    NetBilling.settle(
                        r.simulation,
                        tariffs,
                        r.tariff,
                        rce,
                        rcem,
                        prefs.valuation,
                        r.fixedFees,
                    )
                else null

            val result =
                Analyzer.runFullAnalysis(
                    recs,
                    prefs.capacity,
                    tariffs,
                    tariff,
                    ratio,
                    prefs.efficiency
                )
            val nb = settle(result)
            val comparison =
                Analyzer.compareTariffs(recs, prefs.capacity, tariffs, ratio, prefs.efficiency)
                    .map { r ->
                        val s = settle(r)
                        ComparisonRow(r.tariff, s?.calkowityKoszt ?: r.totalCost, r.fixedFees, s)
                    }
                    .sortedBy { it.totalCost }
            Analysis(
                from = from,
                to = to,
                recordCount = recs.size,
                result = result,
                netBilling = nb,
                comparison = comparison,
                optimalCapacity = Analyzer.optimalCapacity(recs, tariffs, tariff),
                trends = Analyzer.dailyTrends(recs),
                missingHours =
                    Periods.findMissingHours(recs, from, to).filterNot {
                        Periods.isProbableDstSpringGap(it)
                    },
                daily = Aggregation.daily(recs),
                monthly = Aggregation.monthly(recs),
                missingRcem = nb?.missingRcemMonths ?: emptyList(),
                pricesUnavailable = pricesUnavailable,
            )
        }
    }

    /** Downloads new data. [quiet] = background sync on start (no login prompt). */
    fun sync(force: Boolean = false, quiet: Boolean = false) {
        if (syncJob?.isActive == true) return
        syncJob =
            viewModelScope.launch {
                _uiState.update { it.copy(syncing = true) }
                try {
                    val res = repo.sync(force) { y -> _uiState.update { it.copy(syncYear = y) } }
                    _uiState.update {
                        it.copy(
                            loggedIn = true,
                            lastSync = settings.lastSync,
                            customers = settings.customers,
                            customerNumber = settings.customerNumber,
                        )
                    }
                    if (res.downloaded.isNotEmpty() || !_uiState.value.hasData) reloadRecords()
                    if (!quiet) _events.emit(UiEvent.SyncFinished(res.downloaded.size))
                } catch (e: SessionExpiredException) {
                    _uiState.update { it.copy(loggedIn = false) }
                    if (!quiet) _events.emit(UiEvent.LoginRequired)
                } catch (e: CustomerSelectionRequiredException) {
                    _uiState.update {
                        it.copy(customers = e.customers, customerChoice = e.customers)
                    }
                } catch (e: NoMeterDataException) {
                    _events.emit(UiEvent.Error(ErrorKind.NO_METER_DATA))
                } catch (e: EneaProtocolException) {
                    _events.emit(UiEvent.Error(ErrorKind.PROTOCOL, e.message))
                } catch (e: IOException) {
                    if (!quiet) _events.emit(UiEvent.Error(ErrorKind.NETWORK, e.message))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.emit(UiEvent.Error(ErrorKind.UNKNOWN, e.message))
                } finally {
                    _uiState.update { it.copy(syncing = false, syncYear = null) }
                }
            }
    }

    fun onWebLoginFinished() {
        repo.onWebLoginFinished()
        _uiState.update { it.copy(loggedIn = true) }
        sync()
    }

    fun chooseCustomer(number: String) {
        val changed = settings.customerNumber != number
        repo.chooseCustomer(number)
        _uiState.update { it.copy(customerNumber = number, customerChoice = null) }
        viewModelScope.launch {
            if (changed) reloadRecords()
            sync()
        }
    }

    fun dismissCustomerChoice() = _uiState.update { it.copy(customerChoice = null) }

    fun updatePrefs(transform: (AnalysisPrefs) -> AnalysisPrefs) {
        val p = transform(_uiState.value.prefs)
        settings.prefs = p
        _uiState.update { it.copy(prefs = p) }
    }

    fun saveTariffs(table: TariffTable) {
        settings.tariffs = table
        val t = settings.tariffs
        _uiState.update { it.copy(tariffs = t) }
    }

    fun resetTariffs() = saveTariffs(TariffTable.default())

    /** With [keepPassword] the stored password stays (the field was left empty). */
    fun saveCredentials(email: String, password: String, keepPassword: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            settings.email = email.trim()
            if (!keepPassword) settings.password = password
            val hasPassword = settings.password.isNotEmpty()
            _uiState.update { it.copy(email = email.trim(), hasPassword = hasPassword) }
        }
    }

    /** Credentials for prefilling the login form (read off the main thread). */
    suspend fun credentials(): Pair<String, String> =
        withContext(Dispatchers.IO) { settings.email to settings.password }

    fun logout() {
        repo.logout()
        _uiState.update { it.copy(loggedIn = false) }
    }

    fun clearAll() {
        syncJob?.cancel()
        repo.clearAll()
        _uiState.update {
            it.copy(
                loggedIn = false,
                lastSync = 0L,
                customers = emptyList(),
                customerNumber = null,
                email = "",
                hasPassword = false,
            )
        }
        viewModelScope.launch { reloadRecords() }
    }

    /**
     * Downloads PSE prices again (failed RCE days are never cached) and recomputes the analysis.
     */
    fun retryPrices() {
        pricesRetry.update { it + 1 }
    }

    /** Values the selected period at hourly RCE prices. */
    fun loadRce() {
        val analysis = _uiState.value.analysis ?: return
        val all = records.value ?: return
        rceJob?.cancel()
        rceJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(rce = RceState(loading = true, from = analysis.from, to = analysis.to))
                }
                val (prices, failed) =
                    repo.rcePrices(analysis.from, analysis.to) { done, total ->
                        _uiState.update { it.copy(rce = it.rce.copy(done = done, total = total)) }
                    }
                val result =
                    withContext(Dispatchers.Default) {
                        RceAnalysis.run(Periods.filter(all, analysis.from, analysis.to), prices)
                    }
                _uiState.update {
                    it.copy(
                        rce = it.rce.copy(loading = false, result = result, failedDays = failed)
                    )
                }
            }
    }

    enum class ExportKind {
        SIMULATION,
        DAILY,
        MONTHLY,
    }

    fun export(kind: ExportKind) {
        val a = _uiState.value.analysis ?: return
        viewModelScope.launch {
            val file =
                withContext(Dispatchers.IO) {
                    val (name, content) =
                        when (kind) {
                            ExportKind.SIMULATION ->
                                "symulacja_${a.result.tariff}_${a.from}_${a.to}.csv" to
                                    CsvExport.simulation(a.result.simulation)
                            ExportKind.DAILY ->
                                "dane_dzienne_${a.from}_${a.to}.csv" to
                                    CsvExport.aggregates(a.daily)
                            ExportKind.MONTHLY ->
                                "dane_miesieczne_${a.from}_${a.to}.csv" to
                                    CsvExport.aggregates(a.monthly)
                        }
                    repo.exportFile(name, content)
                }
            _events.emit(UiEvent.Share(file))
        }
    }
}
