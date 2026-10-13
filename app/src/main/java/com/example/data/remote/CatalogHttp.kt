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

internal suspend fun OkHttpClient.catalogRequest(request: Request): String = suspendCancellableCoroutine { continuation ->
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
                    val body = it.body?.string() ?: throw IOException("Empty response")
                    if (!continuation.isCancelled) continuation.resume(body)
                } catch (e: Exception) {
                    if (!continuation.isCancelled) continuation.resumeWithException(e)
                }
            }
        }
    })
}
internal class CatalogHttpException(val status: Int, val retryAfter: Long?) : IOException("Catalog HTTP $status")
