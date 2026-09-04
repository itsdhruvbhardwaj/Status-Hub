package com.dhruv.status.hub.ui.components

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerControlView

@OptIn(UnstableApi::class)
@Composable
fun AudioPlayer(
    uri: Uri,
    modifier: Modifier = Modifier,
    autoPlay: Boolean = true
) {

    val context =
        androidx.compose.ui.platform.LocalContext.current

    val exoPlayer =
        remember(uri) {

            ExoPlayer
                .Builder(context)
                .build()
                .apply {

                    setMediaItem(
                        MediaItem.fromUri(uri)
                    )

                    repeatMode =
                        Player.REPEAT_MODE_OFF

                    prepare()
                }
        }

    // Sync playWhenReady with the autoPlay parameter to stop audio when swiped away
    LaunchedEffect(autoPlay) {
        exoPlayer.playWhenReady = autoPlay
    }

    DisposableEffect(exoPlayer) {

        onDispose {
            exoPlayer.release()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface
            )
            .padding(
                horizontal = 16.dp,
                vertical = 12.dp
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
                Arrangement.Center,
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Icon(
                imageVector =
                    Icons.Default.MusicNote,
                contentDescription =
                    "Audio",
                tint =
                    MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.height(36.dp)
            )
        }

        AndroidView(
            factory = { ctx ->

                PlayerControlView(ctx).apply {

                    player = exoPlayer

                    showTimeoutMs = 0

                    setShowPreviousButton(false)
                    setShowNextButton(false)

                    setShowRewindButton(true)
                    setShowFastForwardButton(true)

                    setShowShuffleButton(false)

                    setShowSubtitleButton(false)

                    setShowVrButton(false)
                }
            },
            update = { controlView ->

                controlView.player =
                    exoPlayer
            },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(64.dp)
        )
    }
}