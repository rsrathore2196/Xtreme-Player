package com.example.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Cancellable catalog/metadata HTTP; the Media3 audio data-source pipeline is unchanged. */
internal suspend fun OkHttpClient.catalogGet(url: String): String = catalogRequest(
    Request.Builder().url(url).header("User-Agent", "Mozilla/5.0")
        .header("Accept", "application/json").build()
)

internal suspend fun OkHttpClient.catalogRequest(request: Request, maxResponseBytes: Long? = null): String =
    com.example.performance.BackgroundWorkCoordinator.work { suspendCancellableCoroutine { continuation ->
    val call = newCall(request)
    continuation.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                try {
                    if (!it.isSuccessful) throw CatalogHttpException(it.code, it.header("Retry-After")?.toLongOrNull())
                    val responseBody = it.body ?: throw IOException("Empty response")
                    val body = if (maxResponseBytes == null) responseBody.string() else {
                        val buffer = okio.Buffer()
                        val source = responseBody.source()
                        while (buffer.size <= maxResponseBytes &&
                            source.read(buffer, minOf(8192L, maxResponseBytes + 1L - buffer.size)) != -1L) Unit
                        if (buffer.size > maxResponseBytes) throw IOException("Catalog response exceeds its metadata budget")
                        buffer.readUtf8()
                    }
                    if (!continuation.isCancelled) continuation.resume(body)
                } catch (e: Exception) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            }
        }
    })
} }
internal class CatalogHttpException(val status: Int, val retryAfter: Long?) : IOException("Catalog HTTP $status")
