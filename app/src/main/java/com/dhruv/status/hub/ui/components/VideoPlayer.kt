package com.dhruv.status.hub.ui.components

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

/**
 * VideoPlayer Composable
 * 
 * Optimized to handle video playback.
 * Syncs playback state with the [autoPlay] parameter to ensure only the visible pager item plays.
 */
@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true
) {
    val context = LocalContext.current

    val exoPlayer = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            // neighbor pre-loaded items should not start playing automatically
            playWhenReady = false 
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }

    // React to pager visibility changes
    LaunchedEffect(autoPlay) {
        exoPlayer.playWhenReady = autoPlay
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                controllerAutoShow = true
                showController()
            }
        },
        modifier = modifier,
        update = { playerView ->
            playerView.player = exoPlayer
        }
    )
}
