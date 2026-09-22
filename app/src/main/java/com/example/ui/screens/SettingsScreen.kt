package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.AppThemeMode
import com.example.data.local.UserProfile
import com.example.data.model.CountryData
import com.example.playback.AudioEffectsState
import com.example.playback.AudioQuality
import com.example.playback.PlayerUiState
import com.example.ui.screens.settings.AboutPage
import com.example.ui.screens.settings.BackupRestorePage
import com.example.ui.screens.settings.MusicPlaybackPage
import com.example.ui.screens.settings.OtherSettingsPage
import com.example.ui.screens.settings.ProfileRecommendationsPage
import com.example.ui.screens.settings.SettingsCategory
import com.example.ui.screens.settings.SettingsCategoryCardGroup
import com.example.ui.screens.settings.ThemesAppUiPage
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.XtremeLightBlue
import com.example.util.AppHaptics

@Composable
fun SettingsScreen(
    selectedQuality: AudioQuality = AudioQuality.EXTREME_320,
    effectsState: AudioEffectsState,
    isDarkMode: Boolean,
    themeMode: AppThemeMode,
    userProfile: UserProfile?,
    onUpdateProfile: (UserProfile) -> Unit = {},
    onSelectThemeMode: (AppThemeMode) -> Unit,
    onToggleDarkMode: () -> Unit,
    onAudioQualitySelected: (AudioQuality) -> Unit,
    onCrystalClarityToggle: (Boolean) -> Unit,
    onToggleEqualizer: (Boolean) -> Unit,
    onOpenEqualizer: () -> Unit,
    onSelectPreset: (String) -> Unit,
    customThemeState: com.example.data.local.CustomThemeState? = null,
    onUpdateCustomThemeState: (com.example.data.local.CustomThemeState) -> Unit = {},
    onApplyPreset: (com.example.data.local.AppThemePreset) -> Unit = {},
    onTextScaleChanged: (Int) -> Unit = {},
    onUiScaleChanged: (Int) -> Unit = {},
    onSubpageStateChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf<SettingsCategory?>(null) }
    var subPageTitle by remember { mutableStateOf<String?>(null) }
    var subPageSubtitle by remember { mutableStateOf<String?>(null) }
    var subPageBackAction by remember { mutableStateOf<(() -> Boolean)?>(null) }
    val accentColor = com.example.ui.theme.LocalAppColors.current.primaryAccent
    val screenBg = com.example.ui.theme.LocalAppColors.current.scaffoldBackground

    var showEditProfileSheet by remember { mutableStateOf(false) }

    // Reset subpage state and notify parent when category changes
    LaunchedEffect(selectedCategory) {
        subPageTitle = null
        subPageSubtitle = null
        subPageBackAction = null
        onSubpageStateChanged(selectedCategory != null)
    }

    if (showEditProfileSheet) {
        com.example.ui.components.EditProfileSheet(
            userProfile = userProfile ?: com.example.data.local.UserProfile(),
            onDismiss = { showEditProfileSheet = false },
            onSave = { updated ->
                onUpdateProfile(updated)
                showEditProfileSheet = false
            }
        )
    }

    // Ensure mini player state resets when leaving Settings
    DisposableEffect(Unit) {
        onDispose {
            onSubpageStateChanged(false)
        }
    }

    // Handle back button when in a category subpage or nested subpage
    BackHandler(enabled = selectedCategory != null) {
        if (subPageBackAction?.invoke() != true) {
            selectedCategory = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(screenBg)
            .statusBarsPadding()
            .testTag("settings_screen")
    ) {
        // Top Navigation Bar
        SettingsTopBar(
            selectedCategory = selectedCategory,
            subPageTitle = subPageTitle,
            subPageSubtitle = subPageSubtitle,
            isDarkMode = isDarkMode,
            onBackClick = {
                if (subPageBackAction?.invoke() != true) {
                    selectedCategory = null
                }
            }
        )

        // Content Area: Category List or Category Detail Subpage
        AnimatedContent(
            targetState = selectedCategory,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally(
                        initialOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt() },
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing)))
                        .togetherWith(
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> (-fullWidth * 0.08f).toInt() },
                                animationSpec = tween(180, easing = FastOutLinearInEasing)
                            ) + fadeOut(animationSpec = tween(160))
                        )
                } else {
                    (slideInHorizontally(
                        initialOffsetX = { fullWidth -> (-fullWidth * 0.08f).toInt() },
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    ) + fadeIn(animationSpec = tween(200, easing = LinearOutSlowInEasing)))
                        .togetherWith(
                            slideOutHorizontally(
                                targetOffsetX = { fullWidth -> (fullWidth * 0.12f).toInt() },
                                animationSpec = tween(180, easing = FastOutLinearInEasing)
                            ) + fadeOut(animationSpec = tween(160))
                        )
                }
            },
            modifier = Modifier.fillMaxSize(),
            label = "SettingsCategoryTransition"
        ) { category ->
            if (category == null) {
                // Categorized Overview (Matching screenshot structure)
                SettingsOverviewList(
                    userProfile = userProfile,
                    isDarkMode = isDarkMode,
                    onProfileClick = { showEditProfileSheet = true },
                    onCategoryClick = { selectedCategory = it }
                )
            } else {
                // Sub-Page for the selected category
                when (category) {
                    SettingsCategory.THEMES_APP_UI -> {
                        ThemesAppUiPage(
                            isDarkMode = isDarkMode,
                            themeMode = themeMode,
                            onSelectThemeMode = onSelectThemeMode,
                            customThemeState = customThemeState,
                            onUpdateCustomThemeState = onUpdateCustomThemeState,
                            onApplyPreset = onApplyPreset
                        )
                    }
                    SettingsCategory.MUSIC_PLAYBACK -> {
                        MusicPlaybackPage(
                            selectedQuality = selectedQuality,
                            effectsState = effectsState,
                            isDarkMode = isDarkMode,
                            onAudioQualitySelected = onAudioQualitySelected,
                            onCrystalClarityToggle = onCrystalClarityToggle,
                            onToggleEqualizer = onToggleEqualizer,
                            onOpenEqualizer = onOpenEqualizer,
                            onSelectPreset = onSelectPreset
                        )
                    }
                    SettingsCategory.OTHER_SETTINGS -> {
                        OtherSettingsPage(
                            isDarkMode = isDarkMode,
                            onTextScaleChanged = onTextScaleChanged,
                            onUiScaleChanged = onUiScaleChanged
                        )
                    }
                    SettingsCategory.BACKUP_RESTORE -> {
                        BackupRestorePage(
                            isDarkMode = isDarkMode
                        )
                    }
                    SettingsCategory.ABOUT -> {
                        AboutPage(
                            isDarkMode = isDarkMode
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTopBar(
    selectedCategory: SettingsCategory?,
    subPageTitle: String? = null,
    subPageSubtitle: String? = null,
    isDarkMode: Boolean,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMainSettings = selectedCategory == null && subPageTitle == null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(
                horizontal = 20.dp,
                vertical = if (isMainSettings) 16.dp else 12.dp
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            if (!isMainSettings) {
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(38.dp).testTag("settings_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Column {
                Text(
                    text = subPageTitle ?: (selectedCategory?.title ?: "Settings"),
                    fontWeight = if (isMainSettings) FontWeight.Black else FontWeight.Bold,
                    fontSize = if (isMainSettings) 34.sp else 22.sp,
                    letterSpacing = if (isMainSettings) (-0.8).sp else (-0.2).sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!isMainSettings) {
                    Text(
                        text = subPageSubtitle ?: "Tap to customize options",
                        fontSize = 11.5.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsOverviewList(
    userProfile: UserProfile?,
    isDarkMode: Boolean,
    onProfileClick: () -> Unit,
    onCategoryClick: (SettingsCategory) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Profile Preview Bar - Tapping opens Edit Profile
        MainProfilePreviewBar(
            userProfile = userProfile,
            isDarkMode = isDarkMode,
            onClick = onProfileClick
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Group 1: Themes & App UI
        SettingsCategoryCardGroup(
            categories = listOf(
                SettingsCategory.THEMES_APP_UI
            ),
            isDarkMode = isDarkMode,
            onCategoryClick = onCategoryClick
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Group 2: Music & Playback + Other Settings
        SettingsCategoryCardGroup(
            categories = listOf(
                SettingsCategory.MUSIC_PLAYBACK,
                SettingsCategory.OTHER_SETTINGS
            ),
            isDarkMode = isDarkMode,
            onCategoryClick = onCategoryClick
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Group 3: Backup & Restore + About
        SettingsCategoryCardGroup(
            categories = listOf(
                SettingsCategory.BACKUP_RESTORE,
                SettingsCategory.ABOUT
            ),
            isDarkMode = isDarkMode,
            onCategoryClick = onCategoryClick
        )

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun MainProfilePreviewBar(
    userProfile: UserProfile?,
    isDarkMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    val currentCountry = remember(userProfile?.countryCode, userProfile?.country) {
        CountryData.findCountry(userProfile?.countryCode ?: "")
            ?: CountryData.findCountry(userProfile?.country ?: "")
            ?: CountryData.allCountries.first()
    }

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier
            .fillMaxWidth()
            .clickable {
                AppHaptics.performTap(context)
                onClick()
            }
            .testTag("main_profile_preview_bar")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            val avatarBg = if (appColors.isAmoled) {
                Brush.linearGradient(listOf(Color(0xFF222222), Color(0xFF141414)))
            } else {
                Brush.linearGradient(listOf(appColors.primaryAccent, appColors.secondaryAccent))
            }
            val initialTextColor = if (appColors.isAmoled) Color.White else appColors.onPrimaryAccent

            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(avatarBg)
                    .then(
                        if (appColors.isAmoled) Modifier.border(1.5.dp, appColors.cardBorder, CircleShape)
                        else Modifier
                    ),
                contentAlignment = Alignment.Center
            ) {
                val initial = userProfile?.getInitials() ?: "X"
                Text(
                    text = initial,
                    color = initialTextColor,
                    fontWeight = FontWeight.Black,
                    fontSize = 20.sp
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = userProfile?.name?.ifBlank { "XTREME LISTENER" }?.trim()?.uppercase() ?: "XTREME LISTENER",
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 17.sp,
                    letterSpacing = 0.5.sp,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = currentCountry.flag, fontSize = 13.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "${currentCountry.name} • First Priority Hits",
                        fontSize = 12.sp,
                        color = accentColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Edit Profile",
                tint = TextMuted,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
