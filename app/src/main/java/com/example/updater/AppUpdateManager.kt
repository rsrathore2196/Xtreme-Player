package com.example.updater

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Metadata representation of a GitHub release update.
 */
data class UpdateInfo(
    val versionName: String,
    val releaseTitle: String,
    val releaseNotes: String,
    val apkDownloadUrl: String,
    val apkFileName: String,
    val apkSizeBytes: Long = 0L,
    val publishedAt: String = "",
    val htmlUrl: String = ""
)

sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Downloading(val progressPercent: Int, val downloadId: Long, val updateInfo: UpdateInfo) : UpdateStatus()
    data class ReadyToInstall(val apkFile: File, val updateInfo: UpdateInfo) : UpdateStatus()
    data class Error(val message: String) : UpdateStatus()
}

/**
 * Centralized, production-grade GitHub Release Auto & Manual Update Manager.
 * Communicates with https://api.github.com/repos/rsrathore2196/Xtreme-Player/releases/latest
 */
object AppUpdateManager {
    private const val TAG = "AppUpdateManager"
    private const val GITHUB_LATEST_RELEASE_URL =
        "https://api.github.com/repos/rsrathore2196/Xtreme-Player/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus: StateFlow<UpdateStatus> = _updateStatus.asStateFlow()

    private val _isDismissedForSession = MutableStateFlow(false)
    val isDismissedForSession: StateFlow<Boolean> = _isDismissedForSession.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private var activeDownloadId: Long = -1L
    private var activeUpdateInfo: UpdateInfo? = null
    private var downloadReceiverRegistered = false
    private var progressPollingJob: Job? = null

    /**
     * Dismiss the floating update pop-up for the remainder of this app session.
     */
    fun dismissForSession() {
        _isDismissedForSession.value = true
    }

    /**
     * Checks for updates against GitHub Releases.
     * @param isManual true if triggered by clicking "Check for Update" in Settings/About.
     */
    fun checkForUpdate(
        context: Context,
        isManual: Boolean = false,
        onResult: ((UpdateStatus) -> Unit)? = null
    ) {
        if (_updateStatus.value is UpdateStatus.Checking || _updateStatus.value is UpdateStatus.Downloading) {
            return
        }

        _updateStatus.value = UpdateStatus.Checking

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                fetchLatestReleaseInfo()
            }

