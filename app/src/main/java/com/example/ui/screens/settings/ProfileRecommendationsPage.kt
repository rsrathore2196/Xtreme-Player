package com.example.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserProfile
import com.example.data.model.CountryData
import com.example.ui.theme.LocalAppColors

/**
 * Subpage destinations inside Profile and Recommendations
 */
enum class ProfileSubPage {
    MAIN,
    EDIT_NAME,
    EDIT_COUNTRY,
    EDIT_RECOMMENDATIONS_LANGUAGES
}

@Composable
fun ProfileRecommendationsPage(
    userProfile: UserProfile?,
    isDarkMode: Boolean,
    onUpdateProfile: (UserProfile) -> Unit,
    onSubPageChange: (title: String?, subtitle: String?, onBack: (() -> Boolean)?) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier
) {
    var currentSubPage by remember { mutableStateOf(ProfileSubPage.MAIN) }

    // Intercept back action when in a subpage
    BackHandler(enabled = currentSubPage != ProfileSubPage.MAIN) {
        currentSubPage = ProfileSubPage.MAIN
    }

    // Notify parent top bar about subpage state
    LaunchedEffect(currentSubPage) {
        when (currentSubPage) {
            ProfileSubPage.MAIN -> onSubPageChange(null, null, null)
            ProfileSubPage.EDIT_NAME -> onSubPageChange(
                "Profile Name",
                "Customize your display name",
                { currentSubPage = ProfileSubPage.MAIN; true }
            )
            ProfileSubPage.EDIT_COUNTRY -> onSubPageChange(
                "Country & Region",
                "Famous regional hits recommendation priority",
                { currentSubPage = ProfileSubPage.MAIN; true }
            )
            ProfileSubPage.EDIT_RECOMMENDATIONS_LANGUAGES -> onSubPageChange(
                "Recommendation Languages",
                "Multi-select languages for music feeds",
                { currentSubPage = ProfileSubPage.MAIN; true }
            )
        }
    }

    AnimatedContent(
        targetState = currentSubPage,
        transitionSpec = {
            if (targetState != ProfileSubPage.MAIN) {
                slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
            } else {
                slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
            }
        },
        label = "ProfileSubPageTransition",
        modifier = modifier.fillMaxSize()
    ) { subPage ->
        when (subPage) {
            ProfileSubPage.MAIN -> {
                ProfileRecommendationsOverview(
                    userProfile = userProfile,
                    isDarkMode = isDarkMode,
                    onNavigateToName = { currentSubPage = ProfileSubPage.EDIT_NAME },
                    onNavigateToCountry = { currentSubPage = ProfileSubPage.EDIT_COUNTRY },
                    onNavigateToRecommendationsLanguages = {
                        currentSubPage = ProfileSubPage.EDIT_RECOMMENDATIONS_LANGUAGES
                    }
                )
            }
            ProfileSubPage.EDIT_NAME -> {
                EditProfileNameSubPage(
                    userProfile = userProfile,
                    isDarkMode = isDarkMode,
                    onSaveName = { newName ->
                        val current = userProfile ?: UserProfile()
                        onUpdateProfile(current.copy(name = newName))
                        currentSubPage = ProfileSubPage.MAIN
                    },
                    onCancel = { currentSubPage = ProfileSubPage.MAIN }
                )
            }
            ProfileSubPage.EDIT_COUNTRY -> {
                EditCountrySubPage(
                    currentCountryCode = userProfile?.countryCode ?: "IN",
                    isDarkMode = isDarkMode,
                    onSelectCountry = { country ->
                        val current = userProfile ?: UserProfile()
                        onUpdateProfile(
                            current.copy(
                                country = country.name,
                                countryCode = country.code,
                                flag = country.flag
                            )
                        )
                        currentSubPage = ProfileSubPage.MAIN
                    }
                )
            }
            ProfileSubPage.EDIT_RECOMMENDATIONS_LANGUAGES -> {
                EditRecommendationLanguagesSubPage(
                    currentLanguages = userProfile?.languages ?: listOf("English", "Hindi", "Punjabi"),
                    isDarkMode = isDarkMode,
                    onSaveLanguages = { newLangs ->
                        val current = userProfile ?: UserProfile()
                        onUpdateProfile(current.copy(languages = newLangs))
                        currentSubPage = ProfileSubPage.MAIN
                    }
                )
            }
        }
    }
}

/**
 * Main Overview Page for Profile and Recommendations
 * Reflects the chosen options first (Name, Country, Recommendations Language, App Language)
 * Touching each card navigates to the dedicated edit subpage.
 */
@Composable
private fun ProfileRecommendationsOverview(
    userProfile: UserProfile?,
    isDarkMode: Boolean,
    onNavigateToName: () -> Unit,
    onNavigateToCountry: () -> Unit,
    onNavigateToRecommendationsLanguages: () -> Unit,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    val currentCountry = remember(userProfile?.countryCode, userProfile?.country) {
        CountryData.findCountry(userProfile?.countryCode ?: "")
            ?: CountryData.findCountry(userProfile?.country ?: "")
            ?: CountryData.allCountries.first()
    }

    val chosenName = userProfile?.name?.trim()?.ifBlank { "Not Set" } ?: "Not Set"
    val chosenLanguagesList = userProfile?.languages ?: listOf("English", "Hindi", "Punjabi")
    val chosenLanguagesFormatted = if (chosenLanguagesList.size <= 3) {
        chosenLanguagesList.joinToString(", ")
    } else {
        "${chosenLanguagesList.take(3).joinToString(", ")} +${chosenLanguagesList.size - 3} more"
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = "PROFILE & PREFERENCES",
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            color = appColors.textMuted,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 1. Profile Name Option Card
        ProfileOptionItemCard(
            icon = Icons.Default.Person,
            title = "Profile Name",
            chosenValue = chosenName,
            subtitle = "Displayed on Home greeting & library header",
            isDarkMode = isDarkMode,
            onClick = onNavigateToName,
            testTag = "option_profile_name"
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 2. Country Option Card
        ProfileOptionItemCard(
            icon = Icons.Default.Public,
            title = "Country",
            chosenValue = "${currentCountry.flag} ${currentCountry.name} (${currentCountry.code})",
            subtitle = "Top recommendation priority for regional famous chartbusters",
            isDarkMode = isDarkMode,
            onClick = onNavigateToCountry,
            testTag = "option_country"
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 3. Recommendations Language Option Card
        ProfileOptionItemCard(
            icon = Icons.Default.Translate,
            title = "Recommendations Language",
            chosenValue = "$chosenLanguagesFormatted (${chosenLanguagesList.size} Selected)",
            subtitle = "Languages prioritized for song feeds, trending & mood mixes",
            isDarkMode = isDarkMode,
            onClick = onNavigateToRecommendationsLanguages,
            testTag = "option_recommendation_languages"
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * Reusable Card representing each profile setting option with its chosen value reflected
 */
@Composable
private fun ProfileOptionItemCard(
    icon: ImageVector,
    title: String,
    chosenValue: String,
    subtitle: String,
    isDarkMode: Boolean,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .testTag(testTag)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 15.dp)
        ) {
            // Option Icon
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = title,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = appColors.textSecondary
                    )
                }

                Spacer(modifier = Modifier.height(2.dp))

                // Chosen Option Reflected
                Text(
                    text = chosenValue,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = appColors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(10.dp))

            // Chevron Indicator
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = "Edit $title",
                tint = appColors.textMuted,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}
