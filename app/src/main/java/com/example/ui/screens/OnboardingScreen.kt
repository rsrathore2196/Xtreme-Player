package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.CountryData
import com.example.data.model.CountryItem
import com.example.data.model.LanguageData
import com.example.data.model.LanguageOption
import com.example.ui.components.awaitStartupContentDraw
import com.example.performance.BackgroundWorkCoordinator
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.AppThemeColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlass
import com.example.ui.theme.resolveColorScheme
import com.example.ui.theme.TextMuted
import com.example.ui.theme.XtremeGradients
import com.example.util.AppHaptics
import com.example.data.local.BackupRestoreManager
import com.example.ui.viewmodel.OnboardingRestoreState

enum class OnboardingStep {
    STARTUP,
    DETAILS
}

private val FEATURED_LANGUAGE_NAMES = listOf(
    "English", "Hindi", "Punjabi", "Spanish", "Korean", "Tamil", "Telugu"
)

/** First-run surfaces are always black; retain the app's exact theme accents. */
private fun onboardingColors(colors: AppThemeColors): AppThemeColors = colors.copy(
    isDark = true,
    isAmoled = true,
    screenBackground = Brush.verticalGradient(listOf(Color.Black, Color.Black)),
    scaffoldBackground = Color.Black,
    cardBackground = Color(0xFF15171C),
    cardBackgroundElevated = Color(0xFF1D2027),
    inputBackground = Color(0xFF090B0F),
    chipBackground = Color(0xFF20232B),
    cardBorder = Color(0xFF30343D),
    dividerColor = Color(0xFF292D35),
    textPrimary = Color(0xFFF8F9FC),
    textSecondary = Color(0xFFD5D9E2),
    textMuted = Color(0xFFAAB1BF)
)

