package com.example.downloads

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException

/** The ledger is committed before publishing state or deleting the audio it describes. */
internal interface DownloadIndex {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

internal class AtomicDownloadIndex(path: () -> File) : DownloadIndex {
    private val file by lazy { AtomicFile(path()) }
    override fun read(): ByteArray? = try {
        file.openRead().use { it.readBytes() }
    } catch (missing: FileNotFoundException) {
        if (file.baseFile.exists()) throw missing
        null
    }
    override fun write(bytes: ByteArray) {
        val stream = file.startWrite()
        try { stream.write(bytes); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
}
