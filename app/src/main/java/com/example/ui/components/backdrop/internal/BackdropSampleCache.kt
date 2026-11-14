package com.example.ui.components.backdrop.internal

/** A sampled display list is reusable only for the same source and physical mapping. */
internal class BackdropSampleCache {
    private var revision = -1L
    private var raster: BackdropRaster? = null
    private var padding = Float.NaN
    private val transform = FloatArray(16)
    private var valid = false

    fun matches(revision: Long, raster: BackdropRaster, padding: Float, transform: FloatArray): Boolean =
        valid && this.revision == revision && this.raster == raster && this.padding == padding &&
            this.transform.contentEquals(transform)

    fun recorded(revision: Long, raster: BackdropRaster, padding: Float, transform: FloatArray) {
        this.revision = revision
        this.raster = raster
        this.padding = padding
        transform.copyInto(this.transform)
        valid = true
    }

    fun invalidate() { valid = false }
}