@Composable
fun OnboardingScreen(
    onComplete: (name: String, languages: List<String>, country: String, countryCode: String, flag: String) -> Unit,
    modifier: Modifier = Modifier,
    restoreState: OnboardingRestoreState = OnboardingRestoreState(),
    onRestoreBackup: (android.net.Uri) -> Unit = {}
) {
    val inheritedColors = LocalAppColors.current
    val colors = remember(inheritedColors) { onboardingColors(inheritedColors) }
    val scheme = remember(colors) { resolveColorScheme(colors.isDark, colors) }
    CompositionLocalProvider(LocalAppColors provides colors) {
        MaterialTheme(colorScheme = scheme) {
            OnboardingContent(onComplete, modifier, restoreState, onRestoreBackup)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun OnboardingContent(
    onComplete: (name: String, languages: List<String>, country: String, countryCode: String, flag: String) -> Unit,
    modifier: Modifier = Modifier,
    restoreState: OnboardingRestoreState,
    onRestoreBackup: (android.net.Uri) -> Unit
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val focusManager = LocalFocusManager.current

    var currentStep by remember { mutableStateOf(OnboardingStep.STARTUP) }
    var nameInput by remember { mutableStateOf("") }
    var selectedLanguages by remember { mutableStateOf(setOf("English", "Punjabi", "Hindi")) }
    var selectedCountry by remember {
        mutableStateOf(CountryData.allCountries.firstOrNull { it.code == "IN" } ?: CountryData.allCountries.first())
    }
    var isCountryPickerOpen by remember { mutableStateOf(false) }
    var isLanguagePickerOpen by remember { mutableStateOf(false) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var pickerError by rememberSaveable { mutableStateOf<String?>(null) }
    val documentPicker = rememberLauncherForActivityResult(object : ActivityResultContracts.OpenDocument() {
        override fun createIntent(context: android.content.Context, input: Array<String>): android.content.Intent =
            super.createIntent(context, input).addCategory(android.content.Intent.CATEGORY_OPENABLE)
    }) { uri ->
        pickerOpen = false
        uri?.let(onRestoreBackup)
    }
    fun openBackupPicker() {
        try { documentPicker.launch(arrayOf("*/*")) }
        catch (_: android.content.ActivityNotFoundException) {
            pickerOpen = false
            pickerError = "No file manager is available to select a backup."
        }
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (BackupRestoreManager.getRequiredStoragePermissions().all { grants[it] == true }) {
            openBackupPicker()
        } else {
            pickerOpen = false
            pickerError = "Allow storage access to select your backup, then tap Restore again."
        }
    }

    LaunchedEffect(restoreState.profileForDetails) {
        restoreState.profileForDetails?.let { profile ->
            nameInput = profile.name
            selectedLanguages = profile.languages.toSet()
            selectedCountry = CountryData.allCountries.firstOrNull { it.code == profile.countryCode } ?: selectedCountry
            currentStep = OnboardingStep.DETAILS
        }
    }

    BackHandler(enabled = restoreState.restoring) { /* Keep the validated restore owned by the ViewModel. */ }

    BackHandler(enabled = currentStep == OnboardingStep.DETAILS) {
        focusManager.clearFocus(force = true)
        BackgroundWorkCoordinator.navigationInteraction()
        currentStep = OnboardingStep.STARTUP
    }

    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val textPrimary = appColors.textPrimary
    val textMuted = appColors.textMuted
    val primaryAccent = appColors.primaryAccent
    val detailsCardColor = appColors.cardBackground
    val detailsInputColor = appColors.inputBackground
    val detailsOutline = appColors.cardBorder

    val isFormValid = nameInput.trim().isNotBlank() && selectedLanguages.isNotEmpty()

    // Five quick-pick languages; the full selection remains available in More.
    val visibleLanguages = remember {
        FEATURED_LANGUAGE_NAMES.take(5)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("onboarding_screen"),
        contentAlignment = Alignment.Center
    ) {
        RetainedOnboardingPages(currentStep) { step ->
            when (step) {
                OnboardingStep.STARTUP -> {
                    OnboardingStartupPage(
                        onStartListening = {
                            BackgroundWorkCoordinator.navigationInteraction()
                            currentStep = OnboardingStep.DETAILS
                        },
                        onRestore = {
                            pickerError = null
                            pickerOpen = true
                            val permissions = BackupRestoreManager.getRequiredStoragePermissions()
                            if (permissions.isEmpty() || BackupRestoreManager.isStoragePermissionGranted(context)) openBackupPicker()
                            else storagePermission.launch(permissions)
                        },
                        actionsEnabled = !pickerOpen && !restoreState.restoring,
                        restoreMessage = if (restoreState.restoring) "Restoring backup…" else pickerError ?: restoreState.error
                    )
                }
                OnboardingStep.DETAILS -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 560.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Centered original logo and app title.
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp, bottom = 4.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(76.dp)) {
                                Image(painterResource(R.drawable.ic_onboarding_logo),
                                    contentDescription = "Xtreme Player Logo",
                                    modifier = Modifier.size(70.4.dp).testTag("onboarding_details_logo"))
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // App Name "Xtreme Player"
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = "Xtreme",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.sp,
                                        fontSize = 26.4.sp,
                                        color = primaryAccent
                                    )
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Player",
                                    style = MaterialTheme.typography.titleLarge.copy(
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 1.sp,
                                        fontSize = 26.4.sp,
                                        color = textPrimary
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = "Personalize your recommendations",
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    color = textMuted,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )


                        }

                        // 2. DISPLAY NAME CARD
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = detailsCardColor
                            ),
                            border = BorderStroke(
                                1.dp,
                                detailsOutline
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_name_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(primaryAccent.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Person,
                                            contentDescription = null,
                                            tint = primaryAccent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Your Display Name",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = textPrimary
                                        )
                                        Text(
                                            text = "Used for your personal mixes and profile",
                                            fontSize = 11.5.sp,
                                            color = textMuted
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                OutlinedTextField(
                                    value = nameInput,
                                    onValueChange = { if (it.length <= 40) nameInput = it },
                                    placeholder = {
                                        Text(
                                            "Enter your name (e.g. Alex)",
                                            color = textMuted,
                                            fontSize = 14.sp
                                        )
                                    },
                                    singleLine = true,
                                    trailingIcon = {
                                        if (nameInput.isNotBlank()) {
                                            IconButton(onClick = { nameInput = "" }, modifier = Modifier.size(32.dp)) {
                                                Icon(
                                                    imageVector = Icons.Default.Clear,
                                                    contentDescription = "Clear",
                                                    tint = textMuted,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    },
                                    keyboardOptions = KeyboardOptions(
                                        capitalization = KeyboardCapitalization.Words,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = primaryAccent,
                                        unfocusedBorderColor = detailsOutline,
                                        focusedTextColor = textPrimary,
                                        unfocusedTextColor = textPrimary,
                                        focusedContainerColor = primaryAccent.copy(alpha = 0.04f).compositeOver(detailsInputColor),
                                        unfocusedContainerColor = detailsInputColor
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(56.dp)
                                        .testTag("onboarding_name_input")
                                )
                            }
                        }

                        // 3. COUNTRY & CHARTS CARD
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = detailsCardColor
                            ),
                            border = BorderStroke(
                                1.dp,
                                detailsOutline
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_country_card")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f, fill = false)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(primaryAccent.copy(alpha = 0.14f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Public,
                                            contentDescription = null,
                                            tint = primaryAccent,
                                            modifier = Modifier.size(17.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = "Country & Charts",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = textPrimary
                                        )
                                        Text(
                                            text = "Regional hits & trending priority",
                                            fontSize = 11.5.sp,
                                            color = textMuted
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                // Sleek Interactive Country Pill
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = primaryAccent.copy(alpha = if (isDark) 0.12f else 0.09f),
                                    border = BorderStroke(1.dp, primaryAccent.copy(alpha = if (isDark) 0.35f else 0.28f)),
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            AppHaptics.performTap(context)
                                            isCountryPickerOpen = true
                                        }
                                        .testTag("onboarding_country_selector")
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    ) {
                                        Text(text = selectedCountry.flag, fontSize = 17.sp)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = selectedCountry.name,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textPrimary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = 120.dp)
                                        )
                                        Spacer(modifier = Modifier.width(5.dp))
                                        Icon(
                                            imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                            contentDescription = "Change country",
                                            tint = primaryAccent,
                                            modifier = Modifier.size(11.dp)
                                        )
                                    }
                                }
                            }
                        }

                        // 4. MUSIC LANGUAGES CARD
                        Card(
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = detailsCardColor
                            ),
                            border = BorderStroke(
                                1.dp,
                                detailsOutline
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("onboarding_languages_card")
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 16.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(primaryAccent.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Language,
                                                contentDescription = null,
                                                tint = primaryAccent,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Column {
                                            Text(
                                                text = "Music Languages",
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 14.sp,
                                                color = textPrimary
                                            )
                                            Text(
                                                text = "Tailor songs to your preference",
                                                fontSize = 11.5.sp,
                                                color = textMuted
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = primaryAccent.copy(alpha = if (isDark) 0.14f else 0.10f),
                                        border = BorderStroke(1.dp, primaryAccent.copy(alpha = if (isDark) 0.35f else 0.25f))
                                    ) {
                                        Text(
                                            text = "${selectedLanguages.size} chosen",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = primaryAccent,
                                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Modern Language Chips Flow
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(9.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    visibleLanguages.forEach { langName ->
                                        val isSelected = selectedLanguages.contains(langName)
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isSelected) {
                                                primaryAccent
                                            } else {
                                                detailsInputColor
                                            },
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSelected) primaryAccent else detailsOutline
                                            ),
                                            shadowElevation = if (isSelected && isDark) 3.dp else 0.dp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    AppHaptics.performTap(context)
                                                    selectedLanguages = if (isSelected) {
                                                        if (selectedLanguages.size > 1) selectedLanguages - langName else selectedLanguages
                                                    } else {
                                                        selectedLanguages + langName
                                                    }
                                                }
                                                .testTag("language_chip_$langName")
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                            ) {
                                                if (isSelected) {
                                                    Icon(
                                                        imageVector = Icons.Default.Check,
                                                        contentDescription = null,
                                                        tint = appColors.onPrimaryAccent,
                                                        modifier = Modifier.size(13.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(5.dp))
                                                }
                                                Text(
                                                    text = langName,
                                                    fontSize = 12.5.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                    color = if (isSelected) appColors.onPrimaryAccent else appColors.textSecondary
                                                )
                                            }
                                        }
                                    }

                                    // "+ More" Button with modern aesthetic styling
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = primaryAccent.copy(alpha = if (isDark) 0.10f else 0.08f),
                                        border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.35f)),
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable {
                                                AppHaptics.performTap(context)
                                                isLanguagePickerOpen = true
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Add,
                                                contentDescription = "More languages",
                                                tint = primaryAccent,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "More",
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryAccent
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // 5. BOTTOM CTA BUTTON & HELPER
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Button(
                                onClick = {
                                    if (isFormValid) {
                                        AppHaptics.performStrong(context)
                                        onComplete(
                                            nameInput.trim(),
                                            selectedLanguages.toList(),
                                            selectedCountry.name,
                                            selectedCountry.code,
                                            selectedCountry.flag
                                        )
                                    }
                                },
                                enabled = isFormValid,
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = primaryAccent,
                                    disabledContainerColor = primaryAccent.copy(alpha = if (isDark) 0.18f else 0.12f),
                                    contentColor = appColors.onPrimaryAccent,
                                    disabledContentColor = textMuted.copy(alpha = 0.7f)
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(54.dp)
                                    .shadow(
                                        elevation = if (isFormValid) 3.dp else 0.dp,
                                        shape = RoundedCornerShape(18.dp),
                                        spotColor = Color.Black
                                    )
                                    .testTag("onboarding_submit_button")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Text(
                                        text = "Continue to Music",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 0.3.sp
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.size(19.dp)
                                    )
                                }
                            }

                            if (!isFormValid) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .clip(CircleShape)
                                            .background(primaryAccent.copy(alpha = 0.7f))
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (nameInput.isBlank()) "Enter your display name above to continue" else "Select at least one language to continue",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = textMuted
                                    )
                                }
                            }
                        }
                    }
                }
}
    }
}

    // SEARCHABLE COUNTRY PICKER BOTTOM SHEET
    if (isCountryPickerOpen) {
        CountryPickerBottomSheet(
            currentCountry = selectedCountry,
            onSelect = {
                selectedCountry = it
                isCountryPickerOpen = false
            },
            onDismiss = { isCountryPickerOpen = false }
        )
    }

    // SEARCHABLE ALL LANGUAGES PICKER BOTTOM SHEET
    if (isLanguagePickerOpen) {
        LanguagesPickerBottomSheet(
            selectedLanguages = selectedLanguages,
            onToggleLanguage = { langName ->
                selectedLanguages = if (selectedLanguages.contains(langName)) {
                    if (selectedLanguages.size > 1) selectedLanguages - langName else selectedLanguages
                } else {
                    selectedLanguages + langName
                }
            },
            onDismiss = { isLanguagePickerOpen = false }
        )
    }
}

