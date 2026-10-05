package com.theundefined.eanalizer.data.local

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.theundefined.eanalizer.data.remote.EneaCustomer
import com.theundefined.eanalizer.domain.NetBillingValuation
import com.theundefined.eanalizer.domain.Period
import com.theundefined.eanalizer.domain.TariffTable
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    val capacity: Double = 0.0,
    val efficiency: Double = 0.9,
    val mode: SettlementMode = SettlementMode.NONE,
    val netMeteringRatio: Double = 0.8,
    val valuation: NetBillingValuation = NetBillingValuation.RCEM,
)

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

    private companion object {
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
        const val KEY_PREFS = "analysis_prefs"
        const val KEY_TARIFFS = "tariffs_csv"
        const val KEY_CUSTOMERS = "customers"
        const val KEY_CUSTOMER = "customer"
        const val KEY_LAST_SYNC = "last_sync"
        const val KEY_LOGGED_IN = "logged_in"
    }
}
