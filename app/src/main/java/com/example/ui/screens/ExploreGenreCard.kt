package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Celebration
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FitnessCenter
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Nightlight
import androidx.compose.material.icons.rounded.Piano
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Spa
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.ExploreCategory
import com.example.ui.theme.bouncyClickable
import kotlin.math.absoluteValue

/**
 * Visual styling definition for a genre or mood card:
 * gradient colors, thematic icon, and a curated aesthetic tagline.
 */
data class GenreVisualTheme(
    val gradientColors: List<Color>,
    val icon: ImageVector,
    val subtitle: String
)

/**
 * High-performance, lightweight 2-column Explore Genre Card.
 * Adheres to YouTube Music & Spotify aesthetic card proportions while maintaining
 * smooth 120fps scrolling with pure vector artwork and zero heavy bitmap loading.
 */
@Composable
fun ExploreGenreCard(
    genre: String,
    category: ExploreCategory?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val theme = remember(genre) { getGenreVisualTheme(genre) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(
                brush = Brush.linearGradient(
                    colors = theme.gradientColors,
                    start = Offset.Zero,
                    end = Offset.Infinite
                )
            )
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = Color.White.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(16.dp)
                    )
                } else {
                    Modifier.border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(16.dp)
                    )
                }
            )
            .bouncyClickable(onClick = onClick)
            .testTag(if (category == null) "genre_chip_${genre.lowercase()}" else "explore_chip_${category.key}")
    ) {
        // Decorative background watermark icon shifted slightly to the bottom-right corner
        Icon(
            imageVector = theme.icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.16f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 10.dp, y = 10.dp)
                .size(66.dp)
        )

        // Glass badge with crisp thematic icon at top-right
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 9.dp, end = 9.dp)
                .size(28.dp)
                .background(Color.White.copy(alpha = 0.22f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = theme.icon,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(15.dp)
            )
        }

        // Left-aligned Title and Curated Subtitle
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxSize()
                .padding(start = 14.dp, end = 40.dp, top = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = genre,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    letterSpacing = (-0.2).sp
                ),
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = theme.subtitle,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    letterSpacing = 0.1.sp
                ),
                color = Color.White.copy(alpha = 0.88f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * Returns a customized visual theme matching the genre or mood name.
 * Features hand-tuned rich color gradients, icons, and taglines.
 */
fun getGenreVisualTheme(genreName: String): GenreVisualTheme {
    val clean = genreName.trim().lowercase()

    return when {
        clean == "chill" || clean.contains("coffee") || clean.contains("relax") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF4A7BD0), Color(0xFF6C9BE8), Color(0xFF8BB7F0)),
            icon = Icons.Rounded.Coffee,
            subtitle = "Coffee Shop Blend"
        )
        clean == "commute" || clean.contains("drive") || clean.contains("road") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFFD9822B), Color(0xFFE59C3C), Color(0xFFF3B353)),
            icon = Icons.Rounded.DirectionsCar,
            subtitle = "Feel-Good Drive"
        )
        clean == "energize" || clean.contains("energy") || clean.contains("boost") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFFD4A017), Color(0xFFE5B83B), Color(0xFFC78C18)),
            icon = Icons.Rounded.Bolt,
            subtitle = "Pump-Up Beats"
        )
        clean == "feel good" || clean.contains("happy") || clean.contains("sunshine") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF2E9A68), Color(0xFF48B983), Color(0xFF6CD4A1)),
            icon = Icons.Rounded.WbSunny,
            subtitle = "Sunshine Indie"
        )
        clean == "focus" || clean.contains("study") || clean.contains("work") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF4A5568), Color(0xFF606F7B), Color(0xFF718096)),
            icon = Icons.Rounded.Headphones,
            subtitle = "Lofi Loft Beats"
        )
        clean == "gaming" || clean.contains("game") || clean.contains("esports") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF3B4252), Color(0xFF4C566A), Color(0xFF5E81AC)),
            icon = Icons.Rounded.SportsEsports,
            subtitle = "Gaming Hits"
        )
        clean == "party" || clean.contains("dance") || clean.contains("club") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF6B46C1), Color(0xFF805AD5), Color(0xFF9F7AEA)),
            icon = Icons.Rounded.Celebration,
            subtitle = "Dancefloor Anthems"
        )
        clean == "romance" || clean.contains("romantic") || clean.contains("love") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFFC53030), Color(0xFFE53E3E), Color(0xFFF56565)),
            icon = Icons.Rounded.Favorite,
            subtitle = "Romantic Melodies"
        )
        clean == "sad" || clean.contains("heartbreak") || clean.contains("melancholy") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF2D3748), Color(0xFF3F4E65), Color(0xFF4A5568)),
            icon = Icons.Rounded.WaterDrop,
            subtitle = "Mellow Heartstrings"
        )
        clean == "sleep" || clean.contains("night") || clean.contains("bed") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF312E81), Color(0xFF4338CA), Color(0xFF4F46E5)),
            icon = Icons.Rounded.Bedtime,
            subtitle = "Drifting Ambient"
        )
        clean == "pop" -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFFD53F8C), Color(0xFFED64A6), Color(0xFFF687B3)),
            icon = Icons.Rounded.Star,
            subtitle = "Chart Toppers & Hits"
        )
        clean == "hip-hop" || clean == "hip hop" || clean.contains("rap") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFFC05621), Color(0xFFDD6B20), Color(0xFFED8936)),
            icon = Icons.Rounded.GraphicEq,
            subtitle = "Heavy Bass & Bars"
        )
        clean == "rock" || clean.contains("metal") || clean.contains("punk") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF9B2C2C), Color(0xFFC53030), Color(0xFF742A2A)),
            icon = Icons.Rounded.Whatshot,
            subtitle = "Riffs & Anthems"
        )
        clean == "electronic" || clean.contains("edm") || clean.contains("techno") || clean.contains("house") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF007791), Color(0xFF009FB7), Color(0xFF2EC4B6)),
            icon = Icons.Rounded.Album,
            subtitle = "Club & Synth Anthems"
        )
        clean == "bollywood" || clean.contains("hindi") || clean.contains("desi") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFFB83280), Color(0xFFD53F8C), Color(0xFFE53E3E)),
            icon = Icons.Rounded.AutoAwesome,
            subtitle = "Desi Dance & Melody"
        )
        clean == "punjabi" || clean.contains("bhangra") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFFD69E2E), Color(0xFFED8936), Color(0xFFC05621)),
            icon = Icons.Rounded.Audiotrack,
            subtitle = "Dhol & Modern Flow"
        )
        clean == "synthwave" || clean.contains("cyber") || clean.contains("retrowave") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF553C9A), Color(0xFFB83280), Color(0xFF009FB7)),
            icon = Icons.Rounded.Waves,
            subtitle = "Neon Cyberwave"
        )
        clean == "chillhop" || clean.contains("beats") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF2C7A7B), Color(0xFF319795), Color(0xFF38B2AC)),
            icon = Icons.Rounded.Spa,
            subtitle = "Smooth Instrumental"
        )
        clean == "lo-fi" || clean == "lofi" -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF4A5568), Color(0xFF6B46C1), Color(0xFF718096)),
            icon = Icons.Rounded.Radio,
            subtitle = "Study & Relax Beats"
        )
        clean == "jazz" || clean.contains("blues") || clean.contains("soul") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF744210), Color(0xFF975A16), Color(0xFFB7791F)),
            icon = Icons.Rounded.Piano,
            subtitle = "Smooth Soul & Brass"
        )
        clean == "ambient" || clean.contains("drone") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFF1A365D), Color(0xFF2B6CB0), Color(0xFF4299E1)),
            icon = Icons.Rounded.Nightlight,
            subtitle = "Atmospheric Soundscapes"
        )
        clean == "workout" || clean.contains("gym") || clean.contains("fitness") -> GenreVisualTheme(
            gradientColors = listOf(Color(0xFFC53030), Color(0xFFDD6B20), Color(0xFFD69E2E)),
            icon = Icons.Rounded.FitnessCenter,
            subtitle = "High Power Beats"
        )
        else -> {
            // Deterministic gradient and icon fallback for novel YouTube Music categories
            val hash = genreName.hashCode().absoluteValue
            val fallbackPalettes = listOf(
                listOf(Color(0xFF3182CE), Color(0xFF4299E1), Color(0xFF63B3ED)),
                listOf(Color(0xFFD69E2E), Color(0xFFECC94B), Color(0xFFFAF089)),
                listOf(Color(0xFF38A169), Color(0xFF48BB78), Color(0xFF68D391)),
                listOf(Color(0xFF805AD5), Color(0xFF9F7AEA), Color(0xFFB794F4)),
                listOf(Color(0xFFE53E3E), Color(0xFFF56565), Color(0xFFFEB2B2)),
                listOf(Color(0xFFDD6B20), Color(0xFFED8936), Color(0xFFFBD38D)),
                listOf(Color(0xFF319795), Color(0xFF38B2AC), Color(0xFF4FD1C5)),
                listOf(Color(0xFFD53F8C), Color(0xFFED64A6), Color(0xFFFBB6CE))
            )
            val fallbackIcons = listOf(
                Icons.Rounded.MusicNote,
                Icons.Rounded.Audiotrack,
                Icons.Rounded.GraphicEq,
                Icons.Rounded.Star,
                Icons.Rounded.AutoAwesome
            )
            GenreVisualTheme(
                gradientColors = fallbackPalettes[hash % fallbackPalettes.size],
                icon = fallbackIcons[hash % fallbackIcons.size],
                subtitle = "Curated Mixes & Hits"
            )
        }
    }
}
