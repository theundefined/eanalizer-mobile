package com.theundefined.eanalizer.data.remote

import java.net.URI
import kotlinx.serialization.Serializable

/** Customer (billing account) available on the eBOK account. */
@Serializable data class EneaCustomer(val number: String, val guid: String)

/** Point of delivery and the range of years with meter data. */
data class EneaMeterInfo(val pointOfDeliveryId: String, val minYear: Int, val maxYear: Int)

/** Pure parsing helpers for eBOK pages (no Android APIs, unit-tested on the JVM). */
object EneaHtml {
    const val LOGIN_URL = "https://ebok.enea.pl/logowanie"
    const val ENEA_DOMAIN = "enea.pl"

    private val SELECT_HREF = Regex("href=\"/dashboard/select-current-client/([a-f0-9\\-]+)\"")
    private val NUMBER_SPAN = Regex("<span>\\s*(\\d+)\\s*</span>")
    private val POD_ID = Regex("data-point-of-delivery-id=\"(.*?)\"")
    private val MIN_YEAR = Regex("data-min-date-value=\"(\\d{4})")
    private val MAX_YEAR = Regex("data-max-date-value=\"(\\d{4})")

    /** Logged-in eBOK (and not the login form). */
    fun isAuthenticatedUrl(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.host == "ebok.enea.pl" && !(uri.path ?: "").lowercase().contains("logowanie")
    }

    /** The login WebView may only navigate over HTTPS within the enea.pl domain. */
    fun isAllowedNavigation(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && (host == ENEA_DOMAIN || host.endsWith(".$ENEA_DOMAIN"))
    }

    /** Page with the e-mail/password form (not e.g. the 2FA code field). */
    fun isLoginFormUrl(url: String): Boolean =
        isAllowedNavigation(url) &&
            (runCatching { URI(url).path }.getOrNull() ?: "")
                .lowercase()
                .trimEnd('/')
                .endsWith("/logowanie")

    /**
     * Customers from `/dashboard/many-clients`: every "select client" link paired with the first
     * numeric `<span>` between it and the previous link.
     */
    fun parseCustomers(html: String): List<EneaCustomer> {
        val out = ArrayList<EneaCustomer>()
        var segmentStart = 0
        for (m in SELECT_HREF.findAll(html)) {
            val segment = html.substring(segmentStart, m.range.first)
            NUMBER_SPAN.find(segment)?.let {
                out += EneaCustomer(it.groupValues[1], m.groupValues[1])
            }
            segmentStart = m.range.last + 1
        }
        return out.distinctBy { it.guid }
    }

    /** Point of delivery id and available years from `/meter/summaryBalancingChart`. */
    fun parseMeterInfo(html: String): EneaMeterInfo? {
        val pod = POD_ID.find(html)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() } ?: return null
        val min = MIN_YEAR.find(html)?.groupValues?.get(1)?.toInt() ?: return null
        val max = MAX_YEAR.find(html)?.groupValues?.get(1)?.toInt() ?: return null
        return EneaMeterInfo(pod, min, max)
    }

    /**
     * JS filling (without submitting) the login form on moja.enea.pl. The inputs are controlled
     * React inputs, so the value is set via the native setter followed by an `input` event. Login
     * before password - changing the login clears the password. The form renders lazily, so it
     * polls for up to ~15 s.
     */
    fun autofillScript(login: String, password: String): String =
        """
        (function () {
            var creds = {"login": ${jsString(login)}, "password": ${jsString(password)}};
            var setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, "value").set;
            function fill(input, value) {
                input.focus();
                setter.call(input, value);
                input.dispatchEvent(new Event("input", {bubbles: true}));
                input.dispatchEvent(new Event("change", {bubbles: true}));
                input.blur();
            }
            var attempts = 0;
            var timer = setInterval(function () {
                var login = document.querySelector('input[name="userLogin"]');
                var password = document.querySelector('input[name="userPassword"]');
                if (login && password) {
                    clearInterval(timer);
                    if (!login.value) { fill(login, creds.login); }
                    if (!password.value && creds.password) { fill(password, creds.password); }
                } else if (++attempts > 60) {
                    clearInterval(timer);
                }
            }, 250);
        })();
        """
            .trimIndent()

    /** JSON-style JS string literal, safe for quotes, backslashes and `</script>`. */
    internal fun jsString(s: String): String = buildString {
        append('"')
        for (c in s) {
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c < ' ' || c == '<' || c == '>' || c == ' ' || c == ' ' ->
                    append(String.format("\\u%04x", c.code))
                else -> append(c)
            }
        }
        append('"')
    }
}
