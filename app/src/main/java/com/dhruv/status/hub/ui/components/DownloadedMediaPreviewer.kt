package com.dhruv.status.hub.ui.components

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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

    val currentUri = mediaList[pagerState.currentPage]
    val isCurrentVideo = context.contentResolver.getType(currentUri)?.startsWith("video") == true ||
            currentUri.toString().lowercase().contains(".mp4")

    if (isCurrentVideo) {
        Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            Box(modifier = Modifier.weight(1f)) {
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    VideoPlayer(uri = mediaList[page], modifier = Modifier.fillMaxSize())
                }

                IconButton(
                    onClick = { onClose() },
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 16.dp, start = 16.dp)
                        .statusBarsPadding()
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                }
            }

            ActionBar(
                uri = currentUri,
                onDeleteClick = { showDeleteDialog = true },
                background = Color.Black
            )
        }
    } else {
        Box(modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = !showControls }
        ) {
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                AsyncImage(
                    model = mediaList[page],
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                )
            }

            AnimatedVisibility(
                visible = showControls,
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

            AnimatedVisibility(
                visible = showControls,
                enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                exit = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                ActionBar(
                    uri = currentUri,
                    onDeleteClick = { showDeleteDialog = true },
                    background = Color.Black.copy(alpha = 0.4f)
                )
            }
        }
    }

    if (showDeleteDialog) {
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
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = { showDeleteDialog = false }
                        ) {
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
