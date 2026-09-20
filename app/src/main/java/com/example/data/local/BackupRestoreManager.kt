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
import androidx.core.content.ContextCompat
import com.example.data.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            val playlists = dao.getAllPlaylistsSync()
            val allTracks = dao.getAllTracksSync()
            val favoriteTracks = dao.getAllFavoriteTracksSync()
            val crossRefs = dao.getAllPlaylistCrossRefsSync()
            val userProfile = UserProfilePreferences.getUserProfile(context)

            val rootJson = JSONObject()
            rootJson.put("app", "Xtreme Player")
            rootJson.put("version", "1.6.0")
            rootJson.put("schemaVersion", 2)
            rootJson.put("createdAt", System.currentTimeMillis())

            // User Profile
            val profileJson = JSONObject()
            profileJson.put("name", userProfile.name)
            profileJson.put("country", userProfile.country)
            profileJson.put("countryCode", userProfile.countryCode)
            profileJson.put("flag", userProfile.flag)
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

    /**
     * Restores backup from a Content URI (e.g. from user-selected storage file via SAF)
     */
    suspend fun restoreFromUri(
        context: Context,
        musicDatabase: MusicDatabase,
        uri: Uri
    ): RestoreSummary = withContext(Dispatchers.IO) {
        try {
            val content = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext RestoreSummary(
                    success = false,
                    message = "Could not read backup file from selected storage location."
                )
            restoreBackup(context, musicDatabase, rawJson = content)
        } catch (e: Exception) {
            RestoreSummary(
                success = false,
                message = "Failed to read backup: ${e.localizedMessage ?: "Invalid file"}"
            )
        }
    }

    /**
     * Restores backup from a JSON file, content Uri string, or raw string content
     */
    suspend fun restoreBackup(
        context: Context,
        musicDatabase: MusicDatabase,
        backupFile: File? = null,
        uriString: String? = null,
        rawJson: String? = null
    ): RestoreSummary = withContext(Dispatchers.IO) {
        try {
            val jsonString = rawJson
                ?: if (uriString != null && uriString.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(uriString))?.bufferedReader()?.use { it.readText() }
                } else null
                ?: backupFile?.let { if (it.exists()) it.readText() else null }
                ?: return@withContext RestoreSummary(
                    success = false,
                    message = "Backup file not found or empty."
                )

            val root = JSONObject(jsonString)
            val dao = musicDatabase.musicDao()

            var restoredPlaylists = 0
            var restoredTracks = 0
            var profileRestored = false

            // 1. Restore Profile if present
            if (root.has("userProfile")) {
                val profObj = root.getJSONObject("userProfile")
                val name = profObj.optString("name", "")
                val country = profObj.optString("country", "")
                val countryCode = profObj.optString("countryCode", "")
                val flag = profObj.optString("flag", "")
                val langs = mutableListOf<String>()
                if (profObj.has("languages")) {
                    val langsArr = profObj.getJSONArray("languages")
                    for (i in 0 until langsArr.length()) {
                        langs.add(langsArr.getString(i))
                    }
                }
                if (name.isNotBlank() || country.isNotBlank()) {
                    UserProfilePreferences.saveUserProfile(
                        context,
                        UserProfile(
                            name = name,
                            languages = if (langs.isNotEmpty()) langs else listOf("Hindi", "English"),
                            country = country,
                            countryCode = countryCode,
                            flag = flag
                        )
                    )
                    profileRestored = true
                }
            }

            // 2. Restore Playlists
            if (root.has("playlists")) {
                val playlistsArr = root.getJSONArray("playlists")
                val list = mutableListOf<PlaylistEntity>()
                for (i in 0 until playlistsArr.length()) {
                    val p = playlistsArr.getJSONObject(i)
                    list.add(
                        PlaylistEntity(
                            playlistId = p.optLong("playlistId", 0L),
                            title = p.optString("title", "Restored Playlist"),
                            description = p.optString("description", ""),
                            coverUrl = p.optString("coverUrl", ""),
                            createdAt = p.optLong("createdAt", System.currentTimeMillis())
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    dao.insertPlaylists(list)
                    restoredPlaylists = list.size
                }
            }

            // 3. Restore Library / Favorite Tracks
            val tracksArr = root.optJSONArray("libraryTracks")
                ?: root.optJSONArray("tracks")
                ?: root.optJSONArray("favoriteTracks")

            if (tracksArr != null) {
                val trackList = mutableListOf<TrackEntity>()
                for (i in 0 until tracksArr.length()) {
                    val t = tracksArr.getJSONObject(i)
                    trackList.add(
                        TrackEntity(
                            id = t.getString("id"),
                            title = t.optString("title", "Unknown Track"),
                            artist = t.optString("artist", "Unknown Artist"),
                            album = t.optString("album", ""),
                            durationMs = t.optLong("durationMs", 0L),
                            coverUrl = t.optString("coverUrl", ""),
                            audioUrl = t.optString("audioUrl", ""),
                            bitrateKbps = t.optInt("bitrateKbps", 320),
                            qualityBadge = t.optString("qualityBadge", "HD • 320 kbps"),
                            genre = t.optString("genre", "Pop"),
                            isLiked = t.optBoolean("isLiked", true),
                            isCached = t.optBoolean("isCached", false),
                            singers = t.optString("singers", ""),
                            writer = t.optString("writer", ""),
                            language = t.optString("language", "Hindi"),
                            year = t.optString("year", ""),
                            source = t.optString("source", "HD Stream"),
                            addedAt = t.optLong("addedAt", System.currentTimeMillis())
                        )
                    )
                }
                if (trackList.isNotEmpty()) {
                    dao.insertOrUpdateTracks(trackList)
                    restoredTracks = trackList.size
                }
            }

            // 4. Restore Cross Refs
            if (root.has("playlistTrackRefs")) {
                val refsArr = root.getJSONArray("playlistTrackRefs")
                val refList = mutableListOf<PlaylistTrackCrossRef>()
                for (i in 0 until refsArr.length()) {
                    val r = refsArr.getJSONObject(i)
                    refList.add(
                        PlaylistTrackCrossRef(
                            playlistId = r.getLong("playlistId"),
                            trackId = r.getString("trackId")
                        )
                    )
                }
                if (refList.isNotEmpty()) {
                    dao.insertPlaylistTrackRefs(refList)
                }
            }

            RestoreSummary(
                success = true,
                message = "Backup successfully restored! Recovered $restoredPlaylists playlists and $restoredTracks tracks into your library.",
                restoredPlaylistsCount = restoredPlaylists,
                restoredTracksCount = restoredTracks,
                profileRestored = profileRestored
            )
        } catch (e: Exception) {
            RestoreSummary(
                success = false,
                message = "Failed to restore backup: ${e.localizedMessage ?: "Invalid file format"}"
            )
        }
    }
}
