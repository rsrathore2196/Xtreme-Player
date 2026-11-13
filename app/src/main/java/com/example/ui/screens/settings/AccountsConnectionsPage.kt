@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.example.ui.screens.settings

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
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
        catch (cancelled: CancellationException) { throw cancelled }
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
        item {
            Text("Connect your music", color = colors.textPrimary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Bring your library together and keep your listening history in sync. Connections don’t change your audio source.",
                color = colors.textSecondary, modifier = Modifier.padding(top = 6.dp))
        }
        error?.let { message -> item { Text(message, color = colors.primaryAccent) } }
        item { ConnectionCard(ConnectionService.YouTube, "Your playlists, saved albums and liked songs") {
            if (state.youtube.isEmpty()) Text("Not connected", color = colors.textSecondary)
            state.youtube.forEach { account ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(account.id == state.selectedYouTube,
                        { action { store.change { it.copy(selectedYouTube = account.id) } } },
                        colors = RadioButtonDefaults.colors(selectedColor = ConnectionService.YouTube.accent))
                    Text(account.name, color = colors.textPrimary, modifier = Modifier.weight(1f).padding(end = 8.dp))
                    ConnectionActionButton("Remove", onClick = { action {
                        store.change { current -> val remaining = current.youtube.filterNot { it.id == account.id }
                            current.copy(youtube = remaining, selectedYouTube = if (current.selectedYouTube == account.id) remaining.firstOrNull()?.id.orEmpty() else current.selectedYouTube) }
                        clearConnectionCookies("youtube")
                    } })
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionActionButton(if (state.youtube.isEmpty()) "Connect" else "Add account", { signIn("youtube") },
                    Modifier.testTag("connect_youtube"), enabled = ready, primary = true)
                if (state.activeYouTube != null) {
                    ConnectionActionButton("Choose profile", { signIn("youtube", manage = true) })
                    ConnectionActionButton("Library", { library = "youtube" })
                }
            }
        } }
        item { ConnectionCard(ConnectionService.LastFm, "Scrobble the music you listen to") {
            val fm = state.lastFm
            Text(if (fm.session.isNotBlank()) "Connected as ${fm.username}" else if (fm.username.isNotBlank()) "Session expired · reconnect ${fm.username}" else "Not connected",
                color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionActionButton(if (fm.session.isBlank()) "Connect" else "Account settings", { lastFmDialog = true },
                    Modifier.testTag("connect_lastfm"), enabled = ready, primary = true)
                if (fm.session.isNotBlank() || fm.username.isNotBlank()) ConnectionActionButton("Disconnect", { action {
                    store.change { it.copy(lastFm = LastFmConnection()) }; ScrobbleOutbox.get(context).clear()
                } })
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
        item { ConnectionCard(ConnectionService.Spotify, "Import playlists and liked songs into your library") {
            Text(if (state.spotify == null) "Not connected" else "Connected", color = colors.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ConnectionActionButton(if (state.spotify == null) "Connect" else "Reconnect", { signIn("spotify") },
                    Modifier.testTag("connect_spotify"), enabled = ready, primary = true)
                if (state.spotify != null) {
                    ConnectionActionButton("Library", { library = "spotify" })
                    ConnectionActionButton("Disconnect", { action { store.change { it.copy(spotify = null) }; clearConnectionCookies("spotify") } })
                }
            }
        } }
    }
}

@Composable
private fun ConnectionCard(service: ConnectionService, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalAppColors.current
    CompositionLocalProvider(LocalConnectionService provides service) {
        Surface(shape = RoundedCornerShape(24.dp), color = connectionSurface(service),
            border = BorderStroke(1.dp, service.accent.copy(alpha = .4f)),
            modifier = Modifier.fillMaxWidth().testTag("connection_card_${service.name.lowercase()}")) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Surface(shape = RoundedCornerShape(14.dp),
                        color = if (service == ConnectionService.Spotify) Color.Black else service.accent,
                        contentColor = if (service == ConnectionService.Spotify) service.accent else service.onButton) {
                        Icon(painterResource(service.icon), null, Modifier.padding(10.dp).size(28.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(service.title, color = colors.textPrimary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(subtitle, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider(color = service.accent.copy(alpha = .22f))
                content()
            }
        }
    }
}

@Composable
private fun ConnectionToggle(title: String, subtitle: String, value: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = LocalAppColors.current
    val service = LocalConnectionService.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = colors.textPrimary)
            Text(subtitle, color = colors.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange, enabled = enabled, colors = SwitchDefaults.colors(
            checkedTrackColor = service.button, checkedThumbColor = service.onButton))
    }
}

@Composable
private fun ChoiceSetting(title: String, choices: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    val service = LocalConnectionService.current
    Column {
        Text(title, color = LocalAppColors.current.textPrimary, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            choices.forEach { choice ->
                FilterChip(selected == choice, { onSelect(choice) }, label = { Text(label(choice)) },
                    border = BorderStroke(1.dp, service.accent.copy(alpha = .4f)),
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = service.accent.copy(alpha = .09f).compositeOver(connectionSurface(service)),
                        labelColor = LocalAppColors.current.textPrimary,
                        selectedContainerColor = service.button, selectedLabelColor = service.onButton))
            }
        }
    }
}

@Composable
internal fun LastFmConnectDialog(initial: LastFmConnection, onDismiss: () -> Unit,
    authenticate: suspend (String, String, LastFmConnection) -> LastFmConnection = { username, password, previous ->
        LastFmClient.signIn(username, password, previous)
    }) {
    val context = LocalContext.current
    val store = remember(context) { ConnectionStore.get(context) }
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { password = "" } }
    CompositionLocalProvider(LocalConnectionService provides ConnectionService.LastFm) {
        val service = LocalConnectionService.current
        val colors = LocalAppColors.current
        val fields = OutlinedTextFieldDefaults.colors(focusedTextColor = colors.textPrimary,
            unfocusedTextColor = colors.textPrimary, focusedBorderColor = service.accent,
            unfocusedBorderColor = service.accent.copy(alpha = .45f), focusedLabelColor = service.accent,
            unfocusedLabelColor = colors.textSecondary, cursorColor = service.accent)
        AlertDialog(onDismissRequest = { if (!busy) { password = ""; onDismiss() } },
            containerColor = connectionSurface(service), shape = RoundedCornerShape(24.dp),
            titleContentColor = colors.textPrimary, textContentColor = colors.textSecondary,
            icon = { Icon(painterResource(service.icon), null, Modifier.size(32.dp), tint = service.accent) },
            title = { Text("Sign in to Last.fm") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Sign in with your Last.fm account to sync your listening history. Your password is not saved.")
                    OutlinedTextField(username, { username = it; error = null }, label = { Text("Username") },
                        singleLine = true, enabled = !busy, colors = fields,
                        modifier = Modifier.fillMaxWidth().testTag("lastfm_username"))
                    OutlinedTextField(password, { password = it; error = null }, label = { Text("Password") },
                        singleLine = true, enabled = !busy, colors = fields, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        modifier = Modifier.fillMaxWidth().testTag("lastfm_password"))
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("lastfm_login_error")) }
                    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = service.accent)
                }
            }, confirmButton = {
                ConnectionActionButton(if (busy) "Signing in…" else "Sign in", modifier = Modifier.testTag("lastfm_sign_in"),
                    enabled = !busy && username.isNotBlank() && password.isNotBlank(), primary = true, onClick = { scope.launch {
                        busy = true; error = null
                        try {
                            val connected = authenticate(username.trim(), password, initial)
                            store.change { it.copy(lastFm = it.lastFm.copy(apiKey = connected.apiKey, secret = connected.secret,
                                session = connected.session, username = connected.username, pendingToken = "")) }
                            password = ""
                            ScrobbleOutbox.get(context).schedule()
                            onDismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: LastFmConfigurationException) { error = "Last.fm sign-in is not configured in this build. Please use a configured release." }
                        catch (failed: LastFmException) { error = when (failed.code) {
                            4 -> "Incorrect username or password. Please try again."
                            10, 13, 26 -> "Last.fm sign-in is unavailable in this build. Please update the app or contact support."
                            29 -> "Too many attempts. Please wait and try again."
                            else -> "Last.fm could not sign in. Please try again."
                        } }
                        catch (_: Exception) { error = "Could not reach Last.fm. Check your connection and retry." }
                        finally { busy = false }
                    } })
            }, dismissButton = {
                ConnectionActionButton("Cancel", { password = ""; onDismiss() }, enabled = !busy)
            })
    }
}