/** Prepare the small local form after the intro's first draw, then retain both
 * pages. Only placement/opacity animate; entering never constructs a TextField,
 * logo or language-chip tree during the slide. Hidden pages are not placed,
 * exposed to accessibility, or allowed to accept input. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun RetainedOnboardingPages(step: OnboardingStep, content: @Composable (OnboardingStep) -> Unit) {
    val view = LocalView.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var detailsPrepared by remember { mutableStateOf(false) }
    val transition = updateTransition(step, label = "onboarding_step_crossfade")
    val moving = transition.isRunning || transition.currentState != step
    DisposableEffect(moving) {
        val work = if (moving) BackgroundWorkCoordinator.navigationBusy() else null
        onDispose { work?.close() }
    }
    LaunchedEffect(view, lifecycle, step) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (detailsPrepared) return@repeatOnLifecycle
            if (step == OnboardingStep.DETAILS) {
                detailsPrepared = true
                return@repeatOnLifecycle
            }
            // Real traversals, not a timer. Compose/measure stays on Main and the
            // existing admission gate protects this one-time preparation as well.
            view.awaitStartupContentDraw { }
            val work = BackgroundWorkCoordinator.navigationBusy()
            try {
                detailsPrepared = true
                view.awaitStartupContentDraw { }
            } finally { work.close() }
        }
    }
    Box(Modifier.fillMaxSize()) {
        for (page in OnboardingStep.entries) key(page) {
            if (page == OnboardingStep.STARTUP || detailsPrepared || step == OnboardingStep.DETAILS) {
                val opacity = transition.animateFloat(transitionSpec = {
                    tween(if (targetState == page) 160 else 120)
                }, label = "onboarding_${page}_opacity") { if (it == page) 1f else 0f }
                val offset = transition.animateFloat(transitionSpec = {
                    tween(220, easing = FastOutSlowInEasing)
                }, label = "onboarding_${page}_offset") {
                    if (it == page) 0f else if (page == OnboardingStep.STARTUP) -1f / 6f else 1f / 6f
                }
                Layout(content = { content(page) }, modifier = Modifier.fillMaxSize()
                    .zIndex(if (step == page) 1f else 0f)
                    .then(if (step == page) Modifier else Modifier.clearAndSetSemantics {
                        testTag = "onboarding_${page.name.lowercase()}_prepared"
                        invisibleToUser()
                    })) { measurables, constraints ->
                    val child = measurables.single().measure(constraints)
                    layout(child.width, child.height) {
                        // Measuring an unplaced page prepares it without drawing
                        // it or intercepting touches meant for the visible page.
                        if (step == page || opacity.value > 0f) child.placeWithLayer(0, 0) {
                            alpha = opacity.value
                            translationX = child.width * offset.value
                        }
                    }
                }
            }
        }
    }
}

/**
 * Initial startup page shown the first time a user opens Xtreme Player.
 * Displays the big dynamic app logo, the app name "Xtreme Player" directly under the logo,
 * rich studio-quality audio highlights, and new-user/restore actions.
 */
