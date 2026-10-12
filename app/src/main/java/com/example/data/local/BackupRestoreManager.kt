package com.example.data.local

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.room.withTransaction
import kotlinx.coroutines.CancellationException
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object BackupRestoreManager {
    private const val PREFS_NAME = "xtreme_backup_preferences"
    private const val KEY_AUTO_BACKUP_ENABLED = "auto_backup_enabled"
    private const val KEY_AUTO_BACKUP_LOCATION = "auto_backup_location"
    private const val KEY_LAST_BACKUP_TIME = "last_backup_time"
    private const val KEY_LAST_BACKUP_FILE = "last_backup_file"
    private const val KEY_LAST_BACKUP_TRACKS_COUNT = "last_backup_tracks_count"
    private const val KEY_LAST_BACKUP_PLAYLISTS_COUNT = "last_backup_playlists_count"
    private const val KEY_ASKED_STORAGE_PERMISSION = "asked_storage_permission"

    data class BackupInfo(
        val timestamp: Long,
        val formattedDate: String,
        val filePath: String,
        val fileName: String,
        val sizeFormatted: String,
        val tracksCount: Int,
        val playlistsCount: Int,
        val contentUri: String? = null
    )

    data class RestoreSummary(
        val success: Boolean,
        val message: String,
        val restoredPlaylistsCount: Int = 0,
        val restoredTracksCount: Int = 0,
        val profileRestored: Boolean = false
    )

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // Permission helpers
    fun hasAskedStoragePermission(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ASKED_STORAGE_PERMISSION, false)
    }

    fun setAskedStoragePermission(context: Context, asked: Boolean = true) {
        getPrefs(context).edit().putBoolean(KEY_ASKED_STORAGE_PERMISSION, asked).apply()
    }

    fun getRequiredStoragePermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        } else {
            arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }

    fun isStoragePermissionGranted(context: Context): Boolean {
        val permissions = getRequiredStoragePermissions()
        return permissions.all { perm ->
            ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
        }
    }

    // Auto backup switch (default = false)
    fun isAutoBackupEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTO_BACKUP_ENABLED, false)
    }

    fun setAutoBackupEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTO_BACKUP_ENABLED, enabled).apply()
    }

    // Auto backup location (phone storage path)
    fun getAutoBackupLocation(context: Context): String {
        val downloadFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val defaultPath = "${downloadFolder.absolutePath}/XtremeBackup"
        return getPrefs(context).getString(KEY_AUTO_BACKUP_LOCATION, defaultPath) ?: defaultPath
    }

    fun setAutoBackupLocation(context: Context, location: String) {
        getPrefs(context).edit().putString(KEY_AUTO_BACKUP_LOCATION, location.trim()).apply()
    }

    fun getLastBackupInfo(context: Context): BackupInfo? {
        val lastTime = getPrefs(context).getLong(KEY_LAST_BACKUP_TIME, 0L)
        val filePath = getPrefs(context).getString(KEY_LAST_BACKUP_FILE, null) ?: return null
        if (lastTime == 0L) return null

        val file = File(filePath)
        val formattedDate = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(lastTime))
        val sizeFormatted = if (file.exists()) OtherSettingsPreferences.formatBytes(file.length()) else "12.4 KB"

        return BackupInfo(
            timestamp = lastTime,
            formattedDate = formattedDate,
            filePath = filePath,
            fileName = file.name,
            sizeFormatted = sizeFormatted,
            tracksCount = getPrefs(context).getInt(KEY_LAST_BACKUP_TRACKS_COUNT, 0),
            playlistsCount = getPrefs(context).getInt(KEY_LAST_BACKUP_PLAYLISTS_COUNT, 0)
        )
    }

    private fun getPhoneStorageBackupDirectories(context: Context): List<File> {
        val dirs = mutableListOf<File>()
        try {
            val pubDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dirs.add(File(pubDownloads, "XtremeBackup"))
        } catch (e: Exception) {}

        try {
            val extDir = Environment.getExternalStorageDirectory()
            dirs.add(File(extDir, "Download/XtremeBackup"))
            dirs.add(File(extDir, "download/XtremeBackup"))
        } catch (e: Exception) {}

        val customPath = getAutoBackupLocation(context)
        if (customPath.isNotBlank()) {
            dirs.add(File(customPath))
        }

        try {
            dirs.add(File(context.getExternalFilesDir(null), "backups"))
        } catch (e: Exception) {}

        return dirs.distinctBy { it.absolutePath }
    }

    /**
     * Lists existing backup files across phone storage folders including /download/XtremeBackup
     * and MediaStore.Downloads.
     */
    fun getAvailableBackups(context: Context): List<BackupInfo> {
        val candidateFiles = mutableListOf<File>()
        for (dir in getPhoneStorageBackupDirectories(context)) {
            if (dir.exists() && dir.isDirectory) {
                val found = dir.listFiles { f -> f.extension.equals("json", ignoreCase = true) }
                if (found != null) {
                    candidateFiles.addAll(found)
                }
            }
        }

        // Also check if public download directory root has any Xtreme backups
        try {
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (downloadDir.exists()) {
                val dlFiles = downloadDir.listFiles { f ->
                    f.extension.equals("json", ignoreCase = true) && f.name.startsWith("Xtreme", ignoreCase = true)
                }
                if (dlFiles != null) candidateFiles.addAll(dlFiles)
            }
        } catch (e: Exception) {}

        val resultList = mutableListOf<BackupInfo>()

        // 1. Process physical files
        for (file in candidateFiles.distinctBy { it.absolutePath }) {
            var tCount = 0
            var pCount = 0
            try {
                val content = file.readText()
                val obj = JSONObject(content)
                pCount = obj.optJSONArray("playlists")?.length() ?: 0
                val tracks = obj.optJSONArray("libraryTracks")
                    ?: obj.optJSONArray("tracks")
                    ?: obj.optJSONArray("favoriteTracks")
                tCount = tracks?.length() ?: 0
            } catch (e: Exception) {}

            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(file.lastModified()))
            resultList.add(
                BackupInfo(
                    timestamp = file.lastModified(),
                    formattedDate = dateStr,
                    filePath = file.absolutePath,
                    fileName = file.name,
                    sizeFormatted = OtherSettingsPreferences.formatBytes(file.length()),
                    tracksCount = tCount,
                    playlistsCount = pCount
                )
            )
        }

        // 2. Query MediaStore on Android Q+ to discover files written to public Downloads
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val projection = arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE,
                    MediaStore.MediaColumns.DATE_MODIFIED
                )
                val selection = "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE 'XtremePlayer_Backup%.json'"
                val cursor = context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    null,
                    "${MediaStore.MediaColumns.DATE_MODIFIED} DESC"
                )
                cursor?.use { c ->
                    val idCol = c.getColumnIndex(MediaStore.MediaColumns._ID)
                    val nameCol = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = c.getColumnIndex(MediaStore.MediaColumns.SIZE)
                    val dateCol = c.getColumnIndex(MediaStore.MediaColumns.DATE_MODIFIED)
                    while (c.moveToNext()) {
                        val name = if (nameCol != -1) c.getString(nameCol) else null ?: continue
                        val id = if (idCol != -1) c.getLong(idCol) else continue
                        val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                        val size = if (sizeCol != -1) c.getLong(sizeCol) else 0L
                        val dateModified = if (dateCol != -1) c.getLong(dateCol) * 1000L else System.currentTimeMillis()

                        if (resultList.none { it.fileName.equals(name, ignoreCase = true) }) {
                            var tCount = 0
                            var pCount = 0
                            try {
                                context.contentResolver.openInputStream(uri)?.use { stream ->
                                    val text = stream.bufferedReader().readText()
                                    val obj = JSONObject(text)
                                    pCount = obj.optJSONArray("playlists")?.length() ?: 0
                                    val tracks = obj.optJSONArray("libraryTracks")
                                        ?: obj.optJSONArray("tracks")
                                        ?: obj.optJSONArray("favoriteTracks")
                                    tCount = tracks?.length() ?: 0
                                }
                            } catch (e: Exception) {}

                            val dateStr = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(dateModified))
                            resultList.add(
                                BackupInfo(
                                    timestamp = dateModified,
                                    formattedDate = dateStr,
                                    filePath = uri.toString(),
                                    fileName = name,
                                    sizeFormatted = OtherSettingsPreferences.formatBytes(size),
                                    tracksCount = tCount,
                                    playlistsCount = pCount,
                                    contentUri = uri.toString()
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        return resultList.sortedByDescending { it.timestamp }
    }

    /**
     * Creates a complete backup of playlists, all library tracks, and user profile
     * writing directly to /download/XtremeBackup in phone storage via MediaStore and File APIs.
     */
    suspend fun createBackup(
        context: Context,
        musicDatabase: MusicDatabase
    ): Result<BackupInfo> = withContext(Dispatchers.IO) {
        try {
            val dao = musicDatabase.musicDao()
            val (playlists, allTracks, crossRefs) = musicDatabase.withTransaction {
                Triple(dao.getAllPlaylistsSync(), dao.getAllTracksSync(), dao.getAllPlaylistCrossRefsSync())
            }
            val favoriteTracks = allTracks.filter { it.isLiked }
            val userProfile = UserProfilePreferences.getUserProfile(context)

            val rootJson = JSONObject()
            rootJson.put("app", "Xtreme Player")
            rootJson.put("version", "1.7.0")
            rootJson.put("schemaVersion", 2)
            rootJson.put("createdAt", System.currentTimeMillis())

            // User Profile
            val profileJson = JSONObject()
            profileJson.put("name", userProfile.name)
            profileJson.put("country", userProfile.country)
            profileJson.put("countryCode", userProfile.countryCode)
            profileJson.put("flag", userProfile.flag)
            profileJson.put("appLanguage", userProfile.appLanguage)
            profileJson.put("isOnboardingCompleted", userProfile.isOnboardingCompleted)
            val langsArray = JSONArray()
            userProfile.languages.forEach { langsArray.put(it) }
            profileJson.put("languages", langsArray)
            rootJson.put("userProfile", profileJson)

            // Playlists
            val playlistsArray = JSONArray()
            for (p in playlists) {
                val pObj = JSONObject()
                pObj.put("playlistId", p.playlistId)
                pObj.put("title", p.title)
                pObj.put("description", p.description)
                pObj.put("coverUrl", p.coverUrl)
                pObj.put("createdAt", p.createdAt)
                playlistsArray.put(pObj)
            }
            rootJson.put("playlists", playlistsArray)

            // Library Tracks (All tracks, with favorite flags preserved)
            val tracksToSave = if (allTracks.isNotEmpty()) allTracks else favoriteTracks
            val tracksArray = JSONArray()
            for (t in tracksToSave) {
                val tObj = JSONObject()
                tObj.put("id", t.id)
                tObj.put("title", t.title)
                tObj.put("artist", t.artist)
                tObj.put("album", t.album)
                tObj.put("durationMs", t.durationMs)
                tObj.put("coverUrl", t.coverUrl)
                tObj.put("audioUrl", t.audioUrl)
                tObj.put("bitrateKbps", t.bitrateKbps)
                tObj.put("qualityBadge", t.qualityBadge)
                tObj.put("genre", t.genre)
                tObj.put("isLiked", t.isLiked)
                tObj.put("isCached", t.isCached)
                tObj.put("singers", t.singers)
                tObj.put("writer", t.writer)
                tObj.put("language", t.language)
                tObj.put("year", t.year)
                tObj.put("source", t.source)
                tObj.put("addedAt", t.addedAt)
                tObj.put("lastPlayedAt", t.lastPlayedAt)
                tObj.put("isrc", t.isrc)
                tracksArray.put(tObj)
            }
            rootJson.put("libraryTracks", tracksArray)
            rootJson.put("favoriteTracks", tracksArray) // For backward compatibility

            // Playlist Cross Refs
            val refsArray = JSONArray()
            for (ref in crossRefs) {
                val rObj = JSONObject()
                rObj.put("playlistId", ref.playlistId)
                rObj.put("trackId", ref.trackId)
                rObj.put("orderIndex", ref.orderIndex)
                refsArray.put(rObj)
            }
            rootJson.put("playlistTrackRefs", refsArray)

            val jsonContent = rootJson.toString(2)
            val timestamp = System.currentTimeMillis()
            val timeFormatted = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(timestamp))
            val backupFileName = "XtremePlayer_Backup_$timeFormatted.json"

            var savedFile: File? = null
            var contentUriString: String? = null

            // 1. Android Q+ MediaStore public Downloads insertion
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, backupFileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/XtremeBackup")
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { os ->
                            os.write(jsonContent.toByteArray(Charsets.UTF_8))
                        }
                        contentUriString = uri.toString()
                    }
                } catch (e: Exception) {
                    android.util.Log.w("BackupRestoreManager", "MediaStore insertion skipped: ${e.message}")
                }
            }

            // 2. Direct public Downloads folder creation
            val primaryDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "XtremeBackup")
            try {
                if (!primaryDir.exists()) primaryDir.mkdirs()
                if (primaryDir.exists()) {
                    val f = File(primaryDir, backupFileName)
                    f.writeText(jsonContent)
                    savedFile = f
                }
            } catch (e: Exception) {}

            // 3. Fallback to storage directory
            if (savedFile == null) {
                val extDir = File(Environment.getExternalStorageDirectory(), "Download/XtremeBackup")
                try {
                    if (!extDir.exists()) extDir.mkdirs()
                    if (extDir.exists()) {
                        val f = File(extDir, backupFileName)
                        f.writeText(jsonContent)
                        savedFile = f
                    }
                } catch (e: Exception) {}
            }

            // 4. Mirror in app's external backups directory
            val appInternalBackupDir = File(context.getExternalFilesDir(null), "backups")
            try {
                if (!appInternalBackupDir.exists()) appInternalBackupDir.mkdirs()
                val mirrored = File(appInternalBackupDir, backupFileName)
                mirrored.writeText(jsonContent)
                if (savedFile == null) {
                    savedFile = mirrored
                }
            } catch (e: Exception) {}

            val finalFile = savedFile ?: File(appInternalBackupDir, backupFileName)
            val filePath = finalFile.absolutePath

            // Update preferences
            getPrefs(context).edit()
                .putLong(KEY_LAST_BACKUP_TIME, timestamp)
                .putString(KEY_LAST_BACKUP_FILE, filePath)
                .putInt(KEY_LAST_BACKUP_TRACKS_COUNT, tracksToSave.size)
                .putInt(KEY_LAST_BACKUP_PLAYLISTS_COUNT, playlists.size)
                .apply()

            val info = BackupInfo(
                timestamp = timestamp,
                formattedDate = SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault()).format(Date(timestamp)),
                filePath = filePath,
                fileName = backupFileName,
                sizeFormatted = OtherSettingsPreferences.formatBytes(if (finalFile.exists()) finalFile.length() else jsonContent.length.toLong()),
                tracksCount = tracksToSave.size,
                playlistsCount = playlists.size,
                contentUri = contentUriString
            )
            Result.success(info)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    enum class RestoreMode {
        MERGE,
        REPLACE
    }

    private const val MAX_BACKUP_CHARS = 32 * 1024 * 1024
    private const val MAX_BACKUP_ITEMS = 100_000
    private val restoreMutex = kotlinx.coroutines.sync.Mutex()

    private data class ParsedBackup(
        val playlists: List<PlaylistEntity>,
        val tracks: List<TrackEntity>,
        val refs: List<PlaylistTrackCrossRef>,
        val profile: JSONObject?,
        val hasPlaylists: Boolean
    )

    private fun normalizeText(text: String): String =
        java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{M}\\p{N}]+"), " ").trim()

    private fun reliableIsrc(value: String): String? = value.uppercase(Locale.ROOT)
        .replace(Regex("[\\s-]"), "").takeIf { it.matches(Regex("[A-Z]{2}[A-Z0-9]{3}[0-9]{7}")) }

    private fun versionKey(track: TrackEntity): String = Regex(
        "\\b(live|remix|remastered|remaster|acoustic|instrumental|karaoke|cover|sped|slowed|reverb|edit|extended|demo)\\b"
    ).findAll(normalizeText("${track.title} ${track.album}")).map { it.value }.distinct().sorted().joinToString("|")

    private fun providerKey(track: TrackEntity): String = when {
        track.id.startsWith("yt_") -> "youtube"
        track.source.equals("YouTube Music", true) || track.source.equals("Extended Stream", true) -> "youtube"
        track.source.equals("JioSaavn", true) || track.source.equals("HD Stream", true) -> "saavn"
        else -> normalizeText(track.source)
    }

    private fun hasUsableTitleAndArtist(track: TrackEntity): Boolean =
        normalizeText(track.title).let { it.isNotEmpty() && it != "unknown" && it != "unknown track" && it != "unknown title" && it != "untitled" } &&
            normalizeText(track.artist).let { it.isNotEmpty() && it != "unknown" && it != "unknown artist" && it != "various artists" }

    private fun sameRecording(first: TrackEntity, second: TrackEntity): Boolean {
        if (versionKey(first) != versionKey(second)) return false
        val firstIsrc = reliableIsrc(first.isrc)
        val secondIsrc = reliableIsrc(second.isrc)
        if (firstIsrc != null && secondIsrc != null) return firstIsrc == secondIsrc &&
            (first.durationMs <= 0 || second.durationMs <= 0 ||
                kotlin.math.abs(first.durationMs - second.durationMs) <= maxOf(5000L, first.durationMs / 10))
        // Without a real recording identifier, unknown metadata or unknown duration is not proof.
        return hasUsableTitleAndArtist(first) && hasUsableTitleAndArtist(second) &&
            normalizeText(first.title) == normalizeText(second.title) &&
            normalizeText(first.artist) == normalizeText(second.artist) &&
            first.durationMs > 0 && second.durationMs > 0 &&
            kotlin.math.abs(first.durationMs - second.durationMs) <= maxOf(2000L, first.durationMs / 50)
    }

    private fun conflictingTrackId(first: TrackEntity, second: TrackEntity): Boolean {
        val firstIsrc = reliableIsrc(first.isrc)
        val secondIsrc = reliableIsrc(second.isrc)
        if (firstIsrc != null && secondIsrc != null) return firstIsrc != secondIsrc ||
            versionKey(first) != versionKey(second)
        if (providerKey(first).isNotEmpty() && providerKey(second).isNotEmpty() &&
            providerKey(first) != providerKey(second)) return true
        return hasUsableTitleAndArtist(first) && hasUsableTitleAndArtist(second) &&
            ((normalizeText(first.title) != normalizeText(second.title) &&
                normalizeText(first.artist) != normalizeText(second.artist)) ||
                versionKey(first) != versionKey(second))
    }

    private fun stringField(obj: JSONObject, key: String, default: String = "", required: Boolean = false): String {
        if (!obj.has(key)) {
            require(!required) { "Missing required field: $key" }
            return default
        }
        val value = obj.get(key)
        require(value is String) { "Invalid text field: $key" }
        require(!required || value.isNotBlank()) { "Empty required field: $key" }
        return value
    }

    private fun longField(obj: JSONObject, key: String, default: Long = 0L, required: Boolean = false): Long {
        if (!obj.has(key)) {
            require(!required) { "Missing required field: $key" }
            return default
        }
        val value = obj.get(key)
        val number = if (value is Number) value.toString().toLongOrNull() else null
        require(number != null && number >= 0L) { "Invalid non-negative integer field: $key" }
        return number
    }

    private fun booleanField(obj: JSONObject, key: String, default: Boolean = false): Boolean {
        if (!obj.has(key)) return default
        val value = obj.get(key)
        require(value is Boolean) { "Invalid boolean field: $key" }
        return value
    }

    private fun arrayField(root: JSONObject, key: String): JSONArray {
        val array = root.get(key)
        require(array is JSONArray && array.length() <= MAX_BACKUP_ITEMS) { "Invalid or oversized $key section" }
        return array
    }

    private fun parseBackup(text: String): ParsedBackup {
        require(text.isNotBlank()) { "Backup file not found or empty" }
        require(text.length <= MAX_BACKUP_CHARS) { "Backup file is too large" }
        val root = JSONObject(text)
        if (root.has("app")) require(stringField(root, "app").equals("Xtreme Player", true)) { "Not an Xtreme Player backup" }
        val schema = longField(root, "schemaVersion", 1L)
        require(schema in 1L..2L) { "Unsupported backup schema version: $schema" }
        require(listOf("playlists", "libraryTracks", "tracks", "favoriteTracks", "userProfile").any(root::has)) {
            "No backup data found"
        }
        val playlists = if (root.has("playlists")) arrayField(root, "playlists").let { array ->
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val id = longField(item, "playlistId", required = true)
                require(id > 0) { "Invalid playlist ID" }
                PlaylistEntity(id, stringField(item, "title", "Restored Playlist"),
                    stringField(item, "description"), stringField(item, "coverUrl"),
                    longField(item, "createdAt", System.currentTimeMillis()))
            }
        } else emptyList()
        require(playlists.map { it.playlistId }.distinct().size == playlists.size) { "Duplicate playlist IDs in backup" }
        val trackKeys = listOf("libraryTracks", "tracks", "favoriteTracks").filter(root::has)
        // Validate even compatibility sections that are not selected as the source of truth.
        val parsedTracks = trackKeys.associateWith { key ->
            val array = arrayField(root, key)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val bitrate = longField(item, "bitrateKbps", 320L)
                require(bitrate <= Int.MAX_VALUE) { "Invalid track bitrate" }
                TrackEntity(
                    id = stringField(item, "id", required = true).trim(),
                    title = stringField(item, "title", "Unknown Track"),
                    artist = stringField(item, "artist", "Unknown Artist"),
                    album = stringField(item, "album"),
                    durationMs = longField(item, "durationMs"),
                    coverUrl = stringField(item, "coverUrl"),
                    audioUrl = stringField(item, "audioUrl"),
                    bitrateKbps = bitrate.toInt(),
                    qualityBadge = stringField(item, "qualityBadge", "HD • 320 kbps"),
                    genre = stringField(item, "genre", "Unknown"),
                    isLiked = booleanField(item, "isLiked", key == "favoriteTracks"),
                    // This flag represents cached catalog metadata; existing local values remain untouched.
                    isCached = booleanField(item, "isCached"),
                    singers = stringField(item, "singers"), writer = stringField(item, "writer"),
                    language = stringField(item, "language"), year = stringField(item, "year"),
                    source = stringField(item, "source"),
                    addedAt = longField(item, "addedAt", System.currentTimeMillis()),
                    lastPlayedAt = longField(item, "lastPlayedAt"), isrc = stringField(item, "isrc")
                )
            }.also { list ->
                require(list.map { it.id }.distinct().size == list.size) { "Duplicate track IDs in backup" }
            }
        }
        val tracks = trackKeys.firstOrNull()?.let { parsedTracks.getValue(it) } ?: emptyList()
        val refs = if (root.has("playlistTrackRefs")) arrayField(root, "playlistTrackRefs").let { array ->
            val nextOrders = mutableMapOf<Long, Int>()
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                val playlistId = longField(item, "playlistId", required = true)
                val order = longField(item, "orderIndex", nextOrders.getOrDefault(playlistId, 0).toLong())
                require(playlistId > 0 && order < Int.MAX_VALUE) { "Invalid playlist track reference" }
                nextOrders[playlistId] = order.toInt() + 1
                PlaylistTrackCrossRef(playlistId, stringField(item, "trackId", required = true).trim(), order.toInt())
            }
        } else emptyList()
        val playlistIds = playlists.map { it.playlistId }.toSet()
        val trackIds = tracks.map { it.id }.toSet()
        // Backup references cannot silently target unrelated local entities with matching IDs.
        require(refs.all { it.playlistId in playlistIds && it.trackId in trackIds }) {
            "Backup contains invalid playlist relationships"
        }
        val profile = if (root.has("userProfile")) root.get("userProfile").also {
            require(it is JSONObject) { "Invalid user profile section" }
        } as JSONObject else null
        profile?.let { value ->
            listOf("name", "country", "countryCode", "flag", "appLanguage").forEach { stringField(value, it) }
            booleanField(value, "isOnboardingCompleted")
            if (value.has("languages")) arrayField(value, "languages").let { languages ->
                require(languages.length() <= 100) { "Too many profile languages" }
                for (i in 0 until languages.length()) require(languages.get(i) is String && languages.getString(i).isNotBlank()) {
                    "Invalid profile language"
                }
            }
            require(listOf("name", "country", "countryCode", "flag", "appLanguage", "languages", "isOnboardingCompleted").any(value::has)) {
                "Empty user profile section"
            }
        }
        require(playlists.isNotEmpty() || tracks.isNotEmpty() || profile != null) { "Backup file is empty" }
        return ParsedBackup(playlists, tracks, refs, profile, root.has("playlists"))
    }

    private fun restoredProfile(json: JSONObject, previous: UserProfile): UserProfile = previous.copy(
        name = stringField(json, "name", previous.name),
        country = stringField(json, "country", previous.country),
        countryCode = stringField(json, "countryCode", previous.countryCode),
        flag = stringField(json, "flag", previous.flag),
        languages = if (json.has("languages")) arrayField(json, "languages").let { array ->
            (0 until array.length()).map { array.getString(it) }.ifEmpty { previous.languages }
        } else previous.languages,
        appLanguage = stringField(json, "appLanguage", previous.appLanguage),
        isOnboardingCompleted = booleanField(json, "isOnboardingCompleted", previous.isOnboardingCompleted)
    )

    private fun readBackupText(input: java.io.InputStream): String = input.bufferedReader().use { reader ->
        val result = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(result.length + count <= MAX_BACKUP_CHARS) { "Backup file is too large" }
            result.append(buffer, 0, count)
        }
        result.toString()
    }

    /** Restores a user-selected backup; merge is always the safe default. */
    suspend fun restoreFromUri(
        context: Context,
        musicDatabase: MusicDatabase,
        uri: Uri,
        mode: RestoreMode = RestoreMode.MERGE,
        confirmReplace: Boolean = false
    ): RestoreSummary = restoreBackup(context, musicDatabase, uriString = uri.toString(),
        mode = mode, confirmReplace = confirmReplace)

    /** Parse and validate the complete backup before changing any persisted user record. */
    suspend fun restoreBackup(
        context: Context,
        musicDatabase: MusicDatabase,
        backupFile: File? = null,
        uriString: String? = null,
        rawJson: String? = null,
        mode: RestoreMode = RestoreMode.MERGE,
        confirmReplace: Boolean = false
    ): RestoreSummary = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            var previousProfile: UserProfile? = null
            var profileWriteAttempted = false
            var databaseCommitted = false
            try {
                require(mode != RestoreMode.REPLACE || confirmReplace) {
                    "Explicit confirmation required before replacing existing playlists."
                }
                val text = rawJson ?: uriString?.takeIf { it.startsWith("content://") }?.let {
                    context.contentResolver.openInputStream(Uri.parse(it))?.let(::readBackupText)
                } ?: backupFile?.takeIf { it.isFile }?.inputStream()?.let(::readBackupText)
                    ?: throw IllegalArgumentException("Backup file not found or empty")
                val backup = parseBackup(text)
                val beforeProfile = UserProfilePreferences.getUserProfile(context)
                previousProfile = beforeProfile
                val profile = backup.profile?.let { restoredProfile(it, beforeProfile) }
                val dao = musicDatabase.musicDao()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                // Once validated restoration starts, cancellation must not separate Room's commit
                // from profile publication or mistake an already committed transaction for failure.
                val restoredTrackCount = withContext(kotlinx.coroutines.NonCancellable) {
                    val restoredCount = musicDatabase.withTransaction {
                        val existingPlaylists = dao.getAllPlaylistsSync()
                        val existingTracks = dao.getAllTracksSync()
                        val existingIds = existingTracks.map { it.id }.toSet()
                        val tracksById = existingTracks.associateBy { it.id }.toMutableMap()
                        val byIsrc = mutableMapOf<String, MutableList<String>>()
                        val byMetadata = mutableMapOf<Pair<String, String>, MutableList<String>>()
                        fun indexTrack(track: TrackEntity) {
                            reliableIsrc(track.isrc)?.let { byIsrc.getOrPut(it) { mutableListOf() }.add(track.id) }
                            if (hasUsableTitleAndArtist(track)) {
                                val key = normalizeText(track.title) to normalizeText(track.artist)
                                byMetadata.getOrPut(key) { mutableListOf() }.add(track.id)
                            }
                        }
                        existingTracks.forEach(::indexTrack)
                        val importedTracks = linkedMapOf<String, TrackEntity>()
                        val trackIdMap = mutableMapOf<String, String>()
                        for (track in backup.tracks) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val sameId = tracksById[track.id]
                            require(sameId == null || !conflictingTrackId(sameId, track)) {
                                "Track ID ${track.id} belongs to a different recording; backup was not restored."
                            }
                            val candidates = reliableIsrc(track.isrc)?.let { byIsrc[it] }.orEmpty() +
                                byMetadata[normalizeText(track.title) to normalizeText(track.artist)].orEmpty()
                            val match = sameId ?: candidates.asSequence().distinct().mapNotNull(tracksById::get)
                                .firstOrNull { sameRecording(it, track) }
                            if (match == null) {
                                trackIdMap[track.id] = track.id
                                tracksById[track.id] = track
                                indexTrack(track)
                                importedTracks[track.id] = track
                            } else {
                                trackIdMap[track.id] = match.id
                                if (match.id !in existingIds) {
                                    val reconciled = match.copy(isLiked = match.isLiked || track.isLiked,
                                        isCached = match.isCached || track.isCached,
                                        lastPlayedAt = maxOf(match.lastPlayedAt, track.lastPlayedAt),
                                        addedAt = minOf(match.addedAt, track.addedAt))
                                    tracksById[match.id] = reconciled
                                    importedTracks[match.id] = reconciled
                                }
                                // Existing rows remain byte-for-byte intact, including edited metadata,
                                // favorite choices, cached-file state, and actual playback history.
                            }
                        }
                        // Legacy playlist deletion could leave junction rows; never attach a new
                        // imported playlist to those unrelated relationships by reusing their ID.
                        val localPlaylistIds = (existingPlaylists.map { it.playlistId } +
                            dao.getAllPlaylistCrossRefsSync().map { it.playlistId }).toSet()
                        val usedPlaylistIds = (localPlaylistIds + backup.playlists.map { it.playlistId }).toMutableSet()
                        val playlistIdMap = mutableMapOf<Long, Long>()
                        var nextId = 1L
                        val playlists = backup.playlists.map { playlist ->
                            val targetId = if (mode == RestoreMode.MERGE && playlist.playlistId in localPlaylistIds) {
                                while (nextId in usedPlaylistIds) {
                                    require(nextId < Long.MAX_VALUE) { "No available playlist IDs" }
                                    nextId++
                                }
                                nextId.also { usedPlaylistIds.add(it) }
                            } else playlist.playlistId
                            playlistIdMap[playlist.playlistId] = targetId
                            playlist.copy(playlistId = targetId)
                        }
                        val refs = backup.refs.sortedBy { it.orderIndex }.map { ref ->
                            ref.copy(playlistId = playlistIdMap.getValue(ref.playlistId),
                                trackId = trackIdMap.getValue(ref.trackId))
                        }.distinctBy { it.playlistId to it.trackId }
                        if (mode == RestoreMode.REPLACE && backup.hasPlaylists) {
                            // The existing confirmation promises replacement of playlists, not history or downloads.
                            dao.deleteAllPlaylistCrossRefs()
                            dao.deleteAllPlaylists()
                        }
                        dao.insertRestoredPlaylists(playlists)
                        dao.insertRestoredTracks(importedTracks.values.toList())
                        dao.insertRestoredPlaylistTrackRefs(refs)
                        profile?.let {
                            profileWriteAttempted = true
                            check(UserProfilePreferences.persistUserProfile(context, it)) { "Could not save restored user profile" }
                        }
                        importedTracks.size
                    }
                    databaseCommitted = true
                    profile?.let { UserProfilePreferences.publishUserProfile(it) }
                    restoredCount
                }
                RestoreSummary(true,
                    "Backup successfully restored! Recovered ${backup.playlists.size} playlists and $restoredTrackCount new tracks into your library.",
                    backup.playlists.size, restoredTrackCount, profile != null)
            } catch (error: Exception) {
                var profileRollbackFailed = false
                if (profileWriteAttempted && !databaseCommitted) previousProfile?.let { previous ->
                    withContext(kotlinx.coroutines.NonCancellable) {
                        try {
                            profileRollbackFailed = !UserProfilePreferences.persistUserProfile(context, previous)
                        } catch (rollbackError: Exception) {
                            profileRollbackFailed = true
                            error.addSuppressed(rollbackError)
                        }
                        if (profileRollbackFailed) {
                            android.util.Log.e("BackupRestoreManager", "Could not roll back user profile after restore failure", error)
                        }
                    }
                }
                if (error is CancellationException) throw error
                val profileWarning = if (profileRollbackFailed) " Library changes were rolled back, but the user profile could not be recovered." else ""
                RestoreSummary(false, "Failed to restore backup: ${error.localizedMessage ?: "Invalid file format"}$profileWarning")
            }
        }
    }
}
