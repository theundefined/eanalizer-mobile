package com.theundefined.eanalizer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.theundefined.eanalizer.ui.EanalizerViewModel
import com.theundefined.eanalizer.ui.components.MainScreen
import com.theundefined.eanalizer.ui.theme.EanalizerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: EanalizerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EanalizerTheme { MainScreen(viewModel) } }
    }
}
