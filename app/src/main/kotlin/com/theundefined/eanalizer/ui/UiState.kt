package com.theundefined.eanalizer.ui

import com.theundefined.eanalizer.data.local.AnalysisPrefs
import com.theundefined.eanalizer.data.remote.EneaCustomer
import com.theundefined.eanalizer.domain.AggregateRow
import com.theundefined.eanalizer.domain.AnalysisResult
import com.theundefined.eanalizer.domain.DailyTrends
import com.theundefined.eanalizer.domain.NetBillingResult
import com.theundefined.eanalizer.domain.RceResult
import com.theundefined.eanalizer.domain.TariffTable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

/** Cost of one tariff in the comparison (with the current storage/settlement settings). */
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
    val monthly: List<AggregateRow>,
    /** RCEm months needed for net-billing but not published/downloaded. */
    val missingRcem: List<YearMonth>,
    /** Net-billing selected but prices could not be fetched at all. */
    val pricesUnavailable: Boolean,
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
)