@Composable
fun OnboardingStartupPage(
    onStartListening: () -> Unit,
    modifier: Modifier = Modifier,
    onRestore: () -> Unit = {},
    actionsEnabled: Boolean = true,
    restoreMessage: String? = null
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val primaryAccent = appColors.primaryAccent
    val textPrimary = appColors.textPrimary
    val textMuted = appColors.textMuted

    Column(
        modifier = modifier
            .fillMaxSize()
            .widthIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(14.dp))

        // Center Brand Identity
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Use the actual launcher foreground, without an enclosing ring or badge.
            Image(
                painter = painterResource(R.drawable.ic_onboarding_logo),
                contentDescription = "Xtreme Player Logo",
                modifier = Modifier.size(136.dp).testTag("onboarding_brand_logo")
            )

            Spacer(modifier = Modifier.height(20.dp))

            // App name "Xtreme Player" directly under the logo
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Xtreme",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        fontSize = 34.sp,
                        color = primaryAccent
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Player",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.sp,
                        fontSize = 34.sp,
                        color = textPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Redefining High Fidelity",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = primaryAccent.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                    fontSize = 15.sp,
                    textAlign = TextAlign.Center
                ),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "High-quality sound, lyrics that move with every word, and music picked for your world.",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = textMuted,
                    textAlign = TextAlign.Center,
                    lineHeight = 21.sp,
                    fontSize = 13.5.sp
                ),
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Feature Badges
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                StartupFeaturePill(
                    icon = Icons.Default.GraphicEq,
                    title = "Hi-Res Lossless Audio",
                    description = "Lossless FLAC and high-quality 320 kbps audio"
                )
                StartupFeaturePill(
                    icon = Icons.Default.Lyrics,
                    title = "Real Time Synced Lyrics",
                    description = "Karoke Style Syllable Synced Lyrics"
                )
                StartupFeaturePill(
                    icon = Icons.Default.Star,
                    title = "Personalized Just For You",
                    description = "Your languages, regional hits, and curated mixes"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    AppHaptics.performTap(context)
                    onStartListening()
                },
                enabled = actionsEnabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = primaryAccent,
                    contentColor = appColors.onPrimaryAccent
                ),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .shadow(
                        elevation = 3.dp,
                        shape = RoundedCornerShape(22.dp),
                        spotColor = Color.Black
                    )
                    .testTag("start_listening_button")
            ) {
                Text(
                    text = "New User",
                    maxLines = 1,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp
                )
            }
            Button(
                onClick = { AppHaptics.performTap(context); onRestore() },
                enabled = actionsEnabled,
                shape = RoundedCornerShape(22.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp),
                modifier = Modifier.weight(1f).height(56.dp).testTag("onboarding_restore_button")
            ) {
                Text("Restore", fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
            }
        }
        restoreMessage?.let { message ->
            Text(message, color = textPrimary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp).testTag("onboarding_restore_status"))
        }
        Text("Open source · Ad-free · Free to use", color = textMuted,
            fontSize = 11.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
    }
}

