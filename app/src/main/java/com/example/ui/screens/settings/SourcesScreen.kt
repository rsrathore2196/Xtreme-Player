package com.example.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.OtherSettingsPreferences
import com.example.data.local.PlaybackSourcesPreferences
import com.example.data.remote.StreamingAddons
import com.example.playback.AudioQualityCeiling
import com.example.playback.NetworkQualityMonitor
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassSwitchColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Dedicated full-screen Sources screen for organizing playback source priorities,
 * toggling sources, and configuring streaming addons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesScreen(
    onNavigateBack: () -> Unit
) {
    BackHandler { onNavigateBack() }

    val context = LocalContext.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()

    val networkMonitor = remember(context) { NetworkQualityMonitor.getInstance(context) }
    val activeQuality by networkMonitor.activeQualityCeiling.collectAsStateWithLifecycle()

    val sourcesFlow = remember(context) { PlaybackSourcesPreferences.observeSources(context) }
    val sources by sourcesFlow.collectAsStateWithLifecycle(initialValue = PlaybackSourcesPreferences.getSources(context))


    val canAddAddon = sources.count { it.iconType == PlaybackSourcesPreferences.ID_ADDON } < 16
    var showAddAddonDialog by remember { mutableStateOf(false) }
    var editingAddon by remember { mutableStateOf<PlaybackSourcesPreferences.SourceItem?>(null) }
    val addonIds = sources.filter { it.isReorderable }.map { it.id }
    var liveAddonOrder by remember(addonIds) { mutableStateOf(addonIds) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var totalDrag by remember { mutableFloatStateOf(0f) }
    var startTop by remember { mutableFloatStateOf(0f) }
    val rowHeights = remember { mutableMapOf<String, Float>() }
    var lockedHeights by remember { mutableStateOf<Map<String, Float>>(emptyMap()) }
    val displayedSources = liveAddonOrder.mapNotNull { id -> sources.firstOrNull { it.id == id } } +
        sources.filterNot { it.isReorderable }

    fun finishDrag(commit: Boolean) {
        if (draggedId == null) return
        draggedId = null
        totalDrag = 0f
        if (commit) PlaybackSourcesPreferences.reorderAddons(context, liveAddonOrder)
        else liveAddonOrder = addonIds
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("sources_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = colors.textPrimary
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { editingAddon = null; showAddAddonDialog = true },
                        enabled = canAddAddon,
                        modifier = Modifier.testTag("sources_add_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add Addon",
                            tint = colors.textPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = colors.scaffoldBackground
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState()).testTag("streaming_sources_page")
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Sources",
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 36.sp,
                    color = colors.textPrimary
                ),
                modifier = Modifier.padding(bottom = 16.dp)
            )

            val ceiling = activeQuality.effectiveCeiling
            val qualitySummary = when (ceiling) {
                AudioQualityCeiling.DATA_SAVER, AudioQualityCeiling.NORMAL ->
                    "Current Quality: ${ceiling.label} • Only YouTube Music is available"
                AudioQualityCeiling.HIGH ->
                    "Current Quality: High (~320 kbps) • JioSaavn & YouTube Music available"
                AudioQualityCeiling.MAX ->
                    "Current Quality: Maximum / Lossless • All sources unlocked"
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (ceiling == AudioQualityCeiling.MAX) colors.primaryAccent.copy(alpha = 0.12f)
                    else colors.dividerColor.copy(alpha = 0.22f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 20.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (ceiling == AudioQualityCeiling.MAX) Icons.Default.GraphicEq else Icons.Default.Cloud,
                        contentDescription = null,
                        tint = if (ceiling == AudioQualityCeiling.MAX) colors.primaryAccent else colors.textMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = qualitySummary,
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = if (ceiling == AudioQualityCeiling.MAX) colors.primaryAccent else colors.textMuted,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    )
                }
            }

            Text(
                text = "SOURCES, TRIED IN THIS ORDER",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 1.2.sp,
                    color = colors.textMuted
                ),
                modifier = Modifier.padding(bottom = 12.dp, start = 4.dp)
            )

            // Sources Card Container
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .liquidGlassCard(
                        colors,
                        shape = RoundedCornerShape(26.dp),
                        elevation = 6.dp,
                        translucency = 0.88f,
                        tintAccent = false
                    )
            ) {
                displayedSources.forEachIndexed { index, item ->
                    key(item.id) {
                    val isDragging = draggedId == item.id
                    val dragScale by animateFloatAsState(targetValue = if (isDragging) 1.02f else 1f, label = "dragScale")

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .onSizeChanged { rowHeights[item.id] = it.height.toFloat() }
                            .graphicsLayer {
                                translationY = if (isDragging) startTop + totalDrag -
                                    addonTop(liveAddonOrder, item.id, lockedHeights) else 0f
                            }
                            .scale(dragScale)
                            .background(if (isDragging) colors.primaryAccent.copy(alpha = 0.12f) else Color.Transparent)
                    ) {
                        SourceItemRow(
                            index = index + 1,
                            item = item,
                            onConfigure = { editingAddon = item; showAddAddonDialog = true },
                            canReorder = item.isReorderable && liveAddonOrder.size > 1,
                            onToggle = { enabled ->
                                PlaybackSourcesPreferences.setSourceEnabled(context, item.id, enabled)
                            },
                            onDragDelta = { deltaY ->
                                if (draggedId == item.id) {
                                    val height = lockedHeights[item.id] ?: 0f
                                    val lastTop = lockedHeights.values.sum() - height
                                    totalDrag = (totalDrag + deltaY).coerceIn(-startTop, (lastTop - startTop).coerceAtLeast(0f))
                                    val top = startTop + totalDrag
                                    val order = liveAddonOrder.toMutableList()
                                    var at = order.indexOf(item.id)
                                    while (at >= 0) {
                                        val currentTop = addonTop(order, item.id, lockedHeights)
                                        val before = order.getOrNull(at - 1)?.let { lockedHeights[it] } ?: 0f
                                        val after = order.getOrNull(at + 1)?.let { lockedHeights[it] } ?: 0f
                                        when {
                                            at < order.lastIndex && top + height > currentTop + height + after / 2f -> {
                                                order.add(at + 1, order.removeAt(at)); at++
                                            }
                                            at > 0 && top < currentTop - before / 2f -> {
                                                order.add(at - 1, order.removeAt(at)); at--
                                            }
                                            else -> break
                                        }
                                    }
                                    if (order != liveAddonOrder) liveAddonOrder = order.toList()
                                }
                            },
                            onDragStart = {
                                lockedHeights = liveAddonOrder.associateWith { rowHeights[it] ?: 1f }
                                startTop = addonTop(liveAddonOrder, item.id, lockedHeights)
                                totalDrag = 0f
                                draggedId = item.id
                            },
                            onDragEnd = { finishDrag(commit = true) },
                            onDragCancel = { finishDrag(commit = false) },
                            appColors = colors
                        )

                    if (index < displayedSources.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            color = colors.dividerColor.copy(alpha = 0.35f)
                        )
                    }
                    }
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = colors.dividerColor.copy(alpha = 0.35f)
                )

                // Add an addon bottom button inside the card
                Surface(
                    onClick = { editingAddon = null; showAddAddonDialog = true },
                    enabled = canAddAddon,
                    color = Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_addon_row")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(colors.primaryAccent.copy(alpha = 0.15f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = "Add Addon",
                                tint = colors.primaryAccent,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Add an addon",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = colors.primaryAccent,
                                    fontSize = 15.sp
                                )
                            )
                            Text(
                                text = "Connect another compatible high-quality server",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    color = colors.textMuted,
                                    fontSize = 12.sp
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }

    if (showAddAddonDialog) {
        AddAddonDialog(
            currentUrl = editingAddon?.url.orEmpty(),
            currentName = editingAddon?.name.orEmpty(),
            onDismiss = { showAddAddonDialog = false },
            onSave = { url, name ->
                PlaybackSourcesPreferences.saveAddon(context, url, name, editingAddon?.id)
                showAddAddonDialog = false
            },
            onRemove = {
                editingAddon?.id?.let { PlaybackSourcesPreferences.remove(context, it) }
                showAddAddonDialog = false
            }
        )
    }
}

