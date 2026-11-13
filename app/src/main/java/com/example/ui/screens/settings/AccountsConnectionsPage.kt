package com.example.ui.screens.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.connections.*
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.*

@Composable
fun AccountsConnectionsPage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { ConnectionStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var lastFmDialog by remember { mutableStateOf(false) }
    var library by remember { mutableStateOf<String?>(null) }
    fun action(block: suspend () -> Unit) { scope.launch {
        try { block(); error = null } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "The connection could not be updated. Please try again." }
    } }
    fun signIn(provider: String, manage: Boolean = false) {
        context.startActivity(Intent(context, ConnectionLoginActivity::class.java).putExtra("provider", provider).putExtra("manage", manage))
    }
    LaunchedEffect(store) {
        try { store.load(); ready = true }
        catch (_: Exception) { error = "Secure account storage is unavailable. Unlock your device and reopen this page." }
    }
    if (lastFmDialog) LastFmConnectDialog(state.lastFm, onDismiss = { lastFmDialog = false })
    library?.let { provider ->
        ConnectedLibraryPage(provider, onBack = { library = null }, modifier)
        BackHandler { library = null }
        return
    }
    LazyColumn(modifier.fillMaxSize().testTag("accounts_connections_page"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 160.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Connect your music", color = colors.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Bring your library together and keep your listening history in sync. Connections don’t change your audio source.",
                color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp)) }
        error?.let { message -> item { Text(message, color = colors.primaryAccent) } }
        item { ConnectionCard("YouTube Music", "Your playlists, saved albums and liked songs") {
            if (state.youtube.isEmpty()) Text("Not connected", color = colors.textSecondary)
            state.youtube.forEach { account ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Row(Modifier.weight(1f)) {
                        RadioButton(account.id == state.selectedYouTube, { action { store.change { it.copy(selectedYouTube = account.id) } } })
                        Text(account.name, color = colors.textPrimary, modifier = Modifier.padding(top = 13.dp))
                    }
                    TextButton(onClick = { action {
                        store.change { current -> val remaining = current.youtube.filterNot { it.id == account.id }
                            current.copy(youtube = remaining, selectedYouTube = if (current.selectedYouTube == account.id) remaining.firstOrNull()?.id.orEmpty() else current.selectedYouTube) }
                        clearConnectionCookies("youtube")
                    } }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = ready, onClick = { signIn("youtube") }, modifier = Modifier.testTag("connect_youtube")) {
                    Text(if (state.youtube.isEmpty()) "Connect" else "Add account") }
                if (state.activeYouTube != null) {
                    TextButton(onClick = { signIn("youtube", manage = true) }) { Text("Choose profile") }
                    TextButton(onClick = { library = "youtube" }) { Text("Library") }
                }
            }
        } }
        item { ConnectionCard("Last.fm", "Scrobble the music you listen to") {
            val fm = state.lastFm
            Text(if (fm.session.isNotBlank()) "Connected as ${fm.username}" else if (fm.username.isNotBlank()) "Session expired · reconnect ${fm.username}" else "Not connected",
                color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = ready, onClick = { lastFmDialog = true }, modifier = Modifier.testTag("connect_lastfm")) {
                    Text(if (fm.session.isBlank()) "Connect" else "Account settings") }
                if (fm.session.isNotBlank() || fm.username.isNotBlank()) TextButton(onClick = { action {
                    store.change { it.copy(lastFm = LastFmConnection()) }; ScrobbleOutbox.get(context).clear()
                } }) { Text("Disconnect") }
            }
            if (fm.session.isNotBlank()) {
                ConnectionToggle("Scrobbling", "Send qualified plays to your Last.fm profile", fm.enabled) { value ->
                    action { store.change { it.copy(lastFm = it.lastFm.copy(enabled = value)) }; if (value) ScrobbleOutbox.get(context).schedule() } }
                ConnectionToggle("Now playing", "Show the track you’re currently listening to", fm.nowPlaying, fm.enabled) { value ->
                    action { store.change { it.copy(lastFm = it.lastFm.copy(nowPlaying = value)) } } }
                ConnectionToggle("Primary artist only", "Use the first artist when a track has multiple credits", fm.primaryArtistOnly, fm.enabled) { value ->
                    action { store.change { it.copy(lastFm = it.lastFm.copy(primaryArtistOnly = value)) } } }
                Text("Scrobble after ${fm.thresholdPercent}% or ${fm.thresholdSeconds / 60} minutes, whichever comes first. Tracks must be longer than ${fm.minDurationSeconds} seconds.",
                    color = colors.textMuted, style = MaterialTheme.typography.bodySmall)
                ChoiceSetting("Track progress", listOf(50, 75, 100), fm.thresholdPercent, { "$it%" }) { value ->
                    action { store.change { it.copy(lastFm = it.lastFm.copy(thresholdPercent = value)) } } }
                ChoiceSetting("Maximum listening time", listOf(120, 180, 240), fm.thresholdSeconds, { "${it / 60} min" }) { value ->
                    action { store.change { it.copy(lastFm = it.lastFm.copy(thresholdSeconds = value)) } } }
                ChoiceSetting("Minimum track length", listOf(30, 60, 90), fm.minDurationSeconds, { "$it sec" }) { value ->
                    action { store.change { it.copy(lastFm = it.lastFm.copy(minDurationSeconds = value)) } } }
            }
        } }
        item { ConnectionCard("Spotify", "Import playlists and liked songs into your library") {
            Text(if (state.spotify == null) "Not connected" else "Connected", color = colors.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = ready, onClick = { signIn("spotify") }, modifier = Modifier.testTag("connect_spotify")) {
                    Text(if (state.spotify == null) "Connect" else "Reconnect") }
                if (state.spotify != null) {
                    TextButton(onClick = { library = "spotify" }) { Text("Library") }
                    TextButton(onClick = { action { store.change { it.copy(spotify = null) }; clearConnectionCookies("spotify") } }) { Text("Disconnect") }
                }
            }
        } }
    }
}

