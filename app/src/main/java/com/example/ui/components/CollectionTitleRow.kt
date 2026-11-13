package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ui.theme.LocalAppColors

@Composable
internal fun CollectionTitleRow(
    title: String, style: TextStyle, tag: String, download: CollectionDownloadAction?
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f).testTag(tag), color = LocalAppColors.current.textPrimary,
            style = style, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (download != null) {
            Spacer(Modifier.width(12.dp))
            CollectionDownloadButton(download)
        }
    }
}
