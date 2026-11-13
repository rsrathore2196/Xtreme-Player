package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.LocalAppColors

internal enum class ConnectionService(val title: String, val accent: Color, val button: Color, val onButton: Color, val icon: Int) {
    YouTube("YouTube Music", Color(0xFFFF0033), Color(0xFFD9002B), Color.White, R.drawable.ic_connection_youtube),
    LastFm("Last.fm", Color(0xFFD51007), Color(0xFFD51007), Color.White, R.drawable.ic_connection_lastfm),
    Spotify("Spotify", Color(0xFF1ED760), Color(0xFF1ED760), Color.Black, R.drawable.ic_connection_spotify)
}

internal val LocalConnectionService = staticCompositionLocalOf { ConnectionService.YouTube }

@Composable
internal fun connectionSurface(service: ConnectionService): Color {
    val dark = LocalAppColors.current.isDark
    return service.accent.copy(alpha = if (dark) .10f else .055f)
        .compositeOver(if (dark) Color(0xFF101010) else Color.White)
}

/** Filled and outlined actions retain their service identity in every app color mode. */
@Composable
internal fun ConnectionActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, primary: Boolean = false) {
    val service = LocalConnectionService.current
    val dark = LocalAppColors.current.isDark
    val background = if (primary) service.button else service.accent.copy(alpha = if (dark) .15f else .09f)
        .compositeOver(connectionSurface(service))
    val foreground = if (primary) service.onButton else if (dark) Color.White else when (service) {
        ConnectionService.YouTube -> Color(0xFFAD0022)
        ConnectionService.LastFm -> Color(0xFFAD0D06)
        ConnectionService.Spotify -> Color(0xFF086A2D)
    }
    Button(onClick, modifier.heightIn(min = 40.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, service.accent.copy(alpha = if (primary) 1f else .35f)),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 9.dp),
        colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground,
            disabledContainerColor = background.copy(alpha = .45f), disabledContentColor = foreground.copy(alpha = .45f))) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}
