package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserProfile
import com.example.data.model.CountryData
import com.example.data.model.CountryItem
import com.example.ui.theme.LocalAppColors

/**
 * Subpage 1: Edit Profile Name
 */
@Composable
fun EditProfileNameSubPage(
    userProfile: UserProfile?,
    isDarkMode: Boolean,
    onSaveName: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val inputBg = appColors.cardBackgroundElevated
    val accentColor = appColors.primaryAccent

    var nameInput by remember(userProfile?.name) {
        mutableStateOf(userProfile?.name ?: "")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp)
    ) {
        // Live Display Preview Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "NAME DISPLAY PREVIEW",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 1.sp,
                    color = accentColor
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val avatarBg = if (appColors.isAmoled) {
                        Brush.linearGradient(listOf(Color(0xFF262626), Color(0xFF141414)))
                    } else {
                        Brush.linearGradient(listOf(appColors.primaryAccent, appColors.secondaryAccent))
                    }
                    val initialTextColor = if (appColors.isAmoled) Color.White else appColors.onPrimaryAccent

                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(avatarBg)
                            .then(
                                if (appColors.isAmoled) Modifier.border(1.5.dp, appColors.cardBorder, CircleShape)
                                else Modifier
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        val initial = nameInput.trim().firstOrNull()?.uppercase()
                            ?: userProfile?.name?.trim()?.firstOrNull()?.uppercase()
                            ?: "X"
                        Text(
                            text = initial,
                            color = initialTextColor,
                            fontWeight = FontWeight.Black,
                            fontSize = 20.sp
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column {
                        Text(
                            text = nameInput.ifBlank { "XTREME LISTENER" }.trim().uppercase(),
                            fontWeight = FontWeight.ExtraBold,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 19.sp,
                            letterSpacing = 0.8.sp,
                            color = appColors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Shown on Home greeting & library header in bold caps",
                            fontSize = 11.sp,
                            color = appColors.textMuted
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Input Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Text(
                    text = "Edit Your Name",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Enter your desired display name (maximum 35 characters)",
                    fontSize = 11.5.sp,
                    color = appColors.textMuted
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { if (it.length <= 35) nameInput = it },
                    placeholder = { Text("Enter your name", color = appColors.textMuted, fontSize = 14.sp) },
                    singleLine = true,
                    trailingIcon = {
                        if (nameInput.isNotEmpty()) {
                            IconButton(onClick = { nameInput = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear",
                                    tint = appColors.textMuted,
                                    modifier = Modifier.size(18.dp)
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
                        focusedBorderColor = accentColor,
                        unfocusedBorderColor = cardBorder,
                        focusedTextColor = appColors.textPrimary,
                        unfocusedTextColor = appColors.textPrimary,
                        focusedContainerColor = inputBg,
                        unfocusedContainerColor = inputBg
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("edit_name_text_field")
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Text(
                        text = "${nameInput.length}/35",
                        fontSize = 11.sp,
                        color = appColors.textMuted
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Button(
            onClick = {
                if (nameInput.isNotBlank()) {
                    onSaveName(nameInput.trim())
                }
            },
            enabled = nameInput.isNotBlank(),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor = appColors.onPrimaryAccent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save_profile_name_button")
        ) {
            Text(
                text = "Save Name",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        OutlinedButton(
            onClick = onCancel,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(
                text = "Cancel",
                color = appColors.textSecondary,
                fontSize = 14.sp
            )
        }
    }
}

/**
 * Subpage 2: Edit Country
 */
@Composable
fun EditCountrySubPage(
    currentCountryCode: String,
    isDarkMode: Boolean,
    onSelectCountry: (CountryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val inputBg = appColors.cardBackgroundElevated
    val accentColor = appColors.primaryAccent

    var searchQuery by remember { mutableStateOf("") }

    val filteredCountries = remember(searchQuery) {
        if (searchQuery.isBlank()) {
            CountryData.allCountries
        } else {
            val q = searchQuery.trim().lowercase()
            CountryData.allCountries.filter {
                it.name.lowercase().contains(q) || it.code.lowercase().contains(q)
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Search Input
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search country or code (e.g. India, US)", color = appColors.textMuted, fontSize = 13.5.sp) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = appColors.textMuted,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear search",
                            tint = appColors.textMuted,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accentColor,
                unfocusedBorderColor = cardBorder,
                focusedTextColor = appColors.textPrimary,
                unfocusedTextColor = appColors.textPrimary,
                focusedContainerColor = inputBg,
                unfocusedContainerColor = inputBg
            ),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("country_search_input")
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "Select your country to prioritize famous hits & regional chartbusters",
            fontSize = 11.5.sp,
            color = appColors.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // Countries List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(filteredCountries, key = { it.code }) { country ->
                val isSelected = country.code.equals(currentCountryCode, ignoreCase = true)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            accentColor.copy(alpha = if (isDarkMode) 0.14f else 0.12f)
                        } else {
                            cardBg
                        }
                    ),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) accentColor else cardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectCountry(country) }
                        .testTag("country_item_${country.code}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 13.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = country.flag, fontSize = 24.sp)
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                Text(
                                    text = country.name,
                                    fontSize = 15.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = appColors.textPrimary
                                )
                                Text(
                                    text = "Code: ${country.code}",
                                    fontSize = 11.sp,
                                    color = appColors.textMuted
                                )
                            }
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Subpage 3: Edit Recommendation Languages
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditRecommendationLanguagesSubPage(
    currentLanguages: List<String>,
    isDarkMode: Boolean,
    onSaveLanguages: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val inputBg = appColors.cardBackgroundElevated
    val accentColor = appColors.primaryAccent

    val availableLanguages = remember {
        listOf(
            "English", "Hindi", "Punjabi", "Spanish", "Korean",
            "Japanese", "Tamil", "Telugu", "Arabic", "French",
            "German", "Russian", "Portuguese", "Marathi", "Bengali",
            "Bhojpuri", "Kannada", "Malayalam", "Gujarati"
        )
    }

    var selectedLanguages by remember(currentLanguages) {
        mutableStateOf(currentLanguages.toSet())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // Info Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Recommendation Preferences",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Choose the languages you want music feeds and mood mixes to be curated in. Select one or multiple languages.",
                    fontSize = 12.sp,
                    color = appColors.textMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Selection Counter & Quick actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth()
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = accentColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = "${selectedLanguages.size} Selected",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }

            Row {
                Text(
                    text = "Select All",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { selectedLanguages = availableLanguages.toSet() }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Reset Default",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = appColors.textMuted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { selectedLanguages = setOf("English", "Hindi", "Punjabi") }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Languages FlowRow
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            availableLanguages.forEach { lang ->
                val isSelected = selectedLanguages.contains(lang)
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) {
                        accentColor.copy(alpha = if (isDarkMode) 0.2f else 0.15f)
                    } else {
                        inputBg
                    },
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) accentColor else cardBorder
                    ),
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            val updated = selectedLanguages.toMutableSet()
                            if (isSelected) {
                                if (updated.size > 1) {
                                    updated.remove(lang)
                                    selectedLanguages = updated
                                }
                            } else {
                                updated.add(lang)
                                selectedLanguages = updated
                            }
                        }
                        .testTag("lang_chip_$lang")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = lang,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) appColors.textPrimary else appColors.textSecondary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Save Button
        Button(
            onClick = { onSaveLanguages(selectedLanguages.toList()) },
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = accentColor,
                contentColor = appColors.onPrimaryAccent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .testTag("save_recommendation_languages_button")
        ) {
            Text(
                text = "Save Languages (${selectedLanguages.size})",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Supported App UI Languages model
 */
data class AppLanguageItem(
    val englishName: String,
    val nativeName: String,
    val code: String
)

/**
 * Subpage 4: Edit App Language
 */
@Composable
fun EditAppLanguageSubPage(
    currentLanguage: String,
    isDarkMode: Boolean,
    onSelectLanguage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    val supportedLanguages = remember {
        listOf(
            AppLanguageItem("English", "English (Default)", "en"),
            AppLanguageItem("Hindi", "हिन्दी", "hi"),
            AppLanguageItem("Punjabi", "ਪੰਜਾਬੀ", "pa"),
            AppLanguageItem("Spanish", "Español", "es"),
            AppLanguageItem("French", "Français", "fr"),
            AppLanguageItem("German", "Deutsch", "de"),
            AppLanguageItem("Japanese", "日本語", "ja"),
            AppLanguageItem("Korean", "한국어", "ko"),
            AppLanguageItem("Portuguese", "Português", "pt"),
            AppLanguageItem("Russian", "Русский", "ru"),
            AppLanguageItem("Arabic", "العربية", "ar")
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header info
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "App Interface Language",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Select the language used for interface text, navigation, and settings throughout Xtreme Player.",
                    fontSize = 12.sp,
                    color = appColors.textMuted
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Language List
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(supportedLanguages, key = { it.code }) { item ->
                val isSelected = item.englishName.equals(currentLanguage, ignoreCase = true)
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) {
                            accentColor.copy(alpha = if (isDarkMode) 0.14f else 0.12f)
                        } else {
                            cardBg
                        }
                    ),
                    border = BorderStroke(
                        width = if (isSelected) 1.5.dp else 1.dp,
                        color = if (isSelected) accentColor else cardBorder
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelectLanguage(item.englishName) }
                        .testTag("app_lang_${item.code}")
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp)
                    ) {
                        Column {
                            Text(
                                text = item.nativeName,
                                fontSize = 15.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = appColors.textPrimary
                            )
                            Text(
                                text = item.englishName,
                                fontSize = 11.5.sp,
                                color = appColors.textMuted
                            )
                        }

                        if (isSelected) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Selected",
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
