package com.example.performance

import android.content.Context
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.profileinstaller.ProfileInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Optional library initialization uses existing lifecycle and background admission.
 * ProcessLifecycle and OkHttp's platform initializer remain in AndroidX Startup. */
object DeferredLibraryInitialization {
    private val started = AtomicBoolean()
    private val textStarted = AtomicBoolean()
    private val profileStarted = AtomicBoolean()

    /** App-owned and independent of Home/network/maintenance readiness. */
    internal fun installProfileAfterFirstDraw(context: Context, scope: CoroutineScope,
        awaitDraw: suspend () -> Unit = BackgroundWorkCoordinator::awaitFirstFrame,
        install: (Context) -> Boolean = ::writeRuntimeProfile
    ): kotlinx.coroutines.Job? {
        if (!profileStarted.compareAndSet(false, true)) return null
        return scope.launch(AppDispatchers.Io) {
            try {
                awaitDraw()
                BackgroundWorkCoordinator.localDataPreparation(awaitFrameQuiet = false) {
                    BackgroundWorkCoordinator.awaitOptionalBatch()
                    BackgroundWorkCoordinator.traceWork("Baseline profile install") {
                        if (!install(context.applicationContext)) profileStarted.set(false)
                    }
                }
            } catch (cancelled: CancellationException) { profileStarted.set(false); throw cancelled }
            catch (error: Exception) {
                profileStarted.set(false)
                android.util.Log.w("LibraryInit", "Runtime profile installation can retry on a later foreground", error)
            }
        }
    }

    private fun writeRuntimeProfile(context: Context): Boolean {
        var retryableFailure = false
        ProfileInstaller.writeProfile(context, Executor { it.run() }, object : ProfileInstaller.DiagnosticsCallback {
            override fun onDiagnosticReceived(code: Int, data: Any?) = Unit
            override fun onResultReceived(code: Int, data: Any?) {
                retryableFailure = code == ProfileInstaller.RESULT_IO_EXCEPTION
                android.util.Log.d("RuntimeProfile", "result=$code")
            }
        })
        return !retryableFailure
    }
    suspend fun initializeTextSupport(context: Context) = withContext(AppDispatchers.Io) {
        if (!textStarted.compareAndSet(false, true)) return@withContext
        try {
            BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.DEFERRED,
                label = "Text support") {
                DefaultEmojiCompatConfig.create(context)?.let { config ->
                    config.setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
                    config.setLoadingExecutor(Executor { command -> command.run() })
                    EmojiCompat.init(config).load()
                }
            }
        } catch (cancelled: CancellationException) { textStarted.set(false); throw cancelled }
        catch (error: Exception) { textStarted.set(false); android.util.Log.w("LibraryInit", "Text support unavailable", error) }
    }
    suspend fun initialize(context: Context) = withContext(AppDispatchers.Io) {
        if (!started.compareAndSet(false, true)) return@withContext
        for ((name, init) in listOf<Pair<String, () -> Unit>>(
            "Firebase init" to { com.google.firebase.FirebaseApp.initializeApp(context) }
        )) {
            try {
                BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.MAINTENANCE, requireForeground = false,
                    label = name) { init() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("LibraryInit", "$name unavailable", error) }
        }
    }
}