@Composable
private fun ConnectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAppColors.current
    Surface(shape = RoundedCornerShape(20.dp), color = colors.cardBackground, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, color = colors.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(subtitle, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
            HorizontalDivider(Modifier.padding(vertical = 6.dp), color = colors.cardBorder)
            content()
        }
    }
}
@Composable
private fun ConnectionToggle(title: String, subtitle: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, color = colors.textPrimary)
            Text(subtitle, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall) }
        Switch(value, onChange, enabled = enabled)
    }
}
@Composable
private fun ChoiceSetting(title: String, choices: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    Column {
        Text(title, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { choices.forEach { choice ->
            FilterChip(selected == choice, { onSelect(choice) }, label = { Text(label(choice)) })
        } }
    }
}

@Composable
private fun LastFmConnectDialog(initial: LastFmConnection, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ConnectionStore.get(context) }
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(initial.apiKey) }
    var secret by remember { mutableStateOf(initial.secret) }
    var token by remember { mutableStateOf(initial.pendingToken) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun browser(url: String) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Connect Last.fm") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Use a Last.fm API key and shared secret from your API account. You’ll authorize access on Last.fm’s website.")
            OutlinedTextField(apiKey, { apiKey = it.trim(); token = "" }, label = { Text("API key") }, singleLine = true, enabled = !busy)
            OutlinedTextField(secret, { secret = it.trim(); token = "" }, label = { Text("Shared secret") }, singleLine = true,
                enabled = !busy, visualTransformation = PasswordVisualTransformation())
            TextButton(onClick = { browser("https://www.last.fm/api/account/create") }) { Text("Get an API key") }
            if (token.isNotBlank()) Text("Approve the request in your browser, return here and complete the connection.")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {
        TextButton(enabled = !busy && apiKey.isNotBlank() && secret.isNotBlank(), onClick = { scope.launch {
            busy = true; error = null
            try {
                val credentials = initial.copy(apiKey = apiKey, secret = secret)
                if (token.isBlank()) {
                    token = LastFmClient.call(credentials, "auth.getToken").getString("token")
                    store.change { it.copy(lastFm = credentials.copy(session = "", pendingToken = token)) }
                    browser(Uri.parse("https://www.last.fm/api/auth/").buildUpon().appendQueryParameter("api_key", apiKey)
                        .appendQueryParameter("token", token).build().toString())
                } else {
                    val session = LastFmClient.call(credentials, "auth.getSession", mapOf("token" to token)).getJSONObject("session")
                    store.change { it.copy(lastFm = credentials.copy(session = session.getString("key"), username = session.getString("name"), pendingToken = "")) }
                    ScrobbleOutbox.get(context).schedule(); onDismiss()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failed: LastFmException) {
                if (failed.code in setOf(4, 15)) {
                    token = ""
                    try { store.change { it.copy(lastFm = it.lastFm.copy(pendingToken = "")) } }
                    catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
                }
                error = when (failed.code) {
                14 -> "Approve access on Last.fm first, then try again."
                4, 15 -> "This authorization expired. Start again."
                10, 13 -> "Check your API key and shared secret."
                else -> "Last.fm could not complete the connection. Please retry."
            } }
            catch (_: Exception) { error = "Could not reach Last.fm. Check your connection and retry." }
            finally { busy = false }
        } }) { Text(if (token.isBlank()) "Authorize" else "Complete connection") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") } })
}

@Composable
private fun ConnectedLibraryPage(provider: String, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val client = remember(context) { ConnectedLibraries(context) }
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var collections by remember(provider) { mutableStateOf<List<ConnectedCollection>>(emptyList()) }
    var loading by remember(provider) { mutableStateOf(true) }
    var error by remember(provider) { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf("") }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    LaunchedEffect(provider, reload) {
        loading = true; error = null
        try { collections = client.collections(provider) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "Could not load this library. Check your connection, or reconnect the account." }
        finally { loading = false }
    }
    DisposableEffect(Unit) { onDispose { importJob?.cancel() } }
    LazyColumn(modifier.fillMaxSize().testTag("connected_library_page"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = { importJob?.cancel(); onBack() }) { Text("Back to accounts") }
            Text(if (provider == "youtube") "YouTube Music library" else "Spotify library", color = colors.textPrimary,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Import a collection into your local library. Import again to refresh it.", color = colors.textSecondary) }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, color = colors.primaryAccent)
            TextButton(onClick = { reload++ }) { Text("Retry") } } }
        if (progress.isNotBlank()) item { Text(progress, color = colors.textSecondary) }
        if (!loading && collections.isEmpty() && error == null) item { Text("No saved collections found.", color = colors.textSecondary) }
        items(collections, key = { it.id }) { collection ->
            Surface(shape = RoundedCornerShape(16.dp), color = colors.cardBackground) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(collection.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    TextButton(enabled = importing == null, onClick = {
                        importing = collection.id
                        importJob = scope.launch {
                            try {
                                val result = client.import(collection) { done, total -> progress = "Importing $done of $total tracks…" }
                                progress = "Imported ${result.imported} tracks${if (result.unmatched > 0) " · ${result.unmatched} could not be matched" else ""}."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { progress = "Import could not finish. Your existing library was kept. Please retry." }
                            finally { importing = null }
                        }
                    }) { Text(if (importing == collection.id) "Importing…" else "Import") }
                }
            }
        }
    }
}