private fun addonTop(order: List<String>, id: String, heights: Map<String, Float>): Float {
    var top = 0f
    for (key in order) {
        if (key == id) break
        top += heights[key] ?: 0f
    }
    return top
}

@Composable
private fun SourceItemRow(
    index: Int,
    item: PlaybackSourcesPreferences.SourceItem,
    onToggle: (Boolean) -> Unit,
    onConfigure: () -> Unit,
    canReorder: Boolean,
    onDragDelta: (Float) -> Unit,
    onDragStart: () -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    appColors: com.example.ui.theme.AppThemeColors
) {
    val isEnabled = item.isEnabled
    val isAllowed = item.isQualityAllowed
    val contentAlpha = if (isAllowed) 1f else 0.42f

    val currentOnDragDelta by rememberUpdatedState(onDragDelta)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    val currentOnDragCancel by rememberUpdatedState(onDragCancel)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp).testTag("source_row_${item.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Priority Number
        Text(
            text = index.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = appColors.textMuted.copy(alpha = if (isAllowed) 0.8f else 0.35f)
            ),
            modifier = Modifier.width(22.dp)
        )

        // Source Icon
        Box(
            modifier = Modifier
                .size(38.dp).clickable(enabled = item.iconType == "addon", onClick = onConfigure)
                .background(
                    if (isAllowed) appColors.primaryAccent.copy(alpha = 0.12f)
                    else appColors.dividerColor.copy(alpha = 0.2f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = getSourceIcon(item.iconType),
                contentDescription = item.name,
                tint = if (isAllowed) appColors.primaryAccent else appColors.textMuted,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Title and Description
        Column(
            modifier = Modifier
                .weight(1f)
                .alpha(contentAlpha)
        ) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = appColors.textPrimary
                )
            )

            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = appColors.textMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                ),
                maxLines = 2
            )

            if (!isAllowed && item.qualityRequirementText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.qualityRequirementText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Switch or Always on
        if (item.isAlwaysOn) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = appColors.primaryAccent.copy(alpha = 0.12f),
                modifier = Modifier
                    .padding(end = 4.dp)
                    .testTag("badge_always_on_${item.id}")
            ) {
                Text(
                    text = "Always On",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = appColors.primaryAccent,
                        fontSize = 12.sp
                    ),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        } else {
            Switch(
                checked = isEnabled && isAllowed,
                onCheckedChange = { onToggle(it) },
                enabled = isAllowed,
                colors = liquidGlassSwitchColors(appColors),
                modifier = Modifier
                    .testTag("switch_source_${item.id}")
                    .scale(0.88f)
            )
        }

        if (canReorder) {
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Reorder ${item.name}",
                tint = appColors.textMuted.copy(alpha = 0.65f),
                modifier = Modifier.size(36.dp).testTag("source_drag_${item.id}")
                    .pointerInput(item.id) {
                        detectDragGestures(
                            onDragStart = { currentOnDragStart() },
                            onDrag = { change, amount -> change.consume(); currentOnDragDelta(amount.y) },
                            onDragEnd = { currentOnDragEnd() },
                            onDragCancel = { currentOnDragCancel() }
                        )
                    }.padding(9.dp)
            )
        }
    }
}

