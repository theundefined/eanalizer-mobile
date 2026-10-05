package com.theundefined.eanalizer.data.local

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * OkHttp cookie jar backed by the WebView [CookieManager], so the session established in the login
 * WebView (reCAPTCHA + 2FA, including HttpOnly cookies) is used by OkHttp and vice versa. The
 * WebView persists cookies (also session ones) across app restarts.
 */
class WebViewCookieJar(private val manager: CookieManager = CookieManager.getInstance()) :
    CookieJar {
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val target = url.toString()
        cookies.forEach { manager.setCookie(target, it.toString()) }
        manager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val header = manager.getCookie(url.toString()) ?: return emptyList()
        return header.split(';').mapNotNull { Cookie.parse(url, it.trim()) }
    }

    /** Removes all cookies (logout). */
    fun clear() {
        manager.removeAllCookies(null)
        manager.flush()
    }
}
