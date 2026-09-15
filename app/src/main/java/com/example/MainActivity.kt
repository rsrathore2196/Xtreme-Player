package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.ui.components.MainNavigationScaffold
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {

    private val playerViewModel: PlayerViewModel by viewModels {
        val app = application as XtremeMusicApp
        PlayerViewModel.provideFactory(app.repository, app.playbackManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Configure 120Hz High Refresh Rate Mode for supported screens and devices
        try {
            val win = window
            val layoutParams = win.attributes

            val displayModes = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                display?.supportedModes ?: emptyArray()
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                win.windowManager.defaultDisplay.supportedModes ?: emptyArray()
            } else {
                emptyArray()
            }

            // Target 120Hz+ mode if hardware screen supports it, fallback to 90Hz+
            val target120HzMode = displayModes.filter { it.refreshRate >= 115f }
                .maxByOrNull { it.refreshRate }
                ?: displayModes.filter { it.refreshRate >= 88f }
                    .maxByOrNull { it.refreshRate }

            if (target120HzMode != null) {
                layoutParams.preferredDisplayModeId = target120HzMode.modeId
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                val targetRefreshRate = target120HzMode?.refreshRate ?: 120.0f
                layoutParams.preferredRefreshRate = targetRefreshRate
            }

            // Explicitly reassign attributes so WindowManager applies the 120Hz mode
            win.attributes = layoutParams

            // Dynamic post-attachment refresh rate lock for LTPO/120Hz displays
            win.decorView.post {
                try {
                    val currentParams = win.attributes
                    if (target120HzMode != null) {
                        currentParams.preferredDisplayModeId = target120HzMode.modeId
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        currentParams.preferredRefreshRate = target120HzMode?.refreshRate ?: 120.0f
                    }
                    win.attributes = currentParams
                } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            android.util.Log.w("MainActivity", "Failed setting 120Hz display mode: ${e.message}")
        }

        setContent {
            val isDarkMode by playerViewModel.isDarkMode.collectAsState()
            MyApplicationTheme(darkTheme = isDarkMode) {
                MainNavigationScaffold(viewModel = playerViewModel)
            }
        }
    }
}
