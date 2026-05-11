package com.dhruv.status.hub.ui.components

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * VideoPlayer Composable
 * 
 * A wrapper around Media3 ExoPlayer and PlayerView to play video content in Compose.
 * 
 * @param uri The URI of the video to be played.
 * @param modifier Modifier for sizing and layout.
 */
@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Initialize ExoPlayer and prepare it with the provided media URI
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            // Don't start playing automatically
            playWhenReady = false
        }
    }

    // Integrate the traditional Android View (PlayerView) into the Compose hierarchy
    DisposableEffect(
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    player = exoPlayer
                }
            },
            modifier = modifier
        )
    ) {
        // Clean up resources when the Composable is removed from the composition
        onDispose {
            exoPlayer.release()
        }
    }
}
