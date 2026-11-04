package com.example.ui.util

import android.graphics.Bitmap
import coil.EventListener
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.Options
import coil.request.SuccessResult
import com.example.performance.BackgroundWorkCoordinator

/** Installed only while tracing. Cache probes/fallbacks retain Coil's request ownership. */
internal class ArtworkTraceEventListener(
    private val trace: (String) -> AutoCloseable = BackgroundWorkCoordinator::trace
) : EventListener {
    private var fetch: AutoCloseable? = null
    private var decode: AutoCloseable? = null
    private var transform: AutoCloseable? = null

    override fun fetchStart(request: ImageRequest, fetcher: Fetcher, options: Options) {
        fetch?.close(); fetch = trace("Artwork fetch")
    }
    override fun fetchEnd(request: ImageRequest, fetcher: Fetcher, options: Options, result: FetchResult?) {
        fetch?.close(); fetch = null
    }
    override fun decodeStart(request: ImageRequest, decoder: Decoder, options: Options) {
        decode?.close(); decode = trace("Artwork decode")
    }
    override fun decodeEnd(request: ImageRequest, decoder: Decoder, options: Options, result: DecodeResult?) {
        decode?.close(); decode = null
    }
    override fun transformStart(request: ImageRequest, input: Bitmap) {
        transform?.close(); transform = trace("Artwork transform")
    }
    override fun transformEnd(request: ImageRequest, output: Bitmap) {
        transform?.close(); transform = null
    }
    override fun onCancel(request: ImageRequest) = close()
    override fun onError(request: ImageRequest, result: ErrorResult) = close()
    override fun onSuccess(request: ImageRequest, result: SuccessResult) = close()

    private fun close() {
        fetch?.close(); fetch = null
        decode?.close(); decode = null
        transform?.close(); transform = null
    }
}
