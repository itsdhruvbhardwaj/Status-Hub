package com.dhruv.status.hub.ui.components

import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.dhruv.status.hub.utils.FileUtils
import com.dhruv.status.hub.utils.isFavorite
import com.dhruv.status.hub.utils.toggleFavorite

private const val TAG = "DownloadedMediaPreviewer"

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

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val itemUri = mediaList[page]

            val mimeType = remember(itemUri) {
                context.contentResolver.getType(itemUri)?.lowercase() ?: ""
            }

            val isImage = mimeType.startsWith("image/")
            val isMimeAudio = mimeType.startsWith("audio/")

            val isActuallyVideo = remember(itemUri, mimeType) {
                if (isImage || isMimeAudio) false
                else FileUtils.isActuallyVideo(context, itemUri)
            }

            val isAudio = !isImage && (isMimeAudio || !isActuallyVideo)

            Log.d(TAG, "Player selection: uri=$itemUri mime=$mimeType isImage=$isImage isVideo=$isActuallyVideo isAudio=$isAudio")

            // Determine if this specific page is currently visible to the user
            val isCurrentPage = pagerState.currentPage == page

            when {
                isActuallyVideo -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        VideoPlayer(
                            uri = itemUri,
                            modifier = Modifier.weight(1f),
                            autoPlay = isCurrentPage // Only play if it's the active page
                        )
                        Spacer(modifier = Modifier.navigationBarsPadding().height(60.dp))
                    }
                }

                isAudio -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                shape = CircleShape,
                                modifier = Modifier.size(140.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MusicNote,
                                    contentDescription = "Audio",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(38.dp)
                                )
                            }

                            Spacer(modifier = Modifier.height(24.dp))

                            val fileName = DocumentFile.fromSingleUri(context, itemUri)?.name ?: "Audio File"

                            Text(
                                text = fileName,
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 20.dp),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(modifier = Modifier.height(36.dp))

                            AudioPlayer(
                                uri = itemUri,
                                modifier = Modifier.fillMaxWidth(),
                                autoPlay = isCurrentPage // Only play if it's the active page
                            )
                        }
                    }
                }

                else -> {
                    Box(
                        modifier = Modifier.fillMaxSize().clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showControls = !showControls },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = itemUri,
                            contentDescription = "Image preview",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        val currentUri = mediaList[pagerState.currentPage.coerceIn(0, mediaList.lastIndex)]
        val currentMime = context.contentResolver.getType(currentUri)?.lowercase() ?: ""
        val currentIsImage = currentMime.startsWith("image/")
        val currentIsPlaybackMedia = !currentIsImage

        val barBackground by animateColorAsState(
            targetValue = if (currentIsPlaybackMedia) Color.Black else Color.Black.copy(alpha = 0.4f),
            label = "barBackground"
        )

        AnimatedVisibility(
            visible = showControls || currentIsPlaybackMedia,
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
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }

        AnimatedVisibility(
            visible = showControls || currentIsPlaybackMedia,
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
        val currentUri = mediaList[pagerState.currentPage.coerceIn(0, mediaList.lastIndex)]
        Dialog(onDismissRequest = { showDeleteDialog = false }) {
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)
            ) {
                Column(modifier = Modifier.padding(top = 24.dp, start = 24.dp, end = 24.dp, bottom = 16.dp)) {
                    Text(
                        text = "Delete this file permanently?",
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { showDeleteDialog = false }) {
                            Text(
                                text = "Cancel",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                fontSize = 16.sp
                            )
                        }
                        Button(
                            onClick = {
                                showDeleteDialog = false
                                onDelete(currentUri)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(text = "Delete", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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
    var isFavorited by remember(uri) {
        mutableStateOf(isFavorite(context, uri.toString()))
    }

    Row(
        modifier = Modifier.fillMaxWidth().background(background).navigationBarsPadding(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = context.contentResolver.getType(uri) ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
            }
        ) {
            Icon(Icons.Default.Share, contentDescription = "Share", tint = Color.White)
        }

        IconButton(
            onClick = {
                toggleFavorite(context, uri.toString())
                isFavorited = !isFavorited
            }
        ) {
            Icon(
                imageVector = if (isFavorited) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "Favorite",
                tint = if (isFavorited) Color.Red else Color.White
            )
        }

        IconButton(onClick = onDeleteClick) {
            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.White)
        }
    }
}
