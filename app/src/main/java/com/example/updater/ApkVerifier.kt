package com.example.updater

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

internal class ObsoleteApk : IOException("The downloaded APK is not newer than the installed app.")

/** The platform installer remains the final authority; never bypass its confirmation or checks. */
internal object ApkVerifier {
    private fun version(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    suspend fun verify(context: Context, file: File, info: UpdateInfo): String {
        require(file.isFile && file.length() > 0) { "The downloaded APK is missing or empty." }
        require(info.apkSizeBytes > 0 && file.length() == info.apkSizeBytes) { "The APK size does not match the release. Download it again." }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        info.sha256?.let { expected ->
            require(expected.matches(Regex("[a-fA-F0-9]{64}")) && MessageDigest.isEqual(actual.toByteArray(), expected.lowercase().toByteArray())) {
                "APK SHA-256 verification failed. The file was rejected."
            }
        }
        // Reject truncated/corrupt ZIP entries even when a publisher supplies no digest.
        ZipFile(file).use { zip ->
            require(zip.getEntry("AndroidManifest.xml") != null) { "The release asset is not an Android APK." }
            val entries = zip.entries()
            val names = mutableSetOf<String>()
            val buffer = ByteArray(64 * 1024)
            var expandedBytes = 0L
            while (entries.hasMoreElements()) {
                currentCoroutineContext().ensureActive()
                val entry = entries.nextElement()
                require(names.add(entry.name) && names.size <= 100_000) { "Invalid APK archive entries." }
                if (entry.isDirectory) continue
                val crc = CRC32()
                var size = 0L
                zip.getInputStream(entry).use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count; expandedBytes += count
                        require(expandedBytes <= 2L * 1024 * 1024 * 1024) { "APK archive exceeds the validation limit." }
                        crc.update(buffer, 0, count)
                    }
                }
                require(size == entry.size && crc.value == entry.crc) { "The APK archive is corrupt. Download it again." }
            }
        }
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else @Suppress("DEPRECATION") PackageManager.GET_SIGNATURES
        @Suppress("DEPRECATION") val archive = pm.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw IOException("Android could not read or verify this APK.")
        require(archive.packageName == context.packageName) { "This APK belongs to a different application." }
        @Suppress("DEPRECATION") val installed = pm.getPackageInfo(context.packageName, flags)
        require(signersCompatible(installed, archive)) { "The APK signing certificate is incompatible with this installed app." }
        if (version(archive) <= version(installed)) throw ObsoleteApk()
        require((archive.applicationInfo?.minSdkVersion ?: 0) <= Build.VERSION.SDK_INT) { "This update requires a newer Android version." }
        return if (info.sha256 != null) "SHA-256, package and signing identity verified."
            else "No trusted SHA-256 was published; package and signing identity verified."
    }

    private fun signersCompatible(installed: PackageInfo, archive: PackageInfo): Boolean {
        if (Build.VERSION.SDK_INT >= 28) {
            val current = installed.signingInfo ?: return false
            val candidate = archive.signingInfo ?: return false
            val currentSigners = current.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
            val candidateSigners = candidate.apkContentsSigners.orEmpty().map { it.toCharsString() }.toSet()
            if (currentSigners.isEmpty() || candidateSigners.isEmpty()) return false
            if (current.hasMultipleSigners() || candidate.hasMultipleSigners()) return currentSigners == candidateSigners
            // Accept only forward, platform-validated certificate rotation, never a shared old ancestor.
            return currentSigners == candidateSigners || candidate.signingCertificateHistory.orEmpty()
                .map { it.toCharsString() }.containsAll(currentSigners)
        }
        @Suppress("DEPRECATION") val current = installed.signatures.orEmpty().map { it.toCharsString() }.toSet()
        @Suppress("DEPRECATION") val candidate = archive.signatures.orEmpty().map { it.toCharsString() }.toSet()
        return current.isNotEmpty() && current == candidate
    }
}
