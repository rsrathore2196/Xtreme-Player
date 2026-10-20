package com.example.updater

import android.app.DownloadManager
import android.content.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val versionName: String, val releaseTitle: String, val releaseNotes: String,
    val apkDownloadUrl: String, val apkFileName: String, val apkSizeBytes: Long = 0L,
    val publishedAt: String = "", val htmlUrl: String = "", val sha256: String? = null
)
sealed class UpdateStatus {
    object Idle : UpdateStatus()
    object Checking : UpdateStatus()
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateStatus()
    data class UpToDate(val currentVersion: String) : UpdateStatus()
    data class Downloading(val progressPercent: Int, val downloadId: Long, val updateInfo: UpdateInfo,
        val message: String = "Downloading Update") : UpdateStatus()
    data class ReadyToInstall(val apkFile: File, val updateInfo: UpdateInfo,
        val message: String = "Tap to complete installation") : UpdateStatus()
    data class Error(val message: String, val showNotice: Boolean = false) : UpdateStatus()
}

object AppUpdateManager {
    private const val LATEST = "https://api.github.com/repos/rsrathore2196/Xtreme-Player/releases/latest"
    private const val ASSET_PATH = "/rsrathore2196/Xtreme-Player/releases/download/"
    private const val MAX_AGE = 24 * 60 * 60 * 1000L
    private val client by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val operations = Mutex()
    private val _updateStatus = MutableStateFlow<UpdateStatus>(UpdateStatus.Idle)
    val updateStatus = _updateStatus.asStateFlow()
    private val _isDismissedForSession = MutableStateFlow(false)
    val isDismissedForSession = _isDismissedForSession.asStateFlow()
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress = _downloadProgress.asStateFlow()
    private var session: Session? = null
    private var initialized = false
    private var foreground = false
    private var receiver: BroadcastReceiver? = null
    private var polling: Job? = null
    private var checking: Job? = null
    @Volatile private var manualCheckRequested = false
    private var installing = false
    private data class Session(val id: Long, val info: UpdateInfo, val name: String, val created: Long,
        val ready: Boolean = false, val installRequested: Boolean = true, val waitingPermission: Boolean = false)

    private fun extractVersion(value: String): String? {
        val candidate = value.trim()
        if (candidate.isEmpty()) return null
        return Regex("""(?i)(?:^|[^0-9])v?(\d+(?:\.\d+)*)(?!\d)""").find(candidate)?.groupValues?.get(1)
    }

    fun dismissForSession() { _isDismissedForSession.value = true }
    private fun prefs(c: Context) = c.getSharedPreferences("apk_update_session_v2", Context.MODE_PRIVATE)
    private fun manager(c: Context) = c.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private fun external(c: Context, s: Session): File {
        val dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: throw IOException("Download storage is unavailable.")
        return File(dir, s.name)
    }
    private fun staged(c: Context, s: Session) = File(File(c.filesDir, "update_apks"), s.name)
    private fun validAsset(url: String): Boolean {
        val uri = Uri.parse(url)
        return uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 && uri.userInfo == null &&
            uri.path.orEmpty().startsWith(ASSET_PATH) && uri.query == null && uri.fragment == null
    }
    private fun infoJson(i: UpdateInfo) = JSONObject().put("version", i.versionName).put("title", i.releaseTitle)
        .put("notes", i.releaseNotes).put("url", i.apkDownloadUrl).put("asset", i.apkFileName)
        .put("size", i.apkSizeBytes).put("published", i.publishedAt).put("html", i.htmlUrl).put("sha256", i.sha256.orEmpty())
    private suspend fun persist(c: Context, s: Session?) = withContext(Dispatchers.IO) {
        val editor = prefs(c).edit()
        if (s == null) editor.remove("session") else editor.putString("session", JSONObject()
            .put("id", s.id).put("info", infoJson(s.info)).put("name", s.name).put("created", s.created)
            .put("ready", s.ready).put("requested", s.installRequested).put("permission", s.waitingPermission).toString())
        check(editor.commit()) { "Could not save update download state." }
    }
    private suspend fun restore(c: Context) {
        if (initialized) return
        initialized = true
        session = withContext(Dispatchers.IO) {
            prefs(c).getString("session", null)?.let { value ->
                try {
                    val j = JSONObject(value); val i = j.getJSONObject("info")
                    val info = UpdateInfo(i.getString("version"), i.getString("title"), i.getString("notes"), i.getString("url"),
                        i.getString("asset"), i.getLong("size"), i.optString("published"), i.optString("html"), i.optString("sha256").takeIf(String::isNotBlank))
                    val name = j.getString("name")
                    require(name.matches(Regex("xtreme-update-[a-f0-9-]+\\.apk")) && validAsset(info.apkDownloadUrl))
                    Session(j.getLong("id"), info, name, j.getLong("created"), j.optBoolean("ready"), j.optBoolean("requested"), j.optBoolean("permission"))
                } catch (_: Exception) { prefs(c).edit().remove("session").commit(); null }
            }
        }
    }

