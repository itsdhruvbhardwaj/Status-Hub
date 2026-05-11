package com.dhruv.status.hub.ui.components

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dhruv.status.hub.R
import com.dhruv.status.hub.utils.downloadMedia
import com.dhruv.status.hub.utils.findActivity

/**
 * MediaPreviewer Composable
 * 
 * Provides a full-screen pager to preview images and videos from a list.
 * Includes a back button and an optional download button that triggers an interstitial ad.
 * 
 * @param selectedMedia The URI of the media item that should be shown first.
 * @param mediaList The full list of media URIs to swipe through.
 * @param onClose Callback to exit the previewer.
 * @param adManager Manager to handle showing ads before downloading.
 * @param showDownloadButton Whether to display the download action button.
 */
@Composable
fun MediaPreviewer(
    selectedMedia: Uri,
    mediaList: List<Uri>,
    onClose: () -> Unit,
    adManager: InterstitialAdManager,
    showDownloadButton: Boolean = true
) {
    val context = LocalContext.current
    // Find the starting index based on the selected media URI
    val currentIndex = mediaList.indexOf(selectedMedia).coerceAtLeast(0)
    // State for the horizontal pager
    val pagerState = rememberPagerState(
        initialPage = currentIndex,
        pageCount = { mediaList.size }
    )

    // Intercept back button to close the previewer
    BackHandler { onClose() }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Full-screen horizontal pager for swiping between media items
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val itemUri = mediaList[page]
            // Determine item type for rendering (Image vs Video)
            val isItemVideo = context.contentResolver.getType(itemUri)?.startsWith("video") == true || 
                             itemUri.toString().lowercase().contains(".mp4")

            Box(modifier = Modifier.fillMaxSize()) {
                if (isItemVideo) {
                    // Use custom VideoPlayer for video content
                    VideoPlayer(uri = itemUri, modifier = Modifier.fillMaxSize())
                } else {
                    // Use AsyncImage for static images
                    AsyncImage(model = itemUri, contentDescription = null, modifier = Modifier.fillMaxSize())
                }

                // Floating download button with ad integration
                if (showDownloadButton) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 24.dp, bottom = 80.dp)
                            .size(56.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.7f))
                            .clickable {
                                val activity = context.findActivity()
                                // Show ad first, then download the media
                                adManager.showAd(activity) {
                                    downloadMedia(context, itemUri)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_download),
                            contentDescription = "Download", 
                            tint = Color.White, 
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }
        }

        // Top-left back button overlay
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
