package com.dhruv.status.hub.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * StatusBottomBar Composable
 * 
 * A custom pill-shaped bottom navigation bar that allows users to switch
 * between "Images", "Videos", and "Downloads" tabs.
 * 
 * @param selectedTab The index of the currently active tab.
 * @param tabs List of strings representing tab titles.
 * @param onTabSelected Callback invoked when a tab is clicked.
 */
@Composable
fun StatusBottomBar(
    selectedTab: Int,
    tabs: List<String>,
    onTabSelected: (Int) -> Unit
) {
    // Outer container with shadow and rounded corners for a floating effect
    Row(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 20.dp)
            .fillMaxWidth()
            .shadow(12.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .padding(6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        tabs.forEachIndexed { index, title ->
            val isSelected = selectedTab == index
            // Highlight color for the selected tab
            val bgColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
            
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(color = bgColor, shape = RoundedCornerShape(50))
                    .clickable(
                        // Remove default ripple/indication for a cleaner look
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) {
                        onTabSelected(index)
                    }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = title,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}
