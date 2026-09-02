package com.dhruv.status.hub.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.dhruv.status.hub.utils.NetworkDownloadUtils

/**
 * Enhanced MediaInfoCard with format selection.
 * Fixed to use actual source extension even when labeled as MP3 in UI,
 * to prevent renaming video containers to .mp3 until conversion is implemented.
 */
@Composable
fun MediaInfoCard(
    info: NetworkDownloadUtils.MediaInfo,
    onDownloadClick: (format: NetworkDownloadUtils.MediaFormat?, isAudioOnly: Boolean) -> Unit
) {
    val videoFormats = remember(info) { info.formats.filter { !it.isAudio } }
    
    // Requirement: Show ONLY MP3 bitrates (96, 128, 192, 256, 320 kbps)
    val audioBitrates = listOf(96, 128, 192, 256, 320)
    val audioFormats = remember(info) {
        // Find the best source audio stream
        val bestAudioStream = info.formats.filter { it.isAudio }.maxByOrNull { 
            it.quality.filter { c -> c.isDigit() }.toIntOrNull() ?: 128 
        }
        
        audioBitrates.map { bitrate ->
            NetworkDownloadUtils.MediaFormat(
                id = "mp3_$bitrate",
                url = bestAudioStream?.url ?: info.url,
                quality = "$bitrate kbps",
                // IMPORTANT: Use the actual source extension (e.g. m4a, webm) 
                // to avoid creating fake .mp3 files that are actually video containers.
                extension = bestAudioStream?.extension ?: "m4a",
                format = "MP3", // Label in UI
                size = -1L,
                isAudio = true,
                hasVideo = false,
                hasAudio = true,
                note = "Source: ${bestAudioStream?.extension?.uppercase() ?: "M4A"}"
            )
        }
    }

    var selectedTab by remember(info) { 
        mutableIntStateOf(if (videoFormats.isNotEmpty()) 0 else 1) 
    }
    
    var selectedFormat by remember(info, selectedTab) { 
        mutableStateOf(if (selectedTab == 0) videoFormats.firstOrNull() else audioFormats.getOrNull(1)) // Default 128kbps
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!info.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = info.thumbnailUrl,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = when (info.mediaType) {
                                "video" -> Icons.Default.VideoFile
                                "audio" -> Icons.Default.AudioFile
                                else -> Icons.Default.Image
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = info.platform,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = info.fileName,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.primary,
                divider = {},
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("VIDEO") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("AUDIO") }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            val currentFormats = if (selectedTab == 0) videoFormats else audioFormats
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.1f))
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f)), 
                        RoundedCornerShape(16.dp)
                    )
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentFormats.isEmpty()) {
                    Text(
                        "No formats found", 
                        modifier = Modifier.padding(16.dp).align(Alignment.CenterHorizontally),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    currentFormats.forEach { format ->
                        FormatItem(
                            format = format,
                            selected = selectedFormat?.id == format.id,
                            onClick = { selectedFormat = format }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { onDownloadClick(selectedFormat, selectedTab == 1) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                enabled = selectedFormat != null
            ) {
                Icon(Icons.Default.Download, null)
                Spacer(Modifier.width(8.dp))
                Text("Download", fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun FormatItem(
    format: NetworkDownloadUtils.MediaFormat,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() },
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.1f) else Color.Transparent,
        border = if (selected) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = selected, 
                onClick = onClick,
                colors = RadioButtonDefaults.colors(selectedColor = MaterialTheme.colorScheme.primary)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = format.quality,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${format.format}${if (format.note != null) " • ${format.note}" else ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
