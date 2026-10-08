package com.theundefined.eanalizer.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theundefined.eanalizer.data.remote.EneaCustomer
import com.theundefined.eanalizer.domain.ExtraLoad
import com.theundefined.eanalizer.domain.NetBillingValuation
import com.theundefined.eanalizer.domain.Period
import com.theundefined.eanalizer.domain.StorageEconomics
import com.theundefined.eanalizer.domain.StorageFinance
import com.theundefined.eanalizer.domain.StorageOptions
import com.theundefined.eanalizer.domain.TariffTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How exported energy is settled. */
@Serializable
enum class SettlementMode {
    /** Exported energy is not settled. */
    NONE,

    /** Net-metering (0.7/0.8 of exported energy back). */
    NET_METERING,

    /** Net-billing (deposit valued at RCEm/RCE). */
    NET_BILLING,
}

@Serializable
data class AnalysisPrefs(
    val period: Period = Period.LAST_365_DAYS,
    val tariff: String = "G11",
    val mode: SettlementMode = SettlementMode.NONE,
    val netMeteringRatio: Double = 0.8,
    val valuation: NetBillingValuation = NetBillingValuation.RCEM,
    /** [Period.CUSTOM] range, ISO dates. */
    val customFrom: String? = null,
    val customTo: String? = null,
    /** Last settlement (invoice) date, ISO; the amount due is counted from it. */
    val billingDate: String? = null,
)

/** Physical parameters of the simulated storage. */
data class StorageSim(val efficiency: Double, val options: StorageOptions)

/**
 * Inputs of the reports that don't change the main analysis (no recomputation). The main analysis
 * always runs without storage: the meter data already includes an existing one, so any storage is a
 * what-if simulated only on the storage screen.
 */
@Serializable
data class ReportPrefs(
    /** Annual PV production from the inverter, kWh (0 = unknown). */
    val pvAnnualKwh: Double = 0.0,
    /** Storage size selected on the storage screen, kWh (0 = the most profitable one). */
    val storageSelected: Double = 0.0,
    /** Full-cycle (round-trip) storage efficiency. */
    val storageEfficiency: Double = 0.9,
    /** Usable share of the storage capacity (depth of discharge). */
    val storageUsableFraction: Double = 1.0,
    /** Storage charge/discharge power limit, kW (0 = no limit). */
    val storagePowerKw: Double = 0.0,
    /** Charge the storage from the grid in the cheapest zone. */
    val storageGridCharging: Boolean = false,
    /** Storage price for the payback estimate, zł per kWh of capacity. */
    val storagePricePerKwh: Double = 2500.0,
    /** Storage cost independent of the size (hybrid inverter, mounting), zł. */
    val storageFixedCost: Double = 0.0,
    /** Subsidy share of the storage investment, %. */
    val storageSubsidyPercent: Double = 0.0,
    /** Subsidy cap, zł (0 = none). */
    val storageSubsidyMax: Double = 0.0,
    /** Yearly capacity loss, %. */
    val storageDegradationPercent: Double = 2.0,
    /** Yearly energy price growth, %. */
    val priceGrowthPercent: Double = 3.0,
    /** Yearly discount rate, %. */
    val discountPercent: Double = 0.0,
    val storageHorizonYears: Int = 15,
    /** Storage sizes compared on the storage screen, kWh. */
    val storageCapacities: List<Double> = StorageEconomics.CAPACITIES.drop(1),
    /** Dynamic tariff: seller margin added to RCE, net zł/kWh. */
    val dynamicMargin: Double = 0.10,
    /** Forecast payments made since the last settlement, zł. */
    val billingPaid: Double = 0.0,
    /** Contracted power, kW (0 = unknown). */
    val contractedPowerKw: Double = 0.0,
    /** What-if: heat pump consumption per year, kWh. */
    val heatPumpKwh: Double = 0.0,
    /** What-if: electric car distance per day, km. */
    val evKmPerDay: Double = 0.0,
    val evKwhPer100Km: Double = 18.0,
    /** Electric car charging power, kW. */
    val evChargeKw: Double = 3.7,
    /** Hour when the electric car starts charging. */
    val evStartHour: Int = 22,
) {
    fun extraLoad() = ExtraLoad(heatPumpKwh, evKmPerDay, evKwhPer100Km, evChargeKw, evStartHour)

    fun storageSim() =
        StorageSim(
            storageEfficiency,
            StorageOptions(storageUsableFraction, storagePowerKw, storageGridCharging),
        )

    fun storageFinance() =
        StorageFinance(
            pricePerKwh = storagePricePerKwh,
            fixedCost = storageFixedCost,
            subsidyShare = storageSubsidyPercent / 100,
            subsidyMax = storageSubsidyMax,
            degradation = storageDegradationPercent / 100,
            priceGrowth = priceGrowthPercent / 100,
            discountRate = discountPercent / 100,
            horizonYears = storageHorizonYears,
        )
}

/**
 * App settings. Credentials (used only to prefill the eBOK login form) are stored encrypted, not
 * hashed. Everything else lives in plain preferences.
 */
class SettingsStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val plain: SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val secure: SharedPreferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "credentials",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    init {
        migrateStorageParams()
    }

    var email: String
        get() = secure.getString(KEY_EMAIL, "") ?: ""
        set(v) = secure.edit().putString(KEY_EMAIL, v).apply()

    var password: String
        get() = secure.getString(KEY_PASSWORD, "") ?: ""
        set(v) = secure.edit().putString(KEY_PASSWORD, v).apply()

    var prefs: AnalysisPrefs
        get() =
            plain.getString(KEY_PREFS, null)?.let {
                runCatching { json.decodeFromString<AnalysisPrefs>(it) }.getOrNull()
            } ?: AnalysisPrefs()
        set(v) = plain.edit().putString(KEY_PREFS, json.encodeToString(v)).apply()

    var reportPrefs: ReportPrefs
        get() =
            plain.getString(KEY_REPORT_PREFS, null)?.let {
                runCatching { json.decodeFromString<ReportPrefs>(it) }.getOrNull()
            } ?: ReportPrefs()
        set(v) = plain.edit().putString(KEY_REPORT_PREFS, json.encodeToString(v)).apply()

    /** Daily background sync with notifications. */
    var backgroundSync: Boolean
        get() = plain.getBoolean(KEY_BACKGROUND_SYNC, false)
        set(v) = plain.edit().putBoolean(KEY_BACKGROUND_SYNC, v).apply()

    /** Use only the stored/imported files; never contact Enea. */
    var localOnly: Boolean
        get() = plain.getBoolean(KEY_LOCAL_ONLY, false)
        set(v) = plain.edit().putBoolean(KEY_LOCAL_ONLY, v).apply()

    /** The background job already notified about an expired session (reset on login). */
    var sessionExpiryNotified: Boolean
        get() = plain.getBoolean(KEY_SESSION_NOTIFIED, false)
        set(v) = plain.edit().putBoolean(KEY_SESSION_NOTIFIED, v).apply()

    /** Custom tariff table; the default one when never edited or equal to the old wrong default. */
    var tariffs: TariffTable
        get() {
            val stored = plain.getString(KEY_TARIFFS, null) ?: return TariffTable.default()
            val table = TariffTable.parseCsv(stored)
            return if (table.zones.isEmpty() || TariffTable.isLegacyDefault(table))
                TariffTable.default()
            else table
        }
        set(v) {
            if (v == TariffTable.default()) plain.edit().remove(KEY_TARIFFS).apply()
            else plain.edit().putString(KEY_TARIFFS, v.toCsv()).apply()
        }

    var customers: List<EneaCustomer>
        get() =
            plain.getString(KEY_CUSTOMERS, null)?.let {
                runCatching { json.decodeFromString<List<EneaCustomer>>(it) }.getOrNull()
            } ?: emptyList()
        set(v) = plain.edit().putString(KEY_CUSTOMERS, json.encodeToString(v)).apply()

    /** Selected customer number; null = the only/current one. */
    var customerNumber: String?
        get() = plain.getString(KEY_CUSTOMER, null)
        set(v) = plain.edit().putString(KEY_CUSTOMER, v).apply()

    /** Epoch millis of the last successful sync, 0 if never. */
    var lastSync: Long
        get() = plain.getLong(KEY_LAST_SYNC, 0L)
        set(v) = plain.edit().putLong(KEY_LAST_SYNC, v).apply()

    var loggedIn: Boolean
        get() = plain.getBoolean(KEY_LOGGED_IN, false)
        set(v) = plain.edit().putBoolean(KEY_LOGGED_IN, v).apply()

    /** Epoch millis of the last WebView login, 0 if unknown. */
    var loginAt: Long
        get() = plain.getLong(KEY_LOGIN_AT, 0L)
        set(v) = plain.edit().putLong(KEY_LOGIN_AT, v).apply()

    /** Epoch millis when eBOK last accepted the session cookies, 0 if never. */
    var sessionCheckedAt: Long
        get() = plain.getLong(KEY_SESSION_CHECKED_AT, 0L)
        set(v) = plain.edit().putLong(KEY_SESSION_CHECKED_AT, v).apply()

    /**
     * Up to v0.1.14 the storage parameters were part of [AnalysisPrefs] and applied to the main
     * analysis. Moves them to [ReportPrefs] (the capacity becomes the selected storage size) and
     * drops them from the analysis prefs, so this runs once.
     */
    private fun migrateStorageParams() {
        val raw = plain.getString(KEY_PREFS, null) ?: return
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        val legacy = LEGACY_STORAGE_KEYS.filter { it in obj }
        if (legacy.isEmpty()) return
        fun num(key: String) = obj[key]?.jsonPrimitive?.doubleOrNull
        val rp = reportPrefs
        reportPrefs =
            rp.copy(
                storageSelected = num("capacity") ?: rp.storageSelected,
                storageEfficiency = num("efficiency") ?: rp.storageEfficiency,
                storageUsableFraction = num("usableFraction") ?: rp.storageUsableFraction,
                storagePowerKw = num("powerKw") ?: rp.storagePowerKw,
                storageGridCharging =
                    obj["gridCharging"]?.jsonPrimitive?.booleanOrNull ?: rp.storageGridCharging,
            )
        plain.edit().putString(KEY_PREFS, JsonObject(obj - legacy.toSet()).toString()).apply()
    }

    private companion object {
        val LEGACY_STORAGE_KEYS =
            listOf("capacity", "efficiency", "usableFraction", "powerKw", "gridCharging")

        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
        const val KEY_PREFS = "analysis_prefs"
        const val KEY_TARIFFS = "tariffs_csv"
        const val KEY_CUSTOMERS = "customers"
        const val KEY_CUSTOMER = "customer"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_LOGGED_IN = "logged_in"
        const val KEY_REPORT_PREFS = "report_prefs"
        const val KEY_BACKGROUND_SYNC = "background_sync"
        const val KEY_LOCAL_ONLY = "local_only"
        const val KEY_SESSION_NOTIFIED = "session_expiry_notified"
        const val KEY_LOGIN_AT = "login_at"
        const val KEY_SESSION_CHECKED_AT = "session_checked_at"
    }
}
