package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.TextMuted
import com.example.ui.theme.XtremeGradients
import com.example.util.AppHaptics

private val FEATURED_LANGUAGE_NAMES = listOf(
    "English", "Hindi", "Punjabi", "Spanish", "Korean", "Tamil", "Telugu", "Arabic"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnboardingScreen(
    onComplete: (name: String, languages: List<String>, country: String, countryCode: String, flag: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current
    val isDark = appColors.isDark
    val focusManager = LocalFocusManager.current

    var nameInput by remember { mutableStateOf("") }
    var selectedLanguages by remember { mutableStateOf(setOf("English", "Punjabi", "Hindi")) }
    var selectedCountry by remember {
        mutableStateOf(CountryData.allCountries.firstOrNull { it.code == "IN" } ?: CountryData.allCountries.first())
    }
    var isCountryPickerOpen by remember { mutableStateOf(false) }
    var isLanguagePickerOpen by remember { mutableStateOf(false) }

    // Dynamic gradient adapting cleanly to current theme colors
    val backgroundBrush = Brush.verticalGradient(
        colors = listOf(
            appColors.scaffoldBackground,
            appColors.cardBackgroundElevated.copy(alpha = if (isDark) 0.6f else 0.4f),
            appColors.scaffoldBackground
        )
    )

    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val textPrimary = appColors.textPrimary
    val textMuted = appColors.textMuted
    val primaryAccent = appColors.primaryAccent

    val isFormValid = nameInput.trim().isNotBlank() && selectedLanguages.isNotEmpty()

    // Top languages to show as quick pills: featured + any custom ones selected
    val visibleLanguages = remember(selectedLanguages) {
        val list = FEATURED_LANGUAGE_NAMES.toMutableList()
        selectedLanguages.forEach { lang ->
            if (!list.contains(lang)) {
                list.add(lang)
            }
        }
        list
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .testTag("onboarding_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // TOP HEADER: Aesthetic Compact Brand Bar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 12.dp)
            ) {
                // Sleek Glow Badge
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(56.dp)
                        .shadow(
                            elevation = 12.dp,
                            shape = CircleShape,
                            spotColor = primaryAccent.copy(alpha = 0.5f)
                        )
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(appColors.primaryAccent, appColors.secondaryAccent)
                            )
                        )
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_xtreme_logo),
                        contentDescription = "Xtreme Logo",
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "XTREME",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.5.sp,
                            color = primaryAccent
                        )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "PLAYER",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Black,
                            letterSpacing = 2.5.sp,
                            color = textPrimary
                        )
                    )
                }

                Text(
                    text = "Personalize your high-fidelity music experience",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = textMuted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium
                    ),
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // UNIFIED COMPACT CARD: Name, Country, and Languages
            Card(
                shape = RoundedCornerShape(22.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = BorderStroke(1.dp, cardBorder),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isDark) 4.dp else 2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("onboarding_name_card")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                ) {
                    // 1. NAME FIELD
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(primaryAccent.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = primaryAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Your Display Name",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = textPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = nameInput,
                        onValueChange = { if (it.length <= 40) nameInput = it },
                        placeholder = {
                            Text(
                                "Enter your name (e.g. Alex)",
                                color = textMuted.copy(alpha = 0.6f),
                                fontSize = 13.5.sp
                            )
                        },
                        singleLine = true,
                        trailingIcon = {
                            if (nameInput.isNotBlank()) {
                                IconButton(onClick = { nameInput = "" }, modifier = Modifier.size(28.dp)) {
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
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = primaryAccent,
                            unfocusedBorderColor = cardBorder,
                            focusedTextColor = textPrimary,
                            unfocusedTextColor = textPrimary,
                            focusedContainerColor = appColors.cardBackgroundElevated.copy(alpha = 0.5f),
                            unfocusedContainerColor = appColors.cardBackgroundElevated.copy(alpha = 0.25f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                            .testTag("onboarding_name_input")
                    )

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = appColors.dividerColor, thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    // 2. COUNTRY & REGION SELECTOR
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_country_card")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(primaryAccent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Public,
                                    contentDescription = null,
                                    tint = primaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "Country & Charts",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.5.sp,
                                    color = textPrimary
                                )
                                Text(
                                    text = "Regional hits priority",
                                    fontSize = 11.sp,
                                    color = textMuted
                                )
                            }
                        }

                        // Compact Clickable Country Pill
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = primaryAccent.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.35f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    AppHaptics.performTap(context)
                                    isCountryPickerOpen = true
                                }
                                .testTag("onboarding_country_selector")
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(text = selectedCountry.flag, fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = selectedCountry.name,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 110.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = "Change country",
                                    tint = primaryAccent,
                                    modifier = Modifier.size(11.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = appColors.dividerColor, thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    // 3. RECOMMENDATIONS LANGUAGES
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("onboarding_languages_card")
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(primaryAccent.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = primaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Music Languages",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = textPrimary
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = primaryAccent.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = "${selectedLanguages.size} chosen",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = primaryAccent,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Compact Language Chips Flow
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        visibleLanguages.forEach { langName ->
                            val isSelected = selectedLanguages.contains(langName)
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (isSelected) {
                                    primaryAccent.copy(alpha = if (isDark) 0.22f else 0.16f)
                                } else {
                                    appColors.cardBackgroundElevated.copy(alpha = 0.5f)
                                },
                                border = BorderStroke(
                                    1.dp,
                                    if (isSelected) primaryAccent else cardBorder
                                ),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
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
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.Check,
                                            contentDescription = null,
                                            tint = primaryAccent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = langName,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) textPrimary else textMuted
                                    )
                                }
                            }
                        }

                        // "+ More" Button to open full language bottom sheet
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = primaryAccent.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, primaryAccent.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    AppHaptics.performTap(context)
                                    isLanguagePickerOpen = true
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "More languages",
                                    tint = primaryAccent,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "More",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryAccent
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // BOTTOM CTA BUTTON
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp),
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
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = primaryAccent,
                        disabledContainerColor = primaryAccent.copy(alpha = 0.2f),
                        contentColor = Color.White,
                        disabledContentColor = textMuted
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .shadow(
                            elevation = if (isFormValid) 12.dp else 0.dp,
                            shape = RoundedCornerShape(16.dp),
                            spotColor = primaryAccent.copy(alpha = 0.6f)
                        )
                        .testTag("onboarding_submit_button")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "Start Listening",
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                if (!isFormValid) {
                    Text(
                        text = if (nameInput.isBlank()) "Enter your name to continue" else "Select at least one language",
                        fontSize = 11.5.sp,
                        color = textMuted,
                        modifier = Modifier.padding(top = 6.dp)
                    )
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
