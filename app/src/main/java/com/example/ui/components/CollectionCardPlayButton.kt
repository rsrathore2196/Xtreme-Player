package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.data.model.CollectionPlaybackTarget
import com.example.ui.theme.LocalAppColors

internal data class CollectionPlaybackActions(
    val pendingKey: String? = null, val play: (CollectionPlaybackTarget) -> Unit = {}
)
internal val LocalCollectionPlayback = staticCompositionLocalOf { CollectionPlaybackActions() }

/** A compact visible circle retains the platform's larger minimum touch target. */
@Composable
internal fun CollectionCardPlayButton(
    title: String, onPlay: () -> Unit, modifier: Modifier = Modifier,
    loading: Boolean = false, small: Boolean = false, tag: String = "collection_card_play"
) {
    val colors = LocalAppColors.current
    IconButton(onClick = onPlay, enabled = !loading,
        modifier = modifier.size(if (small) 32.dp else 48.dp).testTag(tag).semantics {
            contentDescription = if (loading) "Preparing $title" else "Play $title"
        }) {
        Box(Modifier.size(if (small) 20.dp else 32.dp).clip(CircleShape).background(colors.primaryAccent)
            .testTag("${tag}_circle"), contentAlignment = Alignment.Center) {
            if (loading) VisibleCircularProgressIndicator(Modifier.size(if (small) 12.dp else 16.dp), colors.onPrimaryAccent, 2.dp)
            else Icon(Icons.Default.PlayArrow, null, Modifier.size(if (small) 14.dp else 20.dp), tint = colors.onPrimaryAccent)
        }
    }
}

@Composable
internal fun CollectionTargetPlayButton(title: String, target: CollectionPlaybackTarget,
    modifier: Modifier = Modifier, small: Boolean = false) {
    val actions = LocalCollectionPlayback.current
    CollectionCardPlayButton(title, { actions.play(target) }, modifier,
        loading = actions.pendingKey == target.key, small = small, tag = "play_collection_${target.key}")
}
