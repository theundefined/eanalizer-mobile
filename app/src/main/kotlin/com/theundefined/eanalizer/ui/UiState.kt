package com.theundefined.eanalizer.ui

import com.theundefined.eanalizer.data.local.AnalysisPrefs
import com.theundefined.eanalizer.data.local.ReportPrefs
import com.theundefined.eanalizer.data.local.StorageSim
import com.theundefined.eanalizer.data.remote.EneaCustomer
import com.theundefined.eanalizer.domain.AggregateRow
import com.theundefined.eanalizer.domain.AnalysisResult
import com.theundefined.eanalizer.domain.DailyTrends
import com.theundefined.eanalizer.domain.DayProfile
import com.theundefined.eanalizer.domain.DynamicTariffResult
import com.theundefined.eanalizer.domain.Heatmap
import com.theundefined.eanalizer.domain.HourlyRecord
import com.theundefined.eanalizer.domain.MonthlyBill
import com.theundefined.eanalizer.domain.NetBillingResult
import com.theundefined.eanalizer.domain.RceResult
import com.theundefined.eanalizer.domain.SelfUseMonth
import com.theundefined.eanalizer.domain.StorageUsage
import com.theundefined.eanalizer.domain.TariffTable
import com.theundefined.eanalizer.domain.YearMonths
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Cost of one tariff in the comparison (without storage, current settlement settings). */
data class ComparisonRow(
    val tariff: String,
    val totalCost: Double,
    val fixedFees: Double,
    val netBilling: NetBillingResult?,
)

/** Everything derived from records + prefs + tariffs for the selected period. */
data class Analysis(
    val from: LocalDate,
    val to: LocalDate,
    val recordCount: Int,
    val result: AnalysisResult,
    val netBilling: NetBillingResult?,
    val comparison: List<ComparisonRow>,
    val optimalCapacity: Double,
    val trends: DailyTrends,
    /** Missing hours, without the spring DST gap. */
    val missingHours: List<LocalDateTime>,
    val daily: List<AggregateRow>,
    /** Days of all stored data (not just the period), for browsing by year. */
    val dailyAll: List<AggregateRow>,
    val monthly: List<AggregateRow>,
    /** RCEm months needed for net-billing but not published/downloaded. */
    val missingRcem: List<YearMonth>,
    /** Net-billing selected but prices could not be fetched at all. */
    val pricesUnavailable: Boolean,
    val dayProfile: DayProfile,
    val heatmap: Heatmap,
    val bills: List<MonthlyBill>,
    /** Self-consumption with production for 1 kWh/year (scale by the annual production). */
    val selfUseUnit: List<SelfUseMonth>,
    /** All data (not just the period) by year, months split by the tariff's zones. */
    val years: List<YearMonths>,
    /** Inputs kept for on-demand reports (storage scenarios, dynamic tariff). */
    val inputs: AnalysisInputs,
)

/** What [Analysis] was computed from. */
class AnalysisInputs(
    val records: List<HourlyRecord>,
    /** Net-billing: records of up to 12 months before the period. */
    val history: List<HourlyRecord>,
    val prefs: AnalysisPrefs,
    val tariffs: TariffTable,
    val tariff: String,
    val netMeteringRatio: Double?,
    val rce: Map<LocalDateTime, Double>,
    val rcem: Map<YearMonth, Double>,
)

/**
 * Total cost of the period per storage capacity (computed on demand for one [Analysis] and storage
 * parameters).
 */
data class StorageState(
    val loading: Boolean = false,
    val forAnalysis: Analysis? = null,
    val sim: StorageSim? = null,
    val capacities: List<Double> = emptyList(),
    val costs: Map<Double, Double> = emptyMap(),
    val detail: StorageDetail? = null,
    val detailFor: Analysis? = null,
)

/** Period cost of one tariff without and with the selected storage. */
data class TariffStorageRow(
    val tariff: String,
    val withoutStorage: Double,
    val withStorage: Double
)

/** Usage and the tariff comparison for one storage size. */
data class StorageDetail(
    val capacity: Double,
    val usage: StorageUsage,
    val tariffs: List<TariffStorageRow>,
)

/** Dynamic tariff estimate (computed on demand for one [Analysis] and margin). */
data class DynamicState(
    val loading: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val forAnalysis: Analysis? = null,
    val margin: Double = 0.0,
    val result: DynamicTariffResult? = null,
    val failedDays: Int = 0,
)

data class RceState(
    val loading: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val result: RceResult? = null,
    val failedDays: Int = 0,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

enum class ErrorKind {
    SESSION_EXPIRED,
    NETWORK,
    PROTOCOL,
    NO_METER_DATA,
    PRICES,
    UNKNOWN,
}

data class UiState(
    val loadingLocal: Boolean = true,
    val hasData: Boolean = false,
    val dataStart: LocalDate? = null,
    val dataEnd: LocalDate? = null,
    val syncing: Boolean = false,
    val syncYear: Int? = null,
    val loggedIn: Boolean = false,
    /** Last WebView login (epoch ms), 0 = unknown. */
    val loginAt: Long = 0L,
    /** Last time eBOK accepted the session (epoch ms), 0 = never. */
    val sessionCheckedAt: Long = 0L,
    val lastSync: Long = 0L,
    val prefs: AnalysisPrefs = AnalysisPrefs(),
    val tariffs: TariffTable = TariffTable.default(),
    val customers: List<EneaCustomer> = emptyList(),
    val customerNumber: String? = null,
    /** Non-null while the user has to pick one of several customers. */
    val customerChoice: List<EneaCustomer>? = null,
    val email: String = "",
    val hasPassword: Boolean = false,
    val analyzing: Boolean = false,
    val analysis: Analysis? = null,
    val rce: RceState = RceState(),
    val dataYears: List<Int> = emptyList(),
    /** Last successful PSE price download (epoch ms), 0 = never. */
    val pricesFetchedAt: Long = 0L,
    val pricesRefreshing: Boolean = false,
    val reportPrefs: ReportPrefs = ReportPrefs(),
    val backgroundSync: Boolean = false,
    /** Monthly sums of all data per year, newest first. */
    val years: List<YearMonths> = emptyList(),
    val storage: StorageState = StorageState(),
    val dynamic: DynamicState = DynamicState(),
)