    fun onForeground(context: Context) {
        foreground = true
        val app = context.applicationContext
        scope.launch { operations.withLock {
            try {
                restore(app)
                if (session != null) inspect(app)
                startMonitoring(app)
                resumeInstall(app, fromResume = true)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(app, e.message ?: "Could not recover the update.") }
        } }
    }
    fun onBackground() { foreground = false; polling?.cancel(); polling = null }

    fun checkForUpdate(context: Context, isManual: Boolean = false, onResult: ((UpdateStatus) -> Unit)? = null) {
        if (checking?.isActive == true) {
            if (isManual) manualCheckRequested = true
            return
        }
        manualCheckRequested = isManual
        val app = context.applicationContext
        checking = scope.launch {
            try {
                val recovering = operations.withLock {
                    restore(app)
                    if (session != null) {
                        inspect(app); startMonitoring(app); onResult?.invoke(_updateStatus.value); true
                    } else false
                }
                if (recovering) return@launch
                // Never hold the download/installer lock while waiting for optional
                // startup admission. Recovery and manual checks remain immediate.
                com.example.performance.BackgroundWorkCoordinator.startup(
                    if (isManual) com.example.performance.BackgroundWorkCoordinator.StartupPriority.CRITICAL
                    else com.example.performance.BackgroundWorkCoordinator.StartupPriority.MAINTENANCE,
                    urgent = { manualCheckRequested }) {
                    operations.withLock {
                        if (session != null) { inspect(app); startMonitoring(app); return@withLock }
                        _updateStatus.value = UpdateStatus.Checking
                        val result = withContext(Dispatchers.IO) { latestRelease() }
                        _updateStatus.value = result
                        if (manualCheckRequested) _isDismissedForSession.value = false
                        onResult?.invoke(result)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val error = UpdateStatus.Error(e.message ?: "Could not check for updates.")
                _updateStatus.value = error; onResult?.invoke(error)
            }
        }
    }
    private fun get(url: String, maxBytes: Long = 2_000_000): String = client.newCall(Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json").header("User-Agent", "Xtreme-Player-Android/${BuildConfig.VERSION_NAME}").build())
        .execute().use { response ->
            if (!response.isSuccessful) throw IOException(when(response.code) {
                403, 429 -> "Update server rate limit reached. Please try again later."
                else -> "Update server returned HTTP ${response.code}."
            })
            val body = response.body ?: throw IOException("Empty update response.")
            val buffer = okio.Buffer()
            var total = 0L
            while (total <= maxBytes) {
                val count = body.source().read(buffer, minOf(8192L, maxBytes + 1 - total))
                if (count < 0) break
                total += count
            }
            require(total <= maxBytes) { "Update metadata exceeds the allowed size." }
            buffer.readUtf8()
        }
    private fun latestRelease(): UpdateStatus {
        val json = JSONObject(get(LATEST))
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
        val version = extractVersion(json.optString("tag_name")) ?: return UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
        if (!isNewerVersion(version, BuildConfig.VERSION_NAME)) return UpdateStatus.UpToDate(BuildConfig.VERSION_NAME)
        val array = json.optJSONArray("assets")
        val assets = (0 until (array?.length() ?: 0)).mapNotNull { array?.optJSONObject(it) }
        val apks = assets.filter { a ->
            a.optString("name").endsWith(".apk", true) && validAsset(a.optString("browser_download_url")) &&
                Uri.parse(a.optString("browser_download_url")).lastPathSegment == a.optString("name") &&
                a.optLong("size") > 0 && a.optString("state") == "uploaded" &&
                a.optString("content_type") in setOf("application/vnd.android.package-archive", "application/octet-stream")
        }
        val apk = apks.firstOrNull { it.optString("name").contains("universal", true) } ?: apks.singleOrNull()
            ?: Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> apks.singleOrNull { it.optString("name").contains(abi, true) } }
            ?: throw IOException("This release has no unambiguous compatible APK asset.")
        val name = apk.getString("name")
        val digest = apk.optString("digest").takeIf { it.startsWith("sha256:", true) }?.substringAfter(':')?.lowercase()
        if (digest != null) require(digest.matches(Regex("[a-f0-9]{64}"))) { "The release SHA-256 is malformed." }
        val sidecar = assets.firstOrNull { it.optString("name").equals("$name.sha256", true) || it.optString("name").equals("$name.sha256sum", true) }
            ?: assets.firstOrNull { it.optString("name").lowercase() in setOf("sha256sums", "sha256sums.txt", "checksums.txt") }
        val checksum = digest ?: sidecar?.let { asset ->
            val url = asset.getString("browser_download_url")
            require(validAsset(url)) { "Untrusted release checksum URL." }
            val content = get(url, 128_000)
            val matches = content.lineSequence().mapNotNull { line ->
                val m = Regex("^([a-fA-F0-9]{64})\\s+\\*?(.+?)\\s*$").matchEntire(line.trim())
                val bsd = Regex("^SHA256 \\((.+)\\) = ([a-fA-F0-9]{64})$", RegexOption.IGNORE_CASE).matchEntire(line.trim())
                if (m != null && m.groupValues[2].removePrefix("./") == name) m.groupValues[1].lowercase()
                else if (bsd != null && bsd.groupValues[1] == name) bsd.groupValues[2].lowercase()
                else if (asset.optString("name").startsWith("$name.") && line.trim().matches(Regex("[a-fA-F0-9]{64}"))) line.trim().lowercase() else null
            }.distinct().toList()
            matches.singleOrNull() ?: throw IOException("No unique SHA-256 for this APK in the published checksum file.")
        }
        val notesChecksum = if (checksum == null && apks.size == 1) {
            Regex("(?i)SHA[- ]?256\\s*[:=]\\s*`?([a-f0-9]{64})(?![a-f0-9])")
                .findAll(json.optString("body")).map { it.groupValues[1].lowercase() }.distinct().toList().singleOrNull()
        } else null
        return UpdateStatus.UpdateAvailable(UpdateInfo(version, json.optString("name", version), json.optString("body"),
            apk.getString("browser_download_url"), name, apk.getLong("size"), json.optString("published_at"), json.optString("html_url"), checksum ?: notesChecksum))
    }
    fun isNewerVersion(remoteVersion: String, localVersion: String): Boolean {
        fun parts(v: String): List<Long>? {
            val numeric = extractVersion(v) ?: return null
            return numeric.split('.').map { it.toLongOrNull() ?: return null }
        }
        val remote = parts(remoteVersion) ?: return false
        val local = parts(localVersion) ?: return false
        for (index in 0 until maxOf(remote.size, local.size)) {
            val comparison = remote.getOrElse(index) { 0 }.compareTo(local.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun downloadAndInstallUpdate(context: Context, updateInfo: UpdateInfo) {
        val app = context.applicationContext
        scope.launch { operations.withLock {
            try {
                restore(app)
                if (session != null) { inspect(app); startMonitoring(app); resumeInstall(app); return@withLock }
                require((_updateStatus.value as? UpdateStatus.UpdateAvailable)?.updateInfo == updateInfo) { "Check for this update again before downloading." }
                require(validAsset(updateInfo.apkDownloadUrl) && updateInfo.apkFileName.endsWith(".apk", true) && updateInfo.apkSizeBytes > 0) { "Invalid release APK." }
                _isDismissedForSession.value = false
                var s = Session(-1, updateInfo, "xtreme-update-${UUID.randomUUID()}.apk", System.currentTimeMillis())
                session = s
                persist(app, s) // Reserve the destination before enqueue, allowing recovery of the crash window.
                val id = withContext(Dispatchers.IO) { manager(app).enqueue(DownloadManager.Request(Uri.parse(updateInfo.apkDownloadUrl))
                    .setTitle("Xtreme Player v${updateInfo.versionName}").setDescription("Downloading app update")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationUri(Uri.fromFile(external(app, s))).setMimeType("application/vnd.android.package-archive")) }
                s = s.copy(id = id); session = s; persist(app, s)
                _downloadProgress.value = 0; _updateStatus.value = UpdateStatus.Downloading(0, id, updateInfo)
                startMonitoring(app)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(app, e.message ?: "Could not start the download.") }
        } }
    }
    private fun startMonitoring(app: Context) {
        val s = session ?: return
        if (s.ready || s.id < 0) return
        if (receiver == null) {
            val listener = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE || intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != session?.id) return
                    // The exported system broadcast is a hint only; DownloadManager is authoritative.
                    scope.launch { operations.withLock {
                        try { inspect(app); resumeInstall(app) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { fail(app, e.message ?: "Update verification failed.") }
                    } }
                }
            }
            try { ContextCompat.registerReceiver(app, listener, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED); receiver = listener }
            catch (_: Exception) { /* Foreground polling and persisted recovery remain available. */ }
        }
        if (!foreground || polling?.isActive == true) return
        polling = scope.launch {
            var errors = 0
            while (isActive && foreground && session?.ready == false) {
                operations.withLock {
                    try { inspect(app); resumeInstall(app); errors = 0 }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { if (++errors >= 3) fail(app, e.message ?: "Cannot read download progress.") }
                }
                delay(1000)
            }
        }
    }
    private data class Download(val id: Long, val status: Int, val reason: Int, val bytes: Long, val total: Long, val url: String, val local: String)
    private suspend fun query(app: Context, s: Session): Download? = withContext(Dispatchers.IO) {
        manager(app).query(if (s.id >= 0) DownloadManager.Query().setFilterById(s.id) else DownloadManager.Query()).use { cursor ->
            if (cursor == null) throw IOException("Download status is unavailable.")
            while (cursor.moveToNext()) {
                fun number(key: String) = cursor.getLong(cursor.getColumnIndexOrThrow(key))
                fun string(key: String) = cursor.getString(cursor.getColumnIndexOrThrow(key)).orEmpty()
                val local = string(DownloadManager.COLUMN_LOCAL_URI)
                if (s.id < 0 && Uri.parse(local).path != external(app, s).absolutePath) continue
                return@withContext Download(number(DownloadManager.COLUMN_ID), number(DownloadManager.COLUMN_STATUS).toInt(),
                    number(DownloadManager.COLUMN_REASON).toInt(), number(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                    number(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), string(DownloadManager.COLUMN_URI), local)
            }
            null
        }
    }
    private suspend fun inspect(app: Context) {
        var s = session ?: return
        if (s.ready) {
            if (_updateStatus.value !is UpdateStatus.ReadyToInstall) {
                try {
                    val message = withContext(Dispatchers.IO) { ApkVerifier.verify(app, staged(app, s), s.info) }
                    _updateStatus.value = UpdateStatus.ReadyToInstall(staged(app, s), s.info, message)
                } catch (_: ObsoleteApk) { clear(app); _updateStatus.value = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME) }
            }
            return
        }
        if (System.currentTimeMillis() - s.created > MAX_AGE) { fail(app, "The update download expired. Please try again."); return }
        val row = query(app, s) ?: run { fail(app, "The update download was canceled or removed."); return }
        if (s.id < 0) { s = s.copy(id = row.id); session = s; persist(app, s) }
        require(row.url == s.info.apkDownloadUrl) { "Download identity does not match the release." }
        when (row.status) {
            DownloadManager.STATUS_SUCCESSFUL -> {
                _downloadProgress.value = 99
                _updateStatus.value = UpdateStatus.Downloading(99, s.id, s.info, "Verifying APK")
                val message = try { withContext(Dispatchers.IO) {
                    val source = external(app, s)
                    require(Uri.parse(row.local).scheme == "file" && File(Uri.parse(row.local).path.orEmpty()).canonicalFile == source.canonicalFile) { "Unexpected APK download location." }
                    require(source.isFile && row.bytes > 0 && row.bytes == source.length() && (row.total <= 0 || row.total == row.bytes)) { "The APK download is empty or incomplete." }
                    val privateFile = staged(app, s)
                    check(privateFile.parentFile?.mkdirs() == true || privateFile.parentFile?.isDirectory == true) { "Update storage is unavailable." }
                    source.copyTo(privateFile, overwrite = true)
                    ApkVerifier.verify(app, privateFile, s.info)
                } } catch (e: CancellationException) { throw e }
                catch (e: Exception) { fail(app, e.message ?: "The APK is corrupt or incompatible."); return }
                s = s.copy(ready = true); session = s; persist(app, s)
                stopMonitoring(app)
                _downloadProgress.value = 100
                _updateStatus.value = UpdateStatus.ReadyToInstall(staged(app, s), s.info, message)
                if (s.info.sha256 == null && foreground) Toast.makeText(app, message, Toast.LENGTH_LONG).show()
            }
            DownloadManager.STATUS_FAILED -> fail(app, when (row.reason) {
                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage for the update."
                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Download storage is unavailable."
                DownloadManager.ERROR_HTTP_DATA_ERROR -> "The APK download was interrupted. Please retry."
                else -> "Update download failed (DownloadManager reason ${row.reason}). Please retry."
            })
            DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                val percent = if (row.total > 0) ((row.bytes.toDouble() / row.total) * 100).toInt().coerceIn(0, 99) else 0
                _downloadProgress.value = percent
                _updateStatus.value = UpdateStatus.Downloading(percent, s.id, s.info,
                    if (row.status == DownloadManager.STATUS_PAUSED) "Waiting for network / download retry" else "Downloading Update")
            }
            else -> fail(app, "The update download is incomplete.")
        }
    }
    private suspend fun stopMonitoring(app: Context) {
        val job = polling
        polling = null
        if (job != currentCoroutineContext()[Job]) job?.cancel()
        receiver?.let { runCatching { app.unregisterReceiver(it) } }; receiver = null
    }
    private suspend fun clear(app: Context) {
        val old = session; session = null
        stopMonitoring(app)
        withContext(Dispatchers.IO + NonCancellable) {
            if (old != null) {
                if (old.id >= 0) runCatching { manager(app).remove(old.id) }
                runCatching { external(app, old).delete() }; staged(app, old).delete()
            }
            persist(app, null)
        }
    }
    private suspend fun fail(app: Context, message: String) {
        try { clear(app) } catch (e: CancellationException) { throw e } catch (_: Exception) { /* State still reports failure if storage is full. */ }
        _updateStatus.value = UpdateStatus.Error(message, showNotice = true)
        _isDismissedForSession.value = false
        _downloadProgress.value = 0
        if (foreground) Toast.makeText(app, message, Toast.LENGTH_LONG).show()
    }
    fun installDownloadedApk(context: Context, apkFile: File) {
        val app = context.applicationContext
        if (installing) return
        installing = true
        scope.launch { operations.withLock {
            try {
                restore(app)
                val s = session ?: throw IOException("Check for an update before installing.")
                withContext(Dispatchers.IO) {
                    require(s.ready && apkFile.canonicalFile == staged(app, s).canonicalFile) { "Only a verified downloaded update can be installed." }
                }
                session = s.copy(installRequested = true); persist(app, session)
                resumeInstall(app)
            } catch (e: CancellationException) { throw e } catch (e: Exception) { fail(app, e.message ?: "Could not launch installation.") }
            finally { installing = false }
        } }
    }
    private suspend fun resumeInstall(app: Context, fromResume: Boolean = false) {
        var s = session ?: return
        if (!foreground || !s.ready || !s.installRequested) return
        if (s.waitingPermission && !fromResume) return
        val file = staged(app, s)
        val message = try { withContext(Dispatchers.IO) { ApkVerifier.verify(app, file, s.info) } }
            catch (_: ObsoleteApk) { clear(app); _updateStatus.value = UpdateStatus.UpToDate(BuildConfig.VERSION_NAME); return }
        if (!foreground) return
        if (Build.VERSION.SDK_INT >= 26 && !app.packageManager.canRequestPackageInstalls()) {
            if (s.waitingPermission) {
                s = s.copy(installRequested = false, waitingPermission = false); session = s; persist(app, s)
                _updateStatus.value = UpdateStatus.ReadyToInstall(file, s.info, "Installation permission was not granted. Tap to allow it.")
                return
            }
            s = s.copy(waitingPermission = true); session = s; persist(app, s)
            _updateStatus.value = UpdateStatus.ReadyToInstall(file, s.info, "Allow app installations, then return to continue.")
            app.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        // Clear the request before opening Android so cancel/back cannot produce an install loop.
        s = s.copy(installRequested = false, waitingPermission = false); session = s; persist(app, s)
        _updateStatus.value = UpdateStatus.ReadyToInstall(file, s.info, message)
        val uri = withContext(Dispatchers.IO) { FileProvider.getUriForFile(app, "${app.packageName}.provider", file) }
        app.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("App update", uri) })
    }
}
