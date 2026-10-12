package com.example

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
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

        // Explicitly enforce hardware acceleration at the window surface level
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // No fixed refresh-rate vote: Android can use its adaptive refresh policy,
        // including battery saver, without changing our animation clocks or rendering quality.
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = 0
            preferredRefreshRate = 0f
        }

        setContent {
            val themeMode by playerViewModel.themeMode.collectAsStateWithLifecycle()
            val customThemeState by playerViewModel.customThemeState.collectAsStateWithLifecycle()
            val textScale by playerViewModel.textScale.collectAsStateWithLifecycle()
            val uiScale by playerViewModel.uiScale.collectAsStateWithLifecycle()
            val isOnboardingCompleted by playerViewModel.isOnboardingCompleted.collectAsStateWithLifecycle()
            val isSystemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val effectiveDark = when (themeMode) {
                com.example.data.local.AppThemeMode.SYSTEM -> isSystemDark
                com.example.data.local.AppThemeMode.DARK -> true
                com.example.data.local.AppThemeMode.LIGHT -> false
            }

            androidx.compose.runtime.LaunchedEffect(effectiveDark) {
                playerViewModel.setResolvedDarkMode(effectiveDark)
            }

            MyApplicationTheme(
                darkTheme = effectiveDark,
                customThemeState = customThemeState,
                textScale = textScale,
                uiScale = uiScale
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = isOnboardingCompleted,
                    label = "onboarding_crossfade"
                ) { completed ->
                    if (completed) {
                        MainNavigationScaffold(viewModel = playerViewModel)
                    } else {
                        com.example.ui.screens.OnboardingScreen(
                            onComplete = { name, languages, country, countryCode, flag ->
                                playerViewModel.completeOnboarding(
                                    name = name,
                                    languages = languages,
                                    country = country,
                                    countryCode = countryCode,
                                    flag = flag
                                )
                            }
                        )
                    }
                }
            }
        }
    }
    override fun onResume() {
        super.onResume()
        com.example.updater.AppUpdateManager.onForeground(this)
    }

    override fun onPause() {
        com.example.updater.AppUpdateManager.onBackground()
        super.onPause()
    }

}
