package com.example.ui.util

import com.example.performance.AppDispatchers

import android.content.Context
import coil.ComponentRegistry
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.DefaultRequestOptions
import coil.request.Disposable
import coil.request.ImageRequest
import coil.request.ImageResult
import coil.request.ErrorResult
import com.example.performance.BackgroundWorkCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/** A lightweight singleton handle. Early Compose requests suspend; they never build
 * an OkHttp client or open a disk cache on Main, and never construct a second loader. */
class AsyncImageLoader(
    private val context: Context,
    private val factory: () -> ImageLoader
) : ImageLoader {
    private val scope = CoroutineScope(SupervisorJob() + AppDispatchers.Io)
    @Volatile private var initialized: ImageLoader? = null
    private val ready = scope.async(start = CoroutineStart.LAZY) {
        BackgroundWorkCoordinator.traceWork("ImageLoader init") {
            factory().also { loader ->
                // Force lazy cache factories here, not in the first UI getter.
                loader.memoryCache
                loader.diskCache
                initialized = loader
            }
        }
    }
    fun start() { ready.start() }
    suspend fun await(): ImageLoader = ready.await()

    override val defaults = DefaultRequestOptions().copy(
        interceptorDispatcher = AppDispatchers.Io,
        fetcherDispatcher = AppDispatchers.Io,
        decoderDispatcher = AppDispatchers.CpuBounded,
        transformationDispatcher = AppDispatchers.CpuBounded
    )
    override val components: ComponentRegistry get() = initialized?.components ?: emptyComponents
    override val memoryCache: MemoryCache? get() = initialized?.memoryCache
    override val diskCache: DiskCache? get() = initialized?.diskCache

    override suspend fun execute(request: ImageRequest): ImageResult = try { await().execute(request) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { ErrorResult(null, request, error) }
    override fun enqueue(request: ImageRequest): Disposable {
        initialized?.let { return it.enqueue(request) }
        val result = scope.async {
            val disposable = await().enqueue(request)
            try { disposable.job.await() }
            finally { if (!disposable.job.isCompleted) disposable.dispose() }
        }
        return object : Disposable {
            override val job: Deferred<ImageResult> = result
            override val isDisposed: Boolean get() = result.isCancelled
            override fun dispose() { result.cancel() }
        }
    }
    override fun newBuilder(): ImageLoader.Builder = initialized?.newBuilder() ?: ImageLoader.Builder(context)
    override fun shutdown() { scope.cancel(); initialized?.shutdown() }

    private companion object { val emptyComponents = ComponentRegistry.Builder().build() }
}
