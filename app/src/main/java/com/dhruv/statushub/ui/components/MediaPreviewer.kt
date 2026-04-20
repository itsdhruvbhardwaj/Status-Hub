package com.dhruv.statushub.ui.components

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dhruv.statushub.utils.downloadMedia
import com.dhruv.statushub.utils.findActivity

@Composable
fun MediaPreviewer(
    selectedMedia: Uri,
    mediaList: List<Uri>,
    onClose: () -> Unit,
    adManager: InterstitialAdManager,
    showDownloadButton: Boolean = true
) {
    val context = LocalContext.current
    val currentIndex = mediaList.indexOf(selectedMedia).coerceAtLeast(0)
    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { mediaList.size }
    )

    BackHandler { onClose() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val itemUri = mediaList[page]
            val isItemVideo = context.contentResolver.getType(itemUri)?.startsWith("video") == true || 
                             itemUri.toString().lowercase().contains(".mp4")

            Box(modifier = Modifier.fillMaxSize()) {
                if (isItemVideo) {
                    VideoPlayer(uri = itemUri, modifier = Modifier.fillMaxSize())
                } else {
                    AsyncImage(model = itemUri, contentDescription = null, modifier = Modifier.fillMaxSize())
                }

                if (showDownloadButton) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 40.dp)
                            .size(56.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .clickable {
                                val activity = context.findActivity()
                                adManager.showAd(activity) {
                                    downloadMedia(context, itemUri)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(28.dp))
                    }
                }
            }
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
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}