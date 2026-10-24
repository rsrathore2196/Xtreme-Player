package com.example.ui.components

import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.example.data.local.LyricsTranslationLanguages
import com.example.performance.AppDispatchers
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LocalAppColors
import kotlinx.coroutines.withContext

/** Uses the provider picker's window, surface and platform dialog transition. Selection is staged until Submit. */
@Composable
internal fun LyricsTranslationLanguagePopup(
    currentLanguageTag: String,
    onSelectLanguage: (String) -> Unit,
    onDismissRequest: () -> Unit,
    appColors: AppThemeColors = LocalAppColors.current
) {
    val locale = LocalConfiguration.current.locales[0]
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable(currentLanguageTag) { mutableStateOf(currentLanguageTag) }
    var options by remember(locale) { mutableStateOf<List<LyricsTranslationLanguages.Option>>(emptyList()) }
    LaunchedEffect(locale) {
        options = withContext(AppDispatchers.CpuBounded) { LyricsTranslationLanguages.options(locale) }
    }
    val filtered = remember(options, query) { options.filter { it.matches(query) } }
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window) {
            window?.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            window?.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window?.setDimAmount(0f)
            onDispose { }
        }
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().imePadding().clickable(
            interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismissRequest),
            contentAlignment = Alignment.Center) {
            val compact = maxHeight < 440.dp
            val inset = if (compact) 12.dp else 20.dp
            Surface(Modifier.widthIn(min = 300.dp, max = 380.dp).fillMaxWidth(0.88f)
                .padding(inset).heightIn(max = (maxHeight - inset * 2).coerceAtLeast(0.dp))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
                shape = RoundedCornerShape(26.dp),
                color = if (appColors.isAmoled) Color(0xFF141414) else appColors.cardBackground,
                border = BorderStroke(1.2.dp, appColors.cardBorder.copy(alpha = if (appColors.isDark) 0.5f else 0.7f)),
                shadowElevation = 20.dp) {
                Column(Modifier.padding(inset)) {
                    Text("Lyrics Translate Language", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, color = appColors.textPrimary)
                    if (!compact) Text("Choose the language used by Translate Lyrics", fontSize = 11.5.sp,
                        color = appColors.textMuted, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                        placeholder = { Text("Search language") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                        shape = RoundedCornerShape(16.dp), colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = appColors.textPrimary, unfocusedTextColor = appColors.textPrimary,
                            focusedBorderColor = appColors.primaryAccent, unfocusedBorderColor = appColors.cardBorder,
                            cursorColor = appColors.primaryAccent),
                        modifier = Modifier.fillMaxWidth().testTag("lyrics_language_search"))
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).weight(1f, fill = false)
                        .testTag("lyrics_language_list")) {
                        items(filtered, key = { it.tag }, contentType = { "translation_language" }) { option ->
                            Surface(onClick = { selected = option.tag }, shape = RoundedCornerShape(16.dp),
                                color = if (selected == option.tag) appColors.primaryAccent.copy(alpha = 0.12f) else Color.Transparent,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                    .testTag("lyrics_language_option_${option.tag}")) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected == option.tag, onClick = null, colors = RadioButtonDefaults.colors(
                                        selectedColor = appColors.primaryAccent, unselectedColor = appColors.textMuted))
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(option.name, fontSize = 14.sp, color = if (selected == option.tag)
                                            appColors.primaryAccent else appColors.textPrimary,
                                            fontWeight = if (selected == option.tag) FontWeight.Bold else FontWeight.Medium)
                                        if (option.nativeName != option.name) Text(option.nativeName, fontSize = 11.sp, color = appColors.textMuted)
                                    }
                                }
                            }
                        }
                        if (options.isNotEmpty() && filtered.isEmpty()) item {
                            Text("No languages found", color = appColors.textMuted, modifier = Modifier.padding(16.dp))
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = if (compact) 8.dp else 12.dp), color = appColors.dividerColor)
                    Button(onClick = { onSelectLanguage(selected); onDismissRequest() },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = appColors.primaryAccent, contentColor = appColors.onPrimaryAccent),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("lyrics_language_submit")) {
                        Text("Submit", fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
