package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import com.example.data.repository.ArtistPhotoRepository
import com.example.data.repository.CatalogArtist
import com.example.ui.theme.XtremeLightBlue

@Composable
fun ArtistPortrait(artist: CatalogArtist, modifier: Modifier = Modifier, fallbackArtwork: String? = null) {
    val context = LocalContext.current.applicationContext
    val photo by produceState<String?>(null, artist.name, artist.musicBrainzId, artist.id, fallbackArtwork) {
        value = null
        value = ArtistPhotoRepository.photo(context, artist, fallbackArtwork)
    }
    var photoFailed by remember(photo) { mutableStateOf(false) }
    Box(modifier.background(Color(0xFF163255)), contentAlignment = Alignment.Center) {
        Icon(Icons.Default.Person, contentDescription = null, tint = XtremeLightBlue, modifier = Modifier.fillMaxSize(0.55f))
        AsyncImage(model = if (photoFailed) fallbackArtwork else photo ?: fallbackArtwork,
            contentDescription = artist.name, contentScale = ContentScale.Crop,
            onError = { photoFailed = true }, modifier = Modifier.fillMaxSize())
    }
}
