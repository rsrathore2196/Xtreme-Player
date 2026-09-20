package com.example.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.LocalAppColors

@Composable
fun AboutPage(
    isDarkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val appColors = LocalAppColors.current
    val cardBg = appColors.cardBackground
    val cardBorder = appColors.cardBorder
    val accentColor = appColors.primaryAccent
    val dividerColor = appColors.dividerColor

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // App Hero Branding Card
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // App Logo Icon
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    accentColor,
                                    appColors.secondaryAccent
                                )
                            )
                        )
                        .border(
                            BorderStroke(1.5.dp, Color.White.copy(alpha = 0.3f)),
                            RoundedCornerShape(18.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_foreground),
                        contentDescription = "Xtreme Player Logo",
                        modifier = Modifier.size(56.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "Xtreme Player",
                    fontWeight = FontWeight.Black,
                    fontSize = 22.sp,
                    color = appColors.textPrimary
                )

                Spacer(modifier = Modifier.height(4.dp))

                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = accentColor.copy(alpha = 0.15f),
                    border = BorderStroke(1.dp, accentColor.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = "VERSION $APP_VERSION • AUDIO ENGINE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.sp,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "High-performance studio-grade music player engineered for audiophiles. Delivering 320 kbps ultra-high definition streaming, real-time 5-band parametric equalization, dynamic bass enhancement, and 3D spatial audio.",
                    fontSize = 12.5.sp,
                    color = appColors.textMuted,
                    lineHeight = 18.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // App Information & Developer Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = BorderStroke(1.dp, cardBorder),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Developer
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Developer",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    }

                    Text(
                        text = "RS Rathore",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        textAlign = TextAlign.End
                    )
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // Build Verification
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Verified,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Build Verification",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isDarkMode) Color(0xFF0F3A2A) else Color(0xFFD1FAE5),
                        border = BorderStroke(1.dp, if (isDarkMode) Color(0xFF10B981) else Color(0xFF059669))
                    ) {
                        Text(
                            text = "STABLE",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                            color = if (isDarkMode) Color(0xFF34D399) else Color(0xFF059669),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                }

                HorizontalDivider(color = dividerColor, thickness = 1.dp, modifier = Modifier.padding(vertical = 12.dp))

                // Audio Engine Architecture
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Audio Architecture",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = appColors.textPrimary
                        )
                    }

                    Text(
                        text = "16-Bit PCM / 5-Band DSP",
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = appColors.textMuted,
                        textAlign = TextAlign.End
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Support & Donation Card (Last Section)
        SupportDonationCard(
            isDarkMode = isDarkMode,
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = accentColor,
            dividerColor = dividerColor
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun SupportDonationCard(
    isDarkMode: Boolean,
    cardBg: Color,
    cardBorder: Color,
    accentColor: Color,
    dividerColor: Color,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val appColors = LocalAppColors.current

    val upiId = "rajaasingh88-4@okicici"
    val presetAmounts = listOf("50", "100", "250", "Custom")
    var selectedPreset by remember { mutableStateOf("100") }
    var customAmount by remember { mutableStateOf("") }

    val innerBg = appColors.cardBackgroundElevated
    val innerBorder = appColors.cardBorder

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(1.dp, cardBorder),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            // Header: Title & Tagline
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFFFF9800),
                                    Color(0xFFE65100)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "Support Development",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column {
                    Text(
                        text = "Support Development",
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = appColors.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Support me by any amount if you like my work",
                        fontSize = 12.sp,
                        color = appColors.textMuted,
                        lineHeight = 16.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ================= INDIAN (UPI) DONATION =================
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(innerBg)
                    .border(BorderStroke(1.dp, innerBorder), RoundedCornerShape(16.dp))
                    .padding(14.dp)
            ) {
                // Indian Badge & Title
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = "🇮🇳", fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Indian Supporters",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = appColors.textPrimary
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Instant payment via Google Pay, PhonePe, Paytm, BHIM, Cred, or any UPI app.",
                    fontSize = 11.5.sp,
                    color = appColors.textMuted,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Quick Donation Chips
                Text(
                    text = "SELECT AMOUNT",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp,
                    color = appColors.textSecondary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    presetAmounts.forEach { preset ->
                        val isSelected = selectedPreset == preset
                        val label = if (preset == "Custom") "Custom" else "₹$preset"

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isSelected) {
                                        accentColor
                                    } else {
                                        appColors.cardBackground
                                    }
                                )
                                .border(
                                    BorderStroke(
                                        1.dp,
                                        if (isSelected) {
                                            accentColor
                                        } else {
                                            appColors.cardBorder
                                        }
                                    ),
                                    RoundedCornerShape(10.dp)
                                )
                                .clickable { selectedPreset = preset }
                                .testTag("donation_chip_$preset"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isSelected) {
                                    appColors.onPrimaryAccent
                                } else {
                                    appColors.textSecondary
                                }
                            )
                        }
                    }
                }

                // Custom amount field
                if (selectedPreset == "Custom") {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = customAmount,
                        onValueChange = { input ->
                            // Only allow numbers
                            if (input.all { it.isDigit() } && input.length <= 6) {
                                customAmount = input
                            }
                        },
                        label = { Text("Enter Custom Amount (INR)", fontSize = 11.5.sp) },
                        placeholder = { Text("e.g. 500", fontSize = 12.sp) },
                        prefix = { Text("₹ ", fontWeight = FontWeight.Bold, color = accentColor) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentColor,
                            unfocusedBorderColor = appColors.cardBorder,
                            focusedLabelColor = accentColor,
                            unfocusedLabelColor = appColors.textSecondary,
                            focusedTextColor = appColors.textPrimary,
                            unfocusedTextColor = appColors.textPrimary,
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_amount_input")
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Primary CTA Button: Donate via UPI 🚀
                Button(
                    onClick = {
                        val amountDigits = if (selectedPreset == "Custom") {
                            customAmount.trim().filter { it.isDigit() }
                        } else {
                            selectedPreset
                        }
                        val amountInt = amountDigits.toIntOrNull()

                        val baseUpiUrl = "upi://pay?pa=$upiId&pn=Xtreme%20Player&tn=Support%20Xtreme%20Player%20Development&cu=INR"
                        val finalUpiUrl = if (amountInt != null && amountInt > 0) {
                            "$baseUpiUrl&am=$amountInt"
                        } else {
                            baseUpiUrl
                        }

                        val upiUri = Uri.parse(finalUpiUrl)
                        val upiIntent = Intent(Intent.ACTION_VIEW, upiUri)
                        val chooser = Intent.createChooser(upiIntent, "Donate via UPI App")

                        try {
                            context.startActivity(chooser)
                        } catch (e: Exception) {
                            Toast.makeText(context, "No UPI app found on your device.", Toast.LENGTH_LONG).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        contentColor = appColors.onPrimaryAccent
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .testTag("donate_via_upi_button")
                ) {
                    Text(
                        text = "Donate via UPI 🚀",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
