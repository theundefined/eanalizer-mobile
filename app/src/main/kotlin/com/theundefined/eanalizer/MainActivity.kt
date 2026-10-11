package com.theundefined.eanalizer

import android.content.Intent
import android.content.Intent.EXTRA_STREAM
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.components.MainScreen
import com.theundefined.eanalizer.ui.theme.EanalizerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: EanalizerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EanalizerTheme { MainScreen(viewModel) } }
        if (savedInstanceState == null) importShared(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        importShared(intent)
    }

    /**
     * Imports files shared to the app or opened with it (e.g. Google Sheets "Send a copy" as CSV or
     * XLSX - the only way to get a Drive sheet out, as Drive offers it to pickers as PDF only).
     */
    private fun importShared(intent: Intent?) {
        val uris =
            when (intent?.action) {
                Intent.ACTION_SEND -> listOfNotNull(stream(intent))
                Intent.ACTION_SEND_MULTIPLE -> streams(intent)
                Intent.ACTION_VIEW -> listOfNotNull(intent.data)
                else -> emptyList()
            }
        viewModel.importFiles(uris)
    }

    private fun stream(i: Intent): Uri? =
        IntentCompat.getParcelableExtra(i, EXTRA_STREAM, Uri::class.java)

    private fun streams(i: Intent): List<Uri> =
        IntentCompat.getParcelableArrayListExtra(i, EXTRA_STREAM, Uri::class.java).orEmpty()
}
