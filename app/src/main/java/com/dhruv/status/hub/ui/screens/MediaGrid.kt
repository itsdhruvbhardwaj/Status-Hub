package com.dhruv.status.hub.ui.screens

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest

/**
 * MediaGrid Composable
 * 
 * A versatile grid for displaying images, videos, and audios. 
 * Supports multi-selection and clear identification of media types.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    mediaList: List<Uri>,
    selectedItems: Set<Uri>,
    onItemClick: (Uri) -> Unit,
    onItemLongClick: (Uri) -> Unit
) {
    val context = LocalContext.current

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 80.dp, start = 4.dp, end = 4.dp, top = 4.dp)
    ) {
        items(mediaList, key = { it.toString() }) { uri ->
            val mimeType = context.contentResolver.getType(uri) ?: ""
            val isVideo = mimeType.startsWith("video") || uri.toString().lowercase().contains(".mp4")
            val isAudio = mimeType.startsWith("audio") || uri.toString().lowercase().let { it.contains(".mp3") || it.contains(".m4a") }
            val isSelected = selectedItems.contains(uri)

            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickable(
                        onClick = { onItemClick(uri) },
                        onLongClick = { onItemLongClick(uri) }
                    )
            ) {
                if (isAudio) {
                    // Audio Placeholder
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                        }
                    }
                } else {
                    // Image or Video Thumbnail
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uri)
                            .apply {
                                if (isVideo) {
                                    decoderFactory(VideoFrameDecoder.Factory())
                                }
                            }
                            .build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                }

                // Media Type Identification Badge
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    color = Color.Black.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Icon(
                        imageVector = when {
                            isAudio -> Icons.Default.MusicNote
                            isVideo -> Icons.Default.PlayArrow
                            else -> Icons.Default.Image
                        },
                        contentDescription = null,
                        modifier = Modifier.padding(2.dp).size(12.dp),
                        tint = Color.White
                    )
                }

                // Selection Overlay
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                    )
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp)
                            .background(Color.White, CircleShape)
                            .size(22.dp)
                            .clip(CircleShape)
                    )
                }
            }
        }
    }
}