@Composable
private fun StartupFeaturePill(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = appColors.cardBackground,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(appColors.primaryAccent.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = appColors.primaryAccent,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = appColors.textPrimary
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = appColors.textMuted
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CountryPickerBottomSheet(
    currentCountry: CountryItem,
    onSelect: (CountryItem) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    val filteredCountries = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            CountryData.allCountries
        } else {
            CountryData.allCountries.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.code.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val bg = appColors.bottomSheetBackground
    val cardBorder = appColors.cardBorder
    val textPrimary = appColors.textPrimary
    val textMuted = appColors.textMuted
    val primaryAccent = appColors.primaryAccent

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bg,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .statusBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Select Your Country",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                    Text(
                        text = "Prioritizes trending charts for your region",
                        fontSize = 11.5.sp,
                        color = textMuted
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Close",
                        tint = textMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search country...", color = textMuted.copy(alpha = 0.6f), fontSize = 13.5.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = primaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = null, tint = textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryAccent,
                    unfocusedBorderColor = cardBorder,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedContainerColor = appColors.cardBackgroundElevated.copy(alpha = 0.5f),
                    unfocusedContainerColor = appColors.cardBackgroundElevated.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Countries List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredCountries, key = { it.code }) { country ->
                    val isSelected = country.code == currentCountry.code
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) {
                            primaryAccent.copy(alpha = if (isDark) 0.18f else 0.12f)
                        } else {
                            Color.Transparent
                        },
                        border = if (isSelected) BorderStroke(1.dp, primaryAccent) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                AppHaptics.performTap(context)
                                onSelect(country)
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(text = country.flag, fontSize = 20.sp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = country.name,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = textPrimary
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = country.code,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSelected) primaryAccent else textMuted
                                )
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = primaryAccent,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagesPickerBottomSheet(
    selectedLanguages: Set<String>,
    onToggleLanguage: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var searchQuery by remember { mutableStateOf("") }

    val filteredLanguages = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            LanguageData.allLanguages
        } else {
            LanguageData.allLanguages.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.nativeScript.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val bg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val textPrimary = appColors.textPrimary
    val textMuted = appColors.textMuted
    val primaryAccent = appColors.primaryAccent

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = bg,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .statusBarsPadding()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column {
                    Text(
                        text = "Select Music Languages",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = textPrimary
                    )
                    Text(
                        text = "${selectedLanguages.size} languages active",
                        fontSize = 11.5.sp,
                        color = primaryAccent
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Close",
                        tint = textMuted
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search language...", color = textMuted.copy(alpha = 0.6f), fontSize = 13.5.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = primaryAccent,
                        modifier = Modifier.size(18.dp)
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotBlank()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(imageVector = Icons.Default.Clear, contentDescription = null, tint = textMuted, modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = primaryAccent,
                    unfocusedBorderColor = cardBorder,
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary,
                    focusedContainerColor = appColors.cardBackgroundElevated.copy(alpha = 0.5f),
                    unfocusedContainerColor = appColors.cardBackgroundElevated.copy(alpha = 0.25f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Languages List
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(filteredLanguages, key = { it.id }) { lang ->
                    val isSelected = selectedLanguages.contains(lang.name)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) {
                            primaryAccent.copy(alpha = if (isDark) 0.18f else 0.12f)
                        } else {
                            Color.Transparent
                        },
                        border = if (isSelected) BorderStroke(1.dp, primaryAccent) else null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                AppHaptics.performTap(context)
                                onToggleLanguage(lang.name)
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)
                        ) {
                            Column {
                                Text(
                                    text = lang.name,
                                    fontSize = 14.5.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = textPrimary
                                )
                                if (lang.nativeScript != lang.name) {
                                    Text(
                                        text = lang.nativeScript,
                                        fontSize = 11.sp,
                                        color = if (isSelected) primaryAccent else textMuted
                                    )
                                }
                            }
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Selected",
                                    tint = primaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
