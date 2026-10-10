package com.theundefined.eanalizer.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.theundefined.eanalizer.data.local.AnalysisPrefs
import com.theundefined.eanalizer.data.local.DataFileInfo
import com.theundefined.eanalizer.data.local.ReportPrefs
import com.theundefined.eanalizer.data.local.SettlementMode
import com.theundefined.eanalizer.data.local.StorageSim
import com.theundefined.eanalizer.data.remote.EneaProtocolException
import com.theundefined.eanalizer.data.remote.SessionExpiredException
import com.theundefined.eanalizer.data.repository.CustomerSelectionRequiredException
import com.theundefined.eanalizer.data.repository.EneaRepository
import com.theundefined.eanalizer.data.repository.NoMeterDataException
import com.theundefined.eanalizer.data.sync.BackgroundSync
import com.theundefined.eanalizer.domain.Aggregation
import com.theundefined.eanalizer.domain.AnalysisResult
import com.theundefined.eanalizer.domain.Analyzer
import com.theundefined.eanalizer.domain.Billing
import com.theundefined.eanalizer.domain.Bills
import com.theundefined.eanalizer.domain.CsvExport
import com.theundefined.eanalizer.domain.DepositForecast
import com.theundefined.eanalizer.domain.DynamicTariff
import com.theundefined.eanalizer.domain.ExtraLoads
import com.theundefined.eanalizer.domain.HourlyRecord
import com.theundefined.eanalizer.domain.Insights
import com.theundefined.eanalizer.domain.LoadAnalysis
import com.theundefined.eanalizer.domain.NetBilling
import com.theundefined.eanalizer.domain.NetBillingValuation
import com.theundefined.eanalizer.domain.Periods
import com.theundefined.eanalizer.domain.RceAnalysis
import com.theundefined.eanalizer.domain.SimulationRow
import com.theundefined.eanalizer.domain.StorageOptions
import com.theundefined.eanalizer.domain.StorageUsage
import com.theundefined.eanalizer.domain.TariffTable
import com.theundefined.eanalizer.domain.XlsxWriter
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
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

const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

/** Largest storage size simulated on the storage screen, kWh. */
private const val MAX_STORAGE_KWH = 100.0

class EanalizerViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = EneaRepository(application)
    private val settings = repo.settings

    sealed interface UiEvent {
        data class SyncFinished(val downloadedYears: Int) : UiEvent

        data class Error(val kind: ErrorKind, val detail: String? = null) : UiEvent

        /** The session is gone - show the login WebView. */
        data object LoginRequired : UiEvent

        data class Share(val file: File, val mimeType: String) : UiEvent

        data object Saved : UiEvent

        /** Files were imported; [rejected] are names that are not Enea hourly CSVs. */
        data class Imported(val count: Int, val rejected: List<String>) : UiEvent
    }

    private val _uiState =
        MutableStateFlow(
            UiState(
                loggedIn = settings.loggedIn,
                loginAt = settings.loginAt,
                sessionCheckedAt = settings.sessionCheckedAt,
                demo = settings.demoMode,
                lastSync = settings.lastSync,
                prefs = settings.prefs,
                tariffs = settings.tariffs,
                customers = settings.customers,
                customerNumber = settings.customerNumber,
                reportPrefs = settings.reportPrefs,
                backgroundSync = settings.backgroundSync,
                localOnly = settings.localOnly,
            )
        )
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events

    private val records = MutableStateFlow<List<HourlyRecord>?>(null)
    private var syncJob: Job? = null
    private var rceJob: Job? = null
    private var pricesJob: Job? = null
    private var storageJob: Job? = null
    private var storageDetailJob: Job? = null
    private var dynamicJob: Job? = null
    private var extraLoadJob: Job? = null

    /** Bumped by [refreshPrices] to recompute the analysis with freshly downloaded PSE prices. */
    private val pricesRetry = MutableStateFlow(0)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val email = settings.email
            val hasPassword = settings.password.isNotEmpty()
            _uiState.update { it.copy(email = email, hasPassword = hasPassword) }
        }
        if (settings.backgroundSync) BackgroundSync.schedule(application, true)
        // Cache-then-refresh: local data first, then a background sync when logged in and the
        // data was not downloaded today yet.
        viewModelScope.launch {
            reloadRecords()
            if (
                !settings.localOnly &&
                    !settings.demoMode &&
                    settings.loggedIn &&
                    !isToday(settings.lastSync)
            )
                sync(quiet = true)
        }
        viewModelScope.launch {
            // Only records/prefs/tariffs matter; other state changes must not restart analysis.
            combine(records, _uiState, pricesRetry) { recs, s, retry ->
                    AnalysisInput(recs, s.prefs, s.tariffs, retry)
                }
                .distinctUntilChanged()
                .collectLatest { (recs, prefs, tariffs, _) ->
                    if (recs == null) return@collectLatest
                    if (recs.isEmpty()) {
                        _uiState.update { it.copy(analysis = null, analyzing = false) }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(analyzing = true) }
                    val analysis = analyze(recs, prefs, tariffs)
                    val fetchedAt = repo.pricesFetchedAt()
                    _uiState.update {
                        it.copy(analysis = analysis, analyzing = false, pricesFetchedAt = fetchedAt)
                    }
                }
        }
    }

    private suspend fun reloadRecords() {
        val data = repo.loadData()
        val recs = data.records
        _uiState.update {
            it.copy(
                loadingLocal = false,
                hasData = recs.isNotEmpty(),
                dataStart = recs.firstOrNull()?.timestamp?.toLocalDate(),
                dataEnd = recs.lastOrNull()?.timestamp?.toLocalDate(),
                files = data.files,
                years = Insights.byYear(recs),
                rce = RceState(),
            )
        }
        records.value = recs
    }

    private suspend fun analyze(
        all: List<HourlyRecord>,
        prefs: AnalysisPrefs,
        tariffs: TariffTable,
    ): Analysis? {
        val dataStart = all.first().timestamp.toLocalDate()
        val dataEnd = all.last().timestamp.toLocalDate()
        val (from, to) =
            Periods.resolve(
                prefs.period,
                dataStart,
                dataEnd,
                prefs.customFrom?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                prefs.customTo?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
        val recs = Periods.filter(all, from, to)
        if (recs.isEmpty()) return null
        val tariff =
            tariffs.tariffNames.firstOrNull { it.equals(prefs.tariff, ignoreCase = true) }
                ?: tariffs.tariffNames.firstOrNull()
                ?: return null
        val ratio = prefs.netMeteringRatio.takeIf { prefs.mode == SettlementMode.NET_METERING }
        val billingFrom =
            prefs.billingDate
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.coerceAtLeast(dataStart)
                ?.takeIf { it <= dataEnd }

        val netBilling = prefs.mode == SettlementMode.NET_BILLING
        // Net-billing: up to 12 whole months before a range, so deposits created then (still
        // valid in the range) are carried over.
        fun historyOf(start: LocalDate): List<HourlyRecord> {
            val month = start.withDayOfMonth(1)
            return Periods.filter(
                all,
                month.minusMonths(NetBilling.DEPOSIT_VALIDITY_MONTHS),
                month.minusDays(1)
            )
        }
        val history = if (netBilling) historyOf(from) else emptyList()
        // Net-billing: the last 12 months up to the end of the data, for the deposit forecast.
        val nowFrom = maxOf(YearMonth.from(dataEnd).minusMonths(11).atDay(1), dataStart)

        // Net-billing prices (network, cached) before the CPU-heavy part: the period, the range
        // since the settlement date and the last 12 months, each with its history.
        var rce = emptyMap<LocalDateTime, Double>()
        var rcem = emptyMap<YearMonth, Double>()
        var pricesUnavailable = false
        if (netBilling) {
            val pricesFrom =
                maxOf(
                    listOfNotNull(from, billingFrom, nowFrom).minOf {
                        it.withDayOfMonth(1).minusMonths(NetBilling.DEPOSIT_VALIDITY_MONTHS)
                    },
                    dataStart,
                )
            val months = NetBilling.monthsOf(Periods.filter(all, pricesFrom, dataEnd))
            rcem = runCatching { repo.rcemPrices(months) }.getOrDefault(emptyMap())
            if (prefs.valuation == NetBillingValuation.RCE) {
                rce =
                    runCatching { repo.rcePrices(pricesFrom, dataEnd).first }
                        .getOrDefault(emptyMap())
            }
            pricesUnavailable = rcem.isEmpty() && rce.isEmpty()
        }

        return withContext(Dispatchers.Default) {
            // The main analysis is the meter data as is (an existing storage is already in it);
            // storage sizes are simulated on demand on the storage screen.
            // History simulation per tariff (zones depend on the tariff).
            val historySim =
                if (history.isEmpty()) emptyMap()
                else
                    Analyzer.compareTariffs(history, 0.0, tariffs).associate {
                        it.tariff to it.simulation
                    }

            fun settle(r: AnalysisResult) =
                if (netBilling)
                    NetBilling.settle(
                        r.simulation,
                        tariffs,
                        r.tariff,
                        rce,
                        rcem,
                        prefs.valuation,
                        r.fixedFees,
                        historySim[r.tariff] ?: emptyList(),
                    )
                else null

            /** Net-billing of [sim] (the selected tariff) starting at [start], with its history. */
            fun settleFrom(start: LocalDate, sim: List<SimulationRow>, fee: Double) =
                NetBilling.settle(
                    sim,
                    tariffs,
                    tariff,
                    rce,
                    rcem,
                    prefs.valuation,
                    fee,
                    historyOf(start).let { h ->
                        if (h.isEmpty()) emptyList()
                        else Analyzer.runFullAnalysis(h, 0.0, tariffs, tariff).simulation
                    },
                )

            val result = Analyzer.runFullAnalysis(recs, 0.0, tariffs, tariff, ratio)
            val nb = settle(result)
            val sinceBilling =
                billingFrom?.let { b ->
                    Billing.toDate(all, b, dataEnd, tariffs, tariff, ratio) { sim, fee ->
                        if (netBilling) settleFrom(b, sim, fee) else null
                    }
                }
            val depositsNow =
                if (!netBilling) null
                else
                    Analyzer.runFullAnalysis(
                            Periods.filter(all, nowFrom, dataEnd),
                            0.0,
                            tariffs,
                            tariff
                        )
                        .let { settleFrom(nowFrom, it.simulation, it.fixedFees) }
            val comparison =
                Analyzer.compareTariffs(recs, 0.0, tariffs, ratio)
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
                daily = Aggregation.daily(recs, tariffs, tariff),
                dailyAll = Aggregation.daily(all, tariffs, tariff),
                monthly = Aggregation.monthly(recs, tariffs, tariff),
                missingRcem = nb?.missingRcemMonths ?: emptyList(),
                pricesUnavailable = pricesUnavailable,
                dayProfile = Insights.dayProfile(recs),
                heatmap = Insights.heatmap(recs),
                bills = Bills.monthly(result.simulation, tariffs, tariff, nb),
                selfUseUnit = Insights.selfUse(recs, 1.0),
                years = Insights.byYear(all, tariffs, tariff),
                inputs = AnalysisInputs(recs, history, prefs, tariffs, tariff, ratio, rce, rcem),
                sinceBilling = sinceBilling,
                depositsNow = depositsNow,
                depositForecast =
                    depositsNow?.let {
                        DepositForecast.forecast(
                            it.deposits,
                            YearMonth.from(dataEnd).plusMonths(1),
                            DepositForecast.expectedCost(it.months),
                        )
                    } ?: emptyList(),
                baseLoad = LoadAnalysis.baseLoad(recs, tariffs, tariff),
                peaks = LoadAnalysis.peaks(recs),
            )
        }
    }

    /**
     * Simulation of [inputs] with a storage of [capacity] kWh ([sim] parameters) on [tariff] and
     * its total cost (same settlement as the analysis). [records]/[history] replace the inputs'
     * ones (e.g. with an extra load).
     */
    private fun runWithStorage(
        inputs: AnalysisInputs,
        sim: StorageSim,
        capacity: Double,
        tariff: String = inputs.tariff,
        records: List<HourlyRecord> = inputs.records,
        history: List<HourlyRecord> = inputs.history,
    ): Pair<AnalysisResult, Double> {
        val p = inputs.prefs
        val r =
            Analyzer.runFullAnalysis(
                records,
                capacity,
                inputs.tariffs,
                tariff,
                inputs.netMeteringRatio,
                sim.efficiency,
                sim.options,
            )
        if (p.mode != SettlementMode.NET_BILLING) return r to r.totalCost
        val historySim =
            if (history.isEmpty()) emptyList()
            else
                Analyzer.runFullAnalysis(
                        history,
                        capacity,
                        inputs.tariffs,
                        tariff,
                        null,
                        sim.efficiency,
                        sim.options,
                    )
                    .simulation
        val cost =
            NetBilling.settle(
                    r.simulation,
                    inputs.tariffs,
                    tariff,
                    inputs.rce,
                    inputs.rcem,
                    p.valuation,
                    r.fixedFees,
                    historySim,
                )
                ?.calkowityKoszt ?: r.totalCost
        return r to cost
    }

    /** Period cost per tariff without and with the extra load from the report prefs. */
    fun loadExtraLoad() {
        val a = _uiState.value.analysis ?: return
        val load = _uiState.value.reportPrefs.extraLoad()
        val current = _uiState.value.extraLoad
        if (current.forAnalysis === a && current.load == load) return
        extraLoadJob?.cancel()
        if (load.isEmpty) {
            _uiState.update { it.copy(extraLoad = ExtraLoadState(forAnalysis = a, load = load)) }
            return
        }
        extraLoadJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        extraLoad = ExtraLoadState(loading = true, forAnalysis = a, load = load)
                    )
                }
                val state =
                    withContext(Dispatchers.Default) {
                        val inputs = a.inputs
                        val extra = ExtraLoads.hourly(inputs.records + inputs.history, load)
                        val records = ExtraLoads.apply(inputs.records, extra)
                        val history = ExtraLoads.apply(inputs.history, extra)
                        val noStorage = StorageSim(1.0, StorageOptions())
                        ExtraLoadState(
                            forAnalysis = a,
                            load = load,
                            addedKwh = inputs.records.sumOf { extra[it.timestamp] ?: 0.0 },
                            rows =
                                a.comparison
                                    .map { c ->
                                        val after =
                                            runWithStorage(
                                                    inputs,
                                                    noStorage,
                                                    0.0,
                                                    c.tariff,
                                                    records,
                                                    history,
                                                )
                                                .second
                                        ExtraLoadRow(c.tariff, c.totalCost, after)
                                    }
                                    .sortedBy { it.after },
                        )
                    }
                _uiState.update { it.copy(extraLoad = state) }
            }
    }

    /**
     * Computes the period cost for the compared storage sizes (plus the selected and suggested
     * one).
     */
    fun loadStorage() {
        val a = _uiState.value.analysis ?: return
        val rp = _uiState.value.reportPrefs
        val sim = rp.storageSim()
        val caps =
            (rp.storageCapacities +
                    0.0 +
                    rp.storageSelected +
                    Math.round(a.optimalCapacity * 2) / 2.0)
                .filter { it in 0.0..MAX_STORAGE_KWH }
                .distinct()
                .sorted()
        val current = _uiState.value.storage
        if (
            current.forAnalysis === a &&
                current.sim == sim &&
                current.capacities == caps &&
                (current.loading || current.costs.isNotEmpty())
        )
            return
        storageJob?.cancel()
        storageJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        storage =
                            // Same simulation: the detail of the selected size stays valid.
                            if (current.detailFor === a && current.sim == sim)
                                current.copy(loading = true, capacities = caps, costs = emptyMap())
                            else
                                StorageState(
                                    loading = true,
                                    forAnalysis = a,
                                    sim = sim,
                                    capacities = caps,
                                )
                    )
                }
                val costs =
                    withContext(Dispatchers.Default) {
                        caps.associateWith { runWithStorage(a.inputs, sim, it).second }
                    }
                _uiState.update {
                    it.copy(
                        storage =
                            it.storage.copy(
                                loading = false,
                                forAnalysis = a,
                                sim = sim,
                                capacities = caps,
                                costs = costs,
                            )
                    )
                }
            }
    }

    /** Usage and the per-tariff comparison of one storage size. */
    fun loadStorageDetail(capacity: Double) {
        val a = _uiState.value.analysis ?: return
        val s = _uiState.value.storage
        val sim = s.sim ?: return
        if (s.forAnalysis !== a) return
        if (s.detail?.capacity == capacity && s.detailFor === a) return
        storageDetailJob?.cancel()
        storageDetailJob =
            viewModelScope.launch {
                val detail =
                    withContext(Dispatchers.Default) {
                        val (run, _) = runWithStorage(a.inputs, sim, capacity)
                        val usable = capacity * sim.options.usableFraction.coerceIn(0.0, 1.0)
                        StorageDetail(
                            capacity = capacity,
                            usage = StorageUsage.of(capacity, usable, run.simulation),
                            tariffs =
                                a.inputs.tariffs.tariffNames
                                    .map { t ->
                                        TariffStorageRow(
                                            t,
                                            runWithStorage(a.inputs, sim, 0.0, t).second,
                                            runWithStorage(a.inputs, sim, capacity, t).second,
                                        )
                                    }
                                    .sortedBy { it.withStorage },
                        )
                    }
                _uiState.update {
                    if (it.storage.forAnalysis !== a || it.storage.sim != sim) it
                    else it.copy(storage = it.storage.copy(detail = detail, detailFor = a))
                }
            }
    }

    /** Dynamic tariff estimate for the analysed period; downloads hourly RCE when needed. */
    fun loadDynamic() {
        val a = _uiState.value.analysis ?: return
        val margin = _uiState.value.reportPrefs.dynamicMargin
        val d = _uiState.value.dynamic
        if (d.forAnalysis === a && d.margin == margin && (d.loading || d.result != null)) return
        dynamicJob?.cancel()
        dynamicJob =
            viewModelScope.launch {
                _uiState.update {
                    it.copy(
                        dynamic = DynamicState(loading = true, forAnalysis = a, margin = margin)
                    )
                }
                val inputs = a.inputs
                val netBilling = inputs.prefs.mode == SettlementMode.NET_BILLING
                val history = if (netBilling) inputs.history else emptyList()
                val from = history.firstOrNull()?.timestamp?.toLocalDate() ?: a.from
                val (prices, failed) =
                    repo.rcePrices(from, a.to) { done, total ->
                        _uiState.update {
                            it.copy(dynamic = it.dynamic.copy(done = done, total = total))
                        }
                    }
                val result =
                    withContext(Dispatchers.Default) {
                        val historySim =
                            if (history.isEmpty()) emptyList()
                            else
                                Analyzer.runFullAnalysis(
                                        history,
                                        0.0,
                                        inputs.tariffs,
                                        inputs.tariff
                                    )
                                    .simulation
                        DynamicTariff.cost(
                            a.result.simulation,
                            inputs.tariffs,
                            inputs.tariff,
                            prices,
                            margin,
                            a.result.fixedFees,
                            netBilling,
                            historySim,
                            inputs.rcem,
                        )
                    }
                _uiState.update {
                    it.copy(
                        dynamic =
                            it.dynamic.copy(loading = false, result = result, failedDays = failed)
                    )
                }
            }
    }

    /** Local-only mode: analyse just the stored/imported files, without logging in to Enea. */
    fun setLocalOnly(enabled: Boolean) {
        settings.localOnly = enabled
        _uiState.update { it.copy(localOnly = enabled) }
        if (enabled) syncJob?.cancel() else if (settings.loggedIn) sync(quiet = true)
    }

    /** Adds CSV files picked by the user (e.g. downloaded by hand from the eBOK page). */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _uiState.update { it.copy(importing = true) }
            try {
                val rejected = repo.importFiles(uris)
                reloadRecords()
                _events.emit(UiEvent.Imported(uris.size - rejected.size, rejected))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.emit(UiEvent.Error(ErrorKind.UNKNOWN, e.message))
            } finally {
                _uiState.update { it.copy(importing = false) }
            }
        }
    }

    fun deleteDataFile(file: DataFileInfo) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.deleteDataFile(file.name, file.imported) }
            reloadRecords()
        }
    }

    /** Saves the stored file as is to [target] (a document picked by the user), or shares it. */
    fun exportDataFile(file: DataFileInfo, target: Uri? = null) {
        viewModelScope.launch {
            try {
                val src =
                    repo.dataFile(file.name, file.imported)
                        ?: throw IOException("missing ${file.name}")
                withContext(Dispatchers.IO) {
                    if (target != null) repo.copyTo(src, target)
                    else {
                        val copy =
                            repo.exportFile(file.name) { out ->
                                src.inputStream().use { it.copyTo(out) }
                            }
                        _events.emit(UiEvent.Share(copy, "text/csv"))
                    }
                }
                if (target != null) _events.emit(UiEvent.Saved)
            } catch (e: IOException) {
                _events.emit(UiEvent.Error(ErrorKind.UNKNOWN, e.message))
            }
        }
    }

    fun setBackgroundSync(enabled: Boolean) {
        settings.backgroundSync = enabled
        BackgroundSync.schedule(getApplication(), enabled)
        _uiState.update { it.copy(backgroundSync = enabled) }
    }

    fun updateReportPrefs(transform: (ReportPrefs) -> ReportPrefs) {
        val p = transform(_uiState.value.reportPrefs)
        settings.reportPrefs = p
        _uiState.update { it.copy(reportPrefs = p) }
    }

    /**
     * Downloads new data. [quiet] = background sync on start (no login prompt). In the local-only
     * mode it just re-reads the stored files.
     */
    fun sync(force: Boolean = false, quiet: Boolean = false) {
        if (syncJob?.isActive == true) return
        if (settings.localOnly) {
            syncJob =
                viewModelScope.launch {
                    _uiState.update { it.copy(syncing = true) }
                    try {
                        reloadRecords()
                    } finally {
                        _uiState.update { it.copy(syncing = false) }
                    }
                }
            return
        }
        if (syncJob?.isActive == true || settings.demoMode) return
        syncJob =
            viewModelScope.launch {
                _uiState.update { it.copy(syncing = true) }
                try {
                    val res = repo.sync(force) { y -> _uiState.update { it.copy(syncYear = y) } }
                    _uiState.update {
                        it.copy(
                            loggedIn = true,
                            sessionCheckedAt = settings.sessionCheckedAt,
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

    /** Switches to synthetic data (no login, no network sync). */
    fun startDemo() {
        settings.demoMode = true
        _uiState.update { it.copy(demo = true) }
        viewModelScope.launch { reloadRecords() }
    }

    /** Leaves the demo mode; real data (if any was downloaded earlier) is shown again. */
    fun exitDemo() {
        settings.demoMode = false
        _uiState.update { it.copy(demo = false) }
        viewModelScope.launch { reloadRecords() }
    }

    fun onWebLoginFinished() {
        val wasDemo = settings.demoMode
        settings.demoMode = false
        repo.onWebLoginFinished()
        _uiState.update {
            it.copy(
                loggedIn = true,
                loginAt = settings.loginAt,
                sessionCheckedAt = settings.sessionCheckedAt,
            )
        }
        sync()
        _uiState.update { it.copy(loggedIn = true, demo = false) }
        viewModelScope.launch {
            if (wasDemo) reloadRecords()
            sync()
        }
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
        _uiState.update { it.copy(loggedIn = false, loginAt = 0L, sessionCheckedAt = 0L) }
    }

    fun clearAll() {
        syncJob?.cancel()
        repo.clearAll()
        _uiState.update {
            it.copy(
                loggedIn = false,
                loginAt = 0L,
                sessionCheckedAt = 0L,
                demo = false,
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
     * Downloads PSE prices now (RCEm page, plus hourly RCE of the analysed period when it is used)
     * and recomputes the analysis.
     */
    fun refreshPrices() {
        if (pricesJob?.isActive == true) return
        val all = records.value?.takeIf { it.isNotEmpty() } ?: return
        val state = _uiState.value
        pricesJob =
            viewModelScope.launch {
                _uiState.update { it.copy(pricesRefreshing = true) }
                try {
                    repo.rcemPrices(NetBilling.monthsOf(all), force = true)
                    val a = state.analysis
                    if (
                        a != null &&
                            state.prefs.mode == SettlementMode.NET_BILLING &&
                            state.prefs.valuation == NetBillingValuation.RCE
                    ) {
                        val (_, failed) = repo.rcePrices(a.from, a.to, force = true)
                        if (failed > 0) throw IOException("PSE: $failed days failed")
                    }
                } catch (e: IOException) {
                    _events.emit(UiEvent.Error(ErrorKind.PRICES, e.message))
                } finally {
                    val fetchedAt = repo.pricesFetchedAt()
                    _uiState.update {
                        it.copy(pricesRefreshing = false, pricesFetchedAt = fetchedAt)
                    }
                    pricesRetry.update { it + 1 }
                }
            }
    }

    private fun isToday(epochMillis: Long): Boolean =
        epochMillis > 0 &&
            Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate() ==
                LocalDate.now()

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

    enum class ExportKind(val mimeType: String) {
        /** Everything in one spreadsheet (all data + analysed period). */
        WORKBOOK(XLSX_MIME),
        /** All downloaded hourly data. */
        HOURLY("text/csv"),
        SIMULATION("text/csv"),
        DAILY("text/csv"),
        MONTHLY("text/csv"),
    }

    /** Suggested file name for [kind], null when there is nothing to export. */
    fun exportName(kind: ExportKind): String? {
        val all = records.value?.takeIf { it.isNotEmpty() } ?: return null
        val a = _uiState.value.analysis
        val dataFrom = all.first().timestamp.toLocalDate()
        val dataTo = all.last().timestamp.toLocalDate()
        return when (kind) {
            ExportKind.WORKBOOK -> "eanalizer_${dataFrom}_$dataTo.xlsx"
            ExportKind.HOURLY -> "dane_godzinowe_${dataFrom}_$dataTo.csv"
            ExportKind.SIMULATION ->
                a?.let { "symulacja_${it.result.tariff}_${it.from}_${it.to}.csv" }
            ExportKind.DAILY -> a?.let { "dane_dzienne_${it.from}_${it.to}.csv" }
            ExportKind.MONTHLY -> a?.let { "dane_miesieczne_${it.from}_${it.to}.csv" }
        }
    }

    /** Writes the export to [target] (a document picked by the user) or shares it when null. */
    fun export(kind: ExportKind, target: Uri? = null) {
        val name = exportName(kind) ?: return
        val all = records.value ?: return
        val a = _uiState.value.analysis
        viewModelScope.launch {
            try {
                val file =
                    withContext(Dispatchers.IO) {
                        repo.exportFile(name) { out ->
                            fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))
                            when (kind) {
                                ExportKind.WORKBOOK ->
                                    XlsxWriter.write(
                                        CsvExport.workbook(
                                            all,
                                            a?.result?.simulation,
                                            a?.netBilling,
                                        ),
                                        out,
                                    )
                                ExportKind.HOURLY -> text(CsvExport.hourly(all))
                                ExportKind.SIMULATION ->
                                    text(CsvExport.simulation(a!!.result.simulation))
                                ExportKind.DAILY -> text(CsvExport.aggregates(a!!.daily))
                                ExportKind.MONTHLY -> text(CsvExport.aggregates(a!!.monthly))
                            }
                        }
                    }
                if (target == null) _events.emit(UiEvent.Share(file, kind.mimeType))
                else {
                    withContext(Dispatchers.IO) { repo.copyTo(file, target) }
                    _events.emit(UiEvent.Saved)
                }
            } catch (e: IOException) {
                _events.emit(UiEvent.Error(ErrorKind.UNKNOWN, e.message))
            }
        }
    }
}
