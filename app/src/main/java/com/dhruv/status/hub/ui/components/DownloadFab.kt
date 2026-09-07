package com.dhruv.status.hub.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * A modern Floating Action Button for the "Download from Link" feature.
 * Starts as a circle, smoothly expands into an oval showing full text for 3 seconds,
 * and then retracts back to a circle.
 *
 * Shadow Fix: Synchronized the shadow by making it an outer modifier to animateContentSize.
 * This ensures the shadow receives the animated size on every frame during retraction,
 * eliminating the lag/delay while maintaining perfect smoothness with clip = false.
 */
@Composable
fun DownloadFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            delay(1200) // Initial stability delay
            isExpanded = true
            delay(3000) // Stay expanded for 3 seconds
            isExpanded = false
        } else {
            isExpanded = false
        }
    }

    // Using CircleShape ensures it remains a perfect capsule/circle at any width during animation.
    val fabShape = CircleShape

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(transformOrigin = TransformOrigin(1f, 1f)),
        exit = fadeOut() + scaleOut(transformOrigin = TransformOrigin(1f, 1f)),
        modifier = modifier
    ) {
        // Internal padding provides 'bleed room' for the soft shadow blur.
        Box(
            modifier = Modifier
                .padding(16.dp)
                .height(56.dp)
                .widthIn(min = 56.dp)
                // ORDER IS CRITICAL: shadow must be OUTER to animateContentSize to follow animated measurements.
                .shadow(
                    elevation = 10.dp,
                    shape = fabShape,
                    clip = false // Key for soft, rounded blur edges
                )
                .animateContentSize(
                    alignment = Alignment.CenterEnd,
                    animationSpec = spring(
                        dampingRatio = Spring.DampingRatioNoBouncy,
                        stiffness = Spring.StiffnessHigh // Ensure measurement updates are snappy
                    )
                )
                .background(MaterialTheme.colorScheme.primary, fabShape)
                .clip(fabShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = ripple(),
                    onClick = onClick
                ),
            contentAlignment = Alignment.CenterEnd
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                // Expanding text label
                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
                    exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
                ) {
                    Text(
                        text = "Download from Link",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        softWrap = false,
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }

                // Stationary Link Icon
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = "Download Link",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}
