package com.dhruv.status.hub.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage

@Composable
fun DownloadedMediaPreviewer(
    selectedMedia: Uri,
    mediaList: List<Uri>,
    onClose: () -> Unit,
    onDelete: (Uri) -> Unit
) {
    val context = LocalContext.current
    val currentIndex = mediaList.indexOf(selectedMedia).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { mediaList.size }
    )

    var showControls by remember { mutableStateOf(true) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    BackHandler { onClose() }

    // Unified Layout to prevent sticking/jumping during swipes
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            val itemUri = mediaList[page]
            val isVideo = context.contentResolver.getType(itemUri)?.startsWith("video") == true ||
                    itemUri.toString().lowercase().contains(".mp4")

            if (isVideo) {
                Column(modifier = Modifier.fillMaxSize()) {
                    VideoPlayer(uri = itemUri, modifier = Modifier.weight(1f))
                    // This spacer reserves space at the bottom so the video controls 
                    // (seek bar, etc) never overlap with the action bar.
                    Spacer(modifier = Modifier.navigationBarsPadding().height(48.dp))
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showControls = !showControls },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = itemUri,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        // --- Overlays ---

        val currentUri = mediaList[pagerState.currentPage]
        val isCurrentVideo = context.contentResolver.getType(currentUri)?.startsWith("video") == true ||
                currentUri.toString().lowercase().contains(".mp4")

        // Dynamic background color for the Action Bar
        val barBackground by animateColorAsState(
            targetValue = if (isCurrentVideo) Color.Black else Color.Black.copy(alpha = 0.4f),
            label = "barBackground"
        )

        // Top Back Button
        AnimatedVisibility(
            visible = showControls || isCurrentVideo,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
            IconButton(
                onClick = { onClose() },
                modifier = Modifier
                    .padding(top = 16.dp, start = 16.dp)
                    .statusBarsPadding()
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .size(48.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
            }
        }

        // Bottom Action Bar
        AnimatedVisibility(
            visible = showControls || isCurrentVideo,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ActionBar(
                uri = currentUri,
                onDeleteClick = { showDeleteDialog = true },
                background = barBackground
            )
        }
    }

    if (showDeleteDialog) {
        val currentUri = mediaList[pagerState.currentPage]
        val isCurrentVideo = context.contentResolver.getType(currentUri)?.startsWith("video") == true ||
                currentUri.toString().lowercase().contains(".mp4")

        Dialog(onDismissRequest = { showDeleteDialog = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 16.dp)
                ) {
                    Text(
                        text = "Delete this ${if (isCurrentVideo) "video" else "image"}?",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Normal
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text("Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), fontSize = 16.sp)
                        }
                        
                        Button(
                            onClick = {
                                showDeleteDialog = false
                                onDelete(currentUri)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ),
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                        ) {
                            Text("Delete", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ActionBar(
    uri: Uri,
    onDeleteClick: () -> Unit,
    background: Color
) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = context.contentResolver.getType(uri) ?: "image/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share Status"))
        }) {
            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
        }

        IconButton(onClick = onDeleteClick) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
        }
    }
}
