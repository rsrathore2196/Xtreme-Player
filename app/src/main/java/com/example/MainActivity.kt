package com.example

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.example.ui.components.MainNavigationScaffold
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.PlayerViewModel

class MainActivity : ComponentActivity() {
    private var mediaSearchIntentJob: Job? = null
    private var mediaSearchRequestId: String? = null
    private var mediaSearchDispatched = false
    private var performanceSettingsJob: Job? = null
    private var initialContentReady = false
    private var firstDrawListener: android.view.ViewTreeObserver.OnDrawListener? = null
    private var lastInteractionMs = 0L
    private var frameThread: HandlerThread? = null
    private var frameListener: Window.OnFrameMetricsAvailableListener? = null
    @Volatile private var frameBudgetNanos = 16_666_667L
    private var uiResumed = false
    private var powerReceiverRegistered = false
    private var displayListenerRegistered = false
    private var supportsHighRefresh = false
    private var cachedHighRefreshRate = 60f
    private var cachedDisplayId = -1
    private var lastAppliedLegacyRate = -1f
    private var lastAppliedCategory = -1f
    private var lastAppliedBoost: Boolean? = null
    private var lastAppliedPowerSavingsBalanced: Boolean? = null
    private val displayManager by lazy { getSystemService(DisplayManager::class.java) }
    private val powerManager by lazy { getSystemService(PowerManager::class.java) }
    private val refreshPolicyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { updateRefreshRate() }
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val currentDisplay = window.decorView.display
            if (currentDisplay != null && currentDisplay.displayId == displayId) {
                cachedDisplayId = -1
                updateRefreshRate()
            }
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> com.example.performance.BackgroundWorkCoordinator.touch(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> com.example.performance.BackgroundWorkCoordinator.touch(false)
        }
        val now = SystemClock.uptimeMillis()
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.actionMasked == MotionEvent.ACTION_UP ||
            now - lastInteractionMs >= 200L) {
            lastInteractionMs = now
            com.example.performance.BackgroundWorkCoordinator.interaction()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        com.example.performance.BackgroundWorkCoordinator.interaction()
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (!powerReceiverRegistered) {
            ContextCompat.registerReceiver(this, refreshPolicyReceiver,
                IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
            powerReceiverRegistered = true
        }
        performanceSettingsJob?.cancel()
        performanceSettingsJob = lifecycleScope.launch {
            com.example.data.local.OtherSettingsPreferences
                .observePerformanceSettings(applicationContext)
                .collect { updateRefreshRate() }
        }
        updateRefreshRate()
        if (supportsHighRefresh && !displayListenerRegistered) {
            displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            displayListenerRegistered = true
        }
        startFrameMetricsObserver()
    }

    override fun onStop() {
        performanceSettingsJob?.cancel()
        performanceSettingsJob = null
        if (powerReceiverRegistered) {
            try { unregisterReceiver(refreshPolicyReceiver) } catch (_: IllegalArgumentException) {}
            powerReceiverRegistered = false
        }
        if (displayListenerRegistered) {
            displayManager?.unregisterDisplayListener(displayListener)
            displayListenerRegistered = false
        }
        stopFrameMetricsObserver()
        super.onStop()
    }

    private fun startFrameMetricsObserver() {
        if (frameListener != null) return
        val thread = HandlerThread("ui-frame-observer", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        frameThread = thread
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            val duration = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val deadline = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                metrics.getMetric(FrameMetrics.DEADLINE)
            } else 0L
            val budget = if (deadline > 0L) deadline else frameBudgetNanos
            com.example.performance.BackgroundWorkCoordinator.frame(duration, budget)
        }
        frameListener = listener
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
    }

    private fun stopFrameMetricsObserver() {
        frameListener?.let { listener ->
            try {
                window.removeOnFrameMetricsAvailableListener(listener)
            } catch (_: Exception) {}
            frameListener = null
        }
        frameThread?.quitSafely()
        frameThread = null
    }

    private val playerViewModel: PlayerViewModel by viewModels {
        val app = application as XtremeMusicApp
        PlayerViewModel.provideFactory(app.repository, app.playbackManager)
    }

    @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Explicitly enforce hardware acceleration at the window surface level
        window.setFlags(
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Leave resolution selection to Android; refresh hints follow foreground/focus below.
        window.attributes = window.attributes.apply {
            preferredDisplayModeId = 0
            preferredRefreshRate = 0f
        }

        val app = application as XtremeMusicApp
        app.prepareLaunchSettings()
        // Keep the Android launch window until local settings are ready, avoiding
        // a frame of onboarding/default colours for a returning user.
        window.decorView.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val ready = initialContentReady
                if (ready) window.decorView.viewTreeObserver.removeOnPreDrawListener(this)
                return ready
            }
        })
        setContent {
            // Only the tiny local appearance/profile snapshot belongs behind the
            // normal Android launch window. Player/network/tab data never gates it.
            val launchSettings by app.launchSettings.collectAsStateWithLifecycle()
            if (launchSettings == null) return@setContent
            androidx.compose.runtime.LaunchedEffect(launchSettings) {
                updateRefreshRate()
            }
            androidx.compose.runtime.SideEffect { initialContentReady = true }
            val themeSelection by playerViewModel.themeSelection.collectAsStateWithLifecycle()
            val themeMode = themeSelection.mode
            val textScale by playerViewModel.textScale.collectAsStateWithLifecycle()
            val uiScale by playerViewModel.uiScale.collectAsStateWithLifecycle()
            val isOnboardingCompleted by playerViewModel.isOnboardingCompleted.collectAsStateWithLifecycle()
            val onboardingRestore by playerViewModel.onboardingRestore.collectAsStateWithLifecycle()
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
                customThemeState = androidx.compose.runtime.remember(themeSelection, effectiveDark) {
                    themeSelection.palette.takeIf { it.isPresetDark == effectiveDark }
                        ?: com.example.data.local.ThemePreferences.getThemeForDarkMode(this@MainActivity, effectiveDark)
                },
                textScale = textScale,
                uiScale = uiScale
            ) {
                androidx.compose.animation.Crossfade(
                    targetState = isOnboardingCompleted,
                    // Export existing tags only in the profile-generation variant; release semantics stay unchanged.
                    modifier = if (BuildConfig.BUILD_TYPE.startsWith("nonMinified")) Modifier.semantics {
                        testTagsAsResourceId = true
                    } else Modifier,
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
                            },
                            restoreState = onboardingRestore,
                            onRestoreBackup = playerViewModel::restoreOnboardingBackup
                        )
                    }
                }
            }
        }
        mediaSearchRequestId = savedInstanceState?.getString("media_search_request_id")
        mediaSearchDispatched = savedInstanceState?.getBoolean("media_search_dispatched", false) == true
        handleMediaSearchIntent(intent, mediaSearchRequestId, mediaSearchDispatched)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMediaSearchIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        mediaSearchRequestId?.let { outState.putString("media_search_request_id", it) }
        outState.putBoolean("media_search_dispatched", mediaSearchDispatched)
        super.onSaveInstanceState(outState)
    }

    private fun handleMediaSearchIntent(incoming: Intent?, restoredRequestId: String? = null,
        alreadyDispatched: Boolean = false) {
        val query = incoming?.mediaPlaybackSearchQuery(alreadyDispatched) ?: return
        val requestId = restoredRequestId ?: java.util.UUID.randomUUID().toString()
        mediaSearchRequestId = requestId
        mediaSearchDispatched = false
        mediaSearchIntentJob?.cancel()
        mediaSearchIntentJob = lifecycleScope.launch {
            val app = application as XtremeMusicApp
            app.launchSettings.first { it != null }
            playerViewModel.settingsReady.first { it }
            playerViewModel.isOnboardingCompleted.first { it }
            playerViewModel.playMediaSearch(query, requestId)
            mediaSearchDispatched = true
        }
    }
    override fun onResume() {
        super.onResume()
        uiResumed = true
        observeForegroundDraw()
        updateRefreshRate()
        com.example.performance.BackgroundWorkCoordinator.foreground(true)
        // Recover an existing verified download/install session immediately. This
        // does not perform the optional GitHub release check.
        com.example.updater.AppUpdateManager.onForeground(this)
    }

    override fun onPause() {
        uiResumed = false
        removeFirstDrawListener()
        updateRefreshRate()
        com.example.performance.BackgroundWorkCoordinator.foreground(false)
        com.example.updater.AppUpdateManager.onBackground()
        super.onPause()
    }

    private fun observeForegroundDraw() {
        removeFirstDrawListener()
        com.example.performance.BackgroundWorkCoordinator.expectForegroundDraw()
        val decor = window.decorView
        val listener = object : android.view.ViewTreeObserver.OnDrawListener {
            private var firstPaintPosted = false
            private var fullyDrawnPosted = false
            override fun onDraw() {
                if (!initialContentReady || !uiResumed) return
                // Post after the actual traversal. Frame-clock callbacks can run
                // before content has been drawn and cannot establish this milestone.
                if (!firstPaintPosted) {
                    firstPaintPosted = true
                    decor.post {
                        if (firstDrawListener === this && uiResumed) {
                            com.example.performance.BackgroundWorkCoordinator.firstFrameRendered()
                            (application as? XtremeMusicApp)?.installRuntimeProfileAfterFirstDraw()
                        }
                    }
                }
                if (!fullyDrawnPosted) {
                    fullyDrawnPosted = true
                    decor.post {
                        if (firstDrawListener === this && uiResumed) {
                            removeFirstDrawListener()
                            reportFullyDrawn()
                        }
                    }
                }
            }
        }
        firstDrawListener = listener
        decor.viewTreeObserver.addOnDrawListener(listener)
        decor.invalidate()
    }

    private fun removeFirstDrawListener() {
        firstDrawListener?.let { listener ->
            window.decorView.viewTreeObserver.takeIf { it.isAlive }?.removeOnDrawListener(listener)
        }
        firstDrawListener = null
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        com.example.performance.BackgroundWorkCoordinator.windowFocus(hasFocus)
        updateRefreshRate()
    }

    private fun updateRefreshRate() {
        val decor = window.decorView
        val display = decor.display ?: return
        val settings = com.example.data.local.OtherSettingsPreferences.performanceSettings(applicationContext)

        if (cachedDisplayId != display.displayId) {
            cachedDisplayId = display.displayId
            val (highRefresh, maxRate) = com.example.performance.DisplayRefreshRatePolicy.resolveDisplayRefreshCapabilities(display)
            supportsHighRefresh = highRefresh
            cachedHighRefreshRate = maxRate
            if (supportsHighRefresh && !displayListenerRegistered && uiResumed) {
                displayManager?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
                displayListenerRegistered = true
            }
        }

        val actualRate = display.refreshRate.takeIf { it.isFinite() && it > 0f } ?: 60f
        frameBudgetNanos = (1_000_000_000L / actualRate).toLong()

        // High Performance opts out of Android's frame-rate power-saving
        // balancing on API 35+, while still allowing thermal/system safety throttling.
        val active = uiResumed && decor.hasWindowFocus()
        val requestedHz = if (settings.highPerformanceEnabled) {
            com.example.performance.DisplayRefreshRatePolicy.resolvePreferredRefreshRate(
                display,
                settings.refreshRateHz
            )
        } else {
            0
        }
        val targetRate = if (active) requestedHz.toFloat() else 0f

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            if (lastAppliedCategory != targetRate) {
                // Numeric request is important: CATEGORY_HIGH alone may resolve to 90Hz
                // even when the panel exposes a 120Hz mode.
                decor.requestedFrameRate = targetRate
                lastAppliedCategory = targetRate
            }
            if (lastAppliedBoost != active) {
                window.frameRateBoostOnTouchEnabled = active
                lastAppliedBoost = active
            }
            val powerSavingsBalanced = !settings.highPerformanceEnabled
            if (lastAppliedPowerSavingsBalanced != powerSavingsBalanced) {
                window.setFrameRatePowerSavingsBalanced(powerSavingsBalanced)
                lastAppliedPowerSavingsBalanced = powerSavingsBalanced
            }
        }

        // preferredRefreshRate is the platform window hint on supported Android versions.
        // API 35+ also receives the exact numeric View.requestedFrameRate hint above. Zero means Auto.
        if (lastAppliedLegacyRate != targetRate) {
            val attributes = window.attributes
            if (attributes.preferredDisplayModeId != 0 || attributes.preferredRefreshRate != targetRate) {
                attributes.preferredDisplayModeId = 0
                attributes.preferredRefreshRate = targetRate
                window.attributes = attributes
            }
            lastAppliedLegacyRate = targetRate
        }
    }

}

/** A blank voice request means resume; other launch intents are not playback commands. */
internal fun Intent.mediaPlaybackSearchQuery(alreadyDispatched: Boolean = false): String? {
    if (alreadyDispatched || action != android.provider.MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return null
    val query = getStringExtra(android.app.SearchManager.QUERY).orEmpty().trim()
    return query.ifBlank {
        listOfNotNull(getStringExtra(android.provider.MediaStore.EXTRA_MEDIA_TITLE),
            getStringExtra(android.provider.MediaStore.EXTRA_MEDIA_ARTIST))
            .map(String::trim).filter(String::isNotEmpty).joinToString(" ")
    }.take(512)
}