@Composable
private fun ConnectedLibraryPage(provider: String, onBack: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val client = remember(context) { ConnectedLibraries(context) }
    val colors = LocalAppColors.current
    val service = if (provider == "youtube") ConnectionService.YouTube else ConnectionService.Spotify
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
    CompositionLocalProvider(LocalConnectionService provides service) {
    LazyColumn(modifier.fillMaxSize().testTag("connected_library_page"), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 160.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ConnectionActionButton("Back to accounts", { importJob?.cancel(); onBack() })
            Text(if (provider == "youtube") "YouTube Music library" else "Spotify library", color = colors.textPrimary,
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("Import a collection into your local library. Import again to refresh it.", color = colors.textSecondary) }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        error?.let { message -> item { Text(message, color = colors.primaryAccent)
            ConnectionActionButton("Retry", { reload++ }) } }
        if (progress.isNotBlank()) item { Text(progress, color = colors.textSecondary) }
        if (!loading && collections.isEmpty() && error == null) item { Text("No saved collections found.", color = colors.textSecondary) }
        items(collections, key = { it.id }) { collection ->
            Surface(shape = RoundedCornerShape(16.dp), color = connectionSurface(service), border = BorderStroke(1.dp, service.accent.copy(alpha = .35f))) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(collection.title, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    ConnectionActionButton(if (importing == collection.id) "Importing…" else "Import", enabled = importing == null, primary = true, onClick = {
                        importing = collection.id
                        importJob = scope.launch {
                            try {
                                val result = client.import(collection) { done, total -> progress = "Importing $done of $total tracks…" }
                                progress = "Imported ${result.imported} ${if (result.imported == 1) "track" else "tracks"}" +
                                    (if (result.skipped > 0) " · ${result.skipped} already available" else "") +
                                    (if (result.unmatched > 0) " · ${result.unmatched} could not be matched" else "") + "."
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (_: Exception) { progress = "Import could not finish. Your existing library was kept. Please retry." }
                            finally { importing = null }
                        }
                    })
                }
            }
        }
    }
}
}
