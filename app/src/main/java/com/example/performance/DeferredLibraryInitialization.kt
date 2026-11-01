package com.example.performance

import android.content.Context
import androidx.emoji2.text.DefaultEmojiCompatConfig
import androidx.emoji2.text.EmojiCompat
import androidx.profileinstaller.ProfileInstaller
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** Replaces the optional automatic provider/500ms/5s timers with the existing idle queue.
 * ProcessLifecycle and OkHttp's platform initializer remain in AndroidX Startup. */
object DeferredLibraryInitialization {
    private val started = AtomicBoolean()
    private val textStarted = AtomicBoolean()
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
            "Firebase init" to { com.google.firebase.FirebaseApp.initializeApp(context) },
            "Profile install" to { ProfileInstaller.writeProfile(context) }
        )) {
            try {
                BackgroundWorkCoordinator.startup(BackgroundWorkCoordinator.StartupPriority.MAINTENANCE, requireForeground = false,
                    label = name) { init() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.w("LibraryInit", "$name unavailable", error) }
        }
    }
}
