package com.example.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.UserProfile
import com.example.data.model.CountryData
import com.example.ui.theme.LocalAppColors
import com.example.ui.theme.LiquidGlass
import com.example.ui.theme.liquidGlassCard
import com.example.ui.theme.liquidGlassPill
import com.example.ui.theme.liquidGlassButton
import com.example.ui.theme.bouncyClickable
import com.example.util.AppHaptics

/**
 * Clean, view-only Profile Detail screen displaying attributes:
 * - Name
 * - Country
 * - Preferred Recommendation Languages
 * With a prominent "Edit Details" button below that transitions to the Edit page.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileDetailPage(
    userProfile: UserProfile?,
    isDarkMode: Boolean,
    onEditDetailsClick: () -> Unit,
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

    val displayName = userProfile?.name?.trim()?.ifBlank { "Xtreme Listener" } ?: "Xtreme Listener"
    val displayLanguages = userProfile?.languages?.takeIf { it.isNotEmpty() } ?: listOf("English", "Hindi", "Punjabi")

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .testTag("profile_detail_screen"),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(10.dp))

        // Avatar Header Card
        val avatarBg = if (appColors.isAmoled) {
            Brush.linearGradient(listOf(Color(0xFF222222), Color(0xFF141414)))
        } else {
            Brush.linearGradient(listOf(appColors.primaryAccent, appColors.secondaryAccent))
        }

        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(avatarBg)
                .border(2.dp, appColors.primaryAccent.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            val initial = userProfile?.getInitials() ?: "X"
            Text(
                text = initial,
                color = if (appColors.isAmoled) Color.White else appColors.onPrimaryAccent,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 32.sp
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = displayName,
            style = MaterialTheme.typography.titleLarge.copy(
                fontWeight = FontWeight.Bold,
                color = appColors.textPrimary
            )
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Member Badge
        Box(
            modifier = Modifier.liquidGlassPill(
                colors = appColors,
                shape = RoundedCornerShape(12.dp),
                isActive = true,
                elevation = 2.dp
            )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    tint = appColors.onPrimaryAccent,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    text = "High-Res Hi-Fi Listener",
                    color = appColors.onPrimaryAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Section Title
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Start
        ) {
            Text(
                text = "ACCOUNT ATTRIBUTES",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
                color = appColors.textMuted
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Profile Attribute 1: Name
        ProfileAttributeCard(
            icon = Icons.Default.Person,
            label = "Display Name",
            value = displayName,
            appColors = appColors,
            cardBg = cardBg,
            cardBorder = cardBorder
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Profile Attribute 2: Country
        ProfileAttributeCard(
            icon = Icons.Default.Public,
            label = "Country & Region",
            value = "${currentCountry.flag} ${currentCountry.name} (${currentCountry.code})",
            appColors = appColors,
            cardBg = cardBg,
            cardBorder = cardBorder
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Profile Attribute 3: Preferred Recommendation Languages
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(appColors.primaryAccent.copy(alpha = if (isDarkMode) 0.16f else 0.10f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Language,
                            contentDescription = null,
                            tint = appColors.primaryAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = "Preferred Recommendation Languages",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = appColors.textMuted
                        )
                        Text(
                            text = "${displayLanguages.size} Languages Selected",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = appColors.textPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Language Chips FlowRow
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    displayLanguages.forEach { lang ->
                        Box(
                            modifier = Modifier.liquidGlassPill(
                                colors = appColors,
                                shape = RoundedCornerShape(12.dp),
                                isActive = false,
                                elevation = 2.dp
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(appColors.primaryAccent)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = lang,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = appColors.textPrimary
                                )
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Prominent "Edit Details" Button Below Attributes
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .liquidGlassButton(appColors, shape = RoundedCornerShape(16.dp), elevation = 6.dp, isActive = true)
                .bouncyClickable {
                    AppHaptics.performTap(context)
                    onEditDetailsClick()
                }
                .testTag("button_edit_profile_details"),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = appColors.onPrimaryAccent,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Edit Details",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = appColors.onPrimaryAccent
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ProfileAttributeCard(
    icon: ImageVector,
    label: String,
    value: String,
    appColors: com.example.ui.theme.AppThemeColors,
    cardBg: Color,
    cardBorder: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .liquidGlassCard(appColors, shape = RoundedCornerShape(22.dp), elevation = 6.dp, translucency = 0.82f, tintAccent = true)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(appColors.primaryAccent.copy(alpha = if (appColors.isDark) 0.16f else 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = appColors.primaryAccent,
                    modifier = Modifier.size(18.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = appColors.textMuted
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = value,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = appColors.textPrimary
                )
            }
        }
    }
}
