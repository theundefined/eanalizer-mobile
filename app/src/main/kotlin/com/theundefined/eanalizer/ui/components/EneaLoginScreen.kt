package com.theundefined.eanalizer.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.theundefined.eanalizer.R
import com.theundefined.eanalizer.data.remote.EneaHtml
import com.theundefined.eanalizer.ui.EanalizerViewModel

/**
 * Enea eBOK login in a WebView (Enea protects the form with reCAPTCHA, so the user logs in on the
 * real page). Stored credentials only prefill the form - submitting and the 2FA code are up to the
 * user. Navigation is restricted to https enea.pl hosts; once the WebView lands on the logged-in
 * eBOK the cookies (shared with OkHttp via CookieManager) are flushed and [onLoggedIn] is called.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun EneaLoginScreen(viewModel: EanalizerViewModel, onLoggedIn: () -> Unit, onBack: () -> Unit) {
    val credentials by produceState<Pair<String, String>?>(null) { value = viewModel.credentials() }
    var loading by remember { mutableStateOf(true) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val currentOnLoggedIn by rememberUpdatedState(onLoggedIn)

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.login_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                    }
                },
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            MutedText(
                stringResource(R.string.login_info),
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            val creds = credentials ?: return@Column
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(top = 4.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        val cookies = CookieManager.getInstance()
                        cookies.setAcceptCookie(true)
                        // reCAPTCHA runs in a google.com iframe.
                        cookies.setAcceptThirdPartyCookies(this, true)
                        var finished = false
                        fun check(url: String?) {
                            if (url != null && !finished && EneaHtml.isAuthenticatedUrl(url)) {
                                finished = true
                                cookies.flush()
                                currentOnLoggedIn()
                            }
                        }
                        webViewClient =
                            object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean =
                                    request.isForMainFrame &&
                                        !EneaHtml.isAllowedNavigation(request.url.toString())

                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: Bitmap?
                                ) {
                                    loading = true
                                }

                                override fun onPageFinished(view: WebView, url: String?) {
                                    loading = false
                                    if (
                                        url != null &&
                                            creds.first.isNotEmpty() &&
                                            EneaHtml.isLoginFormUrl(url)
                                    ) {
                                        view.evaluateJavascript(
                                            EneaHtml.autofillScript(creds.first, creds.second),
                                            null,
                                        )
                                    }
                                    check(url)
                                }

                                override fun doUpdateVisitedHistory(
                                    view: WebView,
                                    url: String?,
                                    isReload: Boolean,
                                ) {
                                    check(url)
                                }
                            }
                        loadUrl(EneaHtml.LOGIN_URL)
                        webView = this
                    }
                },
                onRelease = {
                    it.stopLoading()
                    it.destroy()
                },
            )
        }
    }
}