private fun getSourceIcon(type: String): ImageVector {
    return when (type) {
        "addon" -> Icons.Default.Extension
        "jiosaavn" -> Icons.Default.GraphicEq
        "internet_archive" -> Icons.Default.Cloud
        "youtube" -> Icons.Default.PlayCircle
        else -> Icons.Default.Extension
    }
}

@Composable
private fun AddAddonDialog(
    currentUrl: String,
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (url: String, name: String) -> Unit,
    onRemove: () -> Unit
) {
    var url by remember { mutableStateOf(currentUrl) }
    var busy by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val colors = LocalAppColors.current

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = {
            Text(
                text = if (currentUrl.isNotBlank()) "Streaming Addon" else "Add Streaming Addon",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = colors.textPrimary
                )
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "Enter your server URL supporting the streaming manifest protocol.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = colors.textMuted,
                        fontSize = 12.sp
                    )
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = {
                        url = it
                        errorMessage = null
                    },
                    label = { Text("Server URL") },
                    placeholder = { Text("https://example.com/token") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true, enabled = !busy,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_addon_url")
                )

                errorMessage?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = url.isNotBlank() && !busy,
                onClick = {
                    busy = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val result = StreamingAddons.connect(url)
                            onSave(result.baseUrl, result.name)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "Could not connect to this server"
                        } finally {
                            busy = false
                        }
                    }
                },
                modifier = Modifier.testTag("confirm_addon_button")
            ) {
                Text(if (busy) "Testing..." else "Connect & Save")
            }
        },
        dismissButton = {
            Row {
                if (currentUrl.isNotBlank()) {
                    TextButton(
                        onClick = onRemove, enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Remove")
                    }
                }
                TextButton(onClick = onDismiss, enabled = !busy) {
                    Text("Cancel")
                }
            }
        },
        shape = RoundedCornerShape(22.dp),
        containerColor = colors.cardBackground
    )
}
