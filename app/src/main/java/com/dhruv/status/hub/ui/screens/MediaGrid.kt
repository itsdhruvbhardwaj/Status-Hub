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
import androidx.compose.material3.Icon
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
        items(mediaList) { uri ->
            val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true ||
                    uri.toString().contains(".mp4", true)
            val isSelected = selectedItems.contains(uri)

            Box(
                modifier = Modifier
                    .padding(4.dp)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .combinedClickable(
                        onClick = { onItemClick(uri) },
                        onLongClick = { onItemLongClick(uri) }
                    )
            ) {
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

                if (isVideo) {
                    Text(
                        text = "▶",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }

                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.4f))
                    )
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Selected",
                        tint = Color.Black,
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
