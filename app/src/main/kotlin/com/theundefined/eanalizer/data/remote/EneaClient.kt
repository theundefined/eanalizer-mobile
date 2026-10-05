package com.theundefined.eanalizer.data.remote

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** The eBOK session is missing or expired - the user has to log in again in the WebView. */
class SessionExpiredException : IOException("Enea session expired")

/** eBOK answered with something unexpected (page layout or API change). */
class EneaProtocolException(message: String) : IOException(message)

/**
 * eBOK client (port of eanalizer `downloader.py`). Login itself happens in a WebView (Enea protects
 * the form with reCAPTCHA), this client only reuses the resulting session cookies. Blocking calls -
 * use from `Dispatchers.IO`.
 */
class EneaClient(cookieJar: CookieJar, private val userAgent: () -> String) {
    private val http =
        OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    private val json = Json { ignoreUnknownKeys = true }

    private fun get(url: String, referer: String? = null): Request =
        Request.Builder()
            .url(url)
            .header("User-Agent", userAgent())
            .apply { referer?.let { header("Referer", it) } }
            .build()

    private inline fun <T> call(request: Request, block: (Response, String) -> T): T =
        http.newCall(request).execute().use { resp ->
            val body = resp.body?.string() ?: ""
            if (!EneaHtml.isAuthenticatedUrl(resp.request.url.toString()))
                throw SessionExpiredException()
            if (!resp.isSuccessful) throw EneaProtocolException("HTTP ${resp.code} ${request.url}")
            block(resp, body)
        }

    /** True when the stored cookies give a logged-in eBOK session (possibly via SSO redirects). */
    fun isLoggedIn(): Boolean =
        http.newCall(get(EneaHtml.LOGIN_URL)).execute().use {
            EneaHtml.isAuthenticatedUrl(it.request.url.toString())
        }

    /** Customers on the account; empty when eBOK does not show the selection page. */
    fun customers(): List<EneaCustomer> =
        call(get("$BASE/dashboard/many-clients")) { _, body -> EneaHtml.parseCustomers(body) }

    fun selectCustomer(guid: String) {
        call(get("$BASE/dashboard/select-current-client/$guid", "$BASE/dashboard/many-clients")) {
            _,
            _ ->
        }
    }

    fun meterInfo(): EneaMeterInfo =
        call(get(SUMMARY_URL, "$BASE/dashboard")) { _, body ->
            EneaHtml.parseMeterInfo(body)
                ?: throw EneaProtocolException("pointOfDeliveryId / years not found")
        }

    /** Hourly CSV for [year]; null when Enea returned no data. */
    fun downloadYear(year: Int, pointOfDeliveryId: String): String? {
        val form =
            FormBody.Builder()
                .add("duration", "year")
                .add("date", year.toString())
                .add("pointOfDeliveryId", pointOfDeliveryId)
                .build()
        val request =
            Request.Builder()
                .url("$SUMMARY_URL/csv")
                .post(form)
                .header("User-Agent", userAgent())
                .header("Referer", SUMMARY_URL)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
        return call(request) { _, body ->
            if (body.trimStart().startsWith("<")) throw SessionExpiredException()
            val data =
                runCatching { json.parseToJsonElement(body).jsonObject["data"] }.getOrNull()
                    ?: throw EneaProtocolException("no 'data' in CSV response")
            data.jsonPrimitive.contentOrNull?.takeIf { it.trim().length >= 10 }
        }
    }

    private companion object {
        const val BASE = "https://ebok.enea.pl"
        const val SUMMARY_URL = "$BASE/meter/summaryBalancingChart"
    }
}