            _updateStatus.value = result
            onResult?.invoke(result)
        }
    }

    private fun fetchLatestReleaseInfo(): UpdateStatus {
        try {
            val request = Request.Builder()
                .url(GITHUB_LATEST_RELEASE_URL)
                .header("Accept", "application/vnd.github.v3+json")
                .header("User-Agent", "Xtreme-Player-Android/${BuildConfig.VERSION_NAME}")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                return if (code == 403) {
                    UpdateStatus.Error("GitHub API rate limit reached. Please try again later.")
                } else if (code == 404) {
                    UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
                } else {
                    UpdateStatus.Error("Failed to check for updates (HTTP $code)")
                }
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) {
                return UpdateStatus.Error("Empty response from update server")
            }

            val json = JSONObject(body)
            val tagName = json.optString("tag_name", "").trim()
            val releaseTitle = json.optString("name", tagName).ifBlank { tagName }
            val releaseNotes = json.optString("body", "").trim()
            val publishedAt = json.optString("published_at", "")
            val htmlUrl = json.optString("html_url", "")

            // Extract direct .apk browser download URL from assets
            val assetsArray = json.optJSONArray("assets")
            var apkDownloadUrl = ""
            var apkFileName = "XtremePlayer-update.apk"
            var apkSizeBytes = 0L

            if (assetsArray != null && assetsArray.length() > 0) {
                for (i in 0 until assetsArray.length()) {
                    val assetObj = assetsArray.optJSONObject(i) ?: continue
                    val name = assetObj.optString("name", "")
                    val downloadUrl = assetObj.optString("browser_download_url", "")
                    val size = assetObj.optLong("size", 0L)

                    if (name.endsWith(".apk", ignoreCase = true) || downloadUrl.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = downloadUrl
                        apkFileName = name.ifBlank { "XtremePlayer-$tagName.apk" }
                        apkSizeBytes = size
                        break
                    }
                }

                // If no asset ended explicitly with .apk, pick the first asset with a browser_download_url
                if (apkDownloadUrl.isBlank() && assetsArray.length() > 0) {
                    val firstAsset = assetsArray.optJSONObject(0)
                    apkDownloadUrl = firstAsset?.optString("browser_download_url", "").orEmpty()
                    apkFileName = firstAsset?.optString("name", "XtremePlayer-$tagName.apk").orEmpty()
                    apkSizeBytes = firstAsset?.optLong("size", 0L) ?: 0L
                }
            }

            val cleanRemoteVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val cleanLocalVersion = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V").trim()

            val isNewer = isNewerVersion(cleanRemoteVersion, cleanLocalVersion)

            return if (isNewer && apkDownloadUrl.isNotBlank()) {
                val updateInfo = UpdateInfo(
                    versionName = cleanRemoteVersion,
                    releaseTitle = releaseTitle,
                    releaseNotes = releaseNotes,
                    apkDownloadUrl = apkDownloadUrl,
                    apkFileName = apkFileName,
                    apkSizeBytes = apkSizeBytes,
                    publishedAt = publishedAt,
                    htmlUrl = htmlUrl
                )
                UpdateStatus.UpdateAvailable(updateInfo)
            } else {
                UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching release info", e)
            return UpdateStatus.Error(e.localizedMessage ?: "Network error while checking updates")
        }
    }

    /**
     * Compares two semantic version strings (e.g. "1.7.1" vs "1.7.0").
     * Returns true if remote is strictly newer than local.
     */
    fun isNewerVersion(remoteVersion: String, localVersion: String): Boolean {
        try {
            val remoteParts = remoteVersion.split(".", "-", "_")
                .mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
            val localParts = localVersion.split(".", "-", "_")
                .mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, localParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val l = localParts.getOrElse(i) { 0 }
                if (r > l) return true
                if (r < l) return false
            }
            return false
        } catch (e: Exception) {
            return remoteVersion.compareTo(localVersion, ignoreCase = true) > 0
        }
    }

    /**
     * Starts download via Android DownloadManager and triggers installation on completion.
     */
    fun downloadAndInstallUpdate(context: Context, updateInfo: UpdateInfo) {
        val appContext = context.applicationContext
        activeUpdateInfo = updateInfo

        try {
            val downloadManager = appContext.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (downloadManager == null) {
                _updateStatus.value = UpdateStatus.Error("DownloadManager unavailable")
                return
            }

            // Ensure destination directory in external cache/files
            val destinationFile = File(
                appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                updateInfo.apkFileName
            )
            if (destinationFile.exists()) {
                destinationFile.delete()
            }

            val request = DownloadManager.Request(Uri.parse(updateInfo.apkDownloadUrl))
                .setTitle("Xtreme Player v${updateInfo.versionName}")
                .setDescription("Downloading latest update (${updateInfo.versionName})...")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationUri(Uri.fromFile(destinationFile))
                .setMimeType("application/vnd.android.package-archive")

            activeDownloadId = downloadManager.enqueue(request)
            _updateStatus.value = UpdateStatus.Downloading(0, activeDownloadId, updateInfo)

            registerDownloadReceiver(appContext, destinationFile, updateInfo)
            startProgressTracking(downloadManager, activeDownloadId, updateInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Error initiating download", e)
            _updateStatus.value = UpdateStatus.Error(e.localizedMessage ?: "Failed to start download")
        }
    }

    private fun registerDownloadReceiver(context: Context, apkFile: File, updateInfo: UpdateInfo) {
        if (downloadReceiverRegistered) return

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val action = intent?.action
                if (DownloadManager.ACTION_DOWNLOAD_COMPLETE == action) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id == activeDownloadId && id != -1L) {
                        progressPollingJob?.cancel()
                        _downloadProgress.value = 100
                        _updateStatus.value = UpdateStatus.ReadyToInstall(apkFile, updateInfo)

                        c?.let { installDownloadedApk(it, apkFile) }

                        try {
                            c?.unregisterReceiver(this)
                            downloadReceiverRegistered = false
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        try {
            val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            downloadReceiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register download receiver", e)
        }
    }

    private fun startProgressTracking(
        downloadManager: DownloadManager,
        downloadId: Long,
        updateInfo: UpdateInfo
    ) {
        progressPollingJob?.cancel()
        progressPollingJob = scope.launch(Dispatchers.IO) {
            val query = DownloadManager.Query().setFilterById(downloadId)
            while (isActive) {
                var isCompleted = false
                try {
                    val cursor = downloadManager.query(query)
                    if (cursor != null && cursor.moveToFirst()) {
                        val bytesDownloaded = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                        )
                        val bytesTotal = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                        )
                        val status = cursor.getInt(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)
                        )

                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            isCompleted = true
                            _downloadProgress.value = 100
                        } else if (bytesTotal > 0) {
                            val progress = ((bytesDownloaded * 100L) / bytesTotal).toInt()
                            _downloadProgress.value = progress.coerceIn(0, 99)
                            _updateStatus.value = UpdateStatus.Downloading(progress, downloadId, updateInfo)
                        }
                    }
                    cursor?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error polling download progress", e)
                }

                if (isCompleted) break
                delay(400)
            }
        }
    }

    /**
     * Prompts the native Android package installer to install the downloaded APK.
     * Handles unknown sources permission on Android 8.0+ (Oreo).
     */
    fun installDownloadedApk(context: Context, apkFile: File) {
        try {
            if (!apkFile.exists()) {
                _updateStatus.value = UpdateStatus.Error("APK file not found")
                return
            }

            // Check Unknown Sources permission on Android 8.0+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            context.startActivity(installIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer", e)
            _updateStatus.value = UpdateStatus.Error("Install failed: ${e.localizedMessage}")
        }
    }
}
