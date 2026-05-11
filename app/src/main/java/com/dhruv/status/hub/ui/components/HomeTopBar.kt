package com.dhruv.status.hub.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * HomeTopBar Composable
 * 
 * The top app bar for the main screen. It adapts its content based on whether
 * the user is in "Selection Mode" (e.g., when deleting downloaded items).
 * 
 * @param isSelectionMode Whether multi-selection is active.
 * @param selectedCount The number of items currently selected.
 * @param onSettingsClick Callback for the settings icon button.
 * @param onDeleteClick Callback for the delete icon button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    isSelectionMode: Boolean,
    selectedCount: Int,
    onSettingsClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    // Elevate the top bar for a visual shadow effect
    Surface(shadowElevation = 4.dp) {
        TopAppBar(
            title = {
                if (isSelectionMode) {
                    // Show selection count if in selection mode
                    Text("$selectedCount Selected", fontWeight = FontWeight.Bold)
                } else {
                    // Show app name in cursive font otherwise
                    Text(
                        text = "Status Hub",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Cursive,
                        fontSize = 28.sp
                    )
                }
            },
            actions = {
                if (isSelectionMode) {
                    // Show delete icon during selection
                    IconButton(onClick = onDeleteClick) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                } else {
                    // Show settings icon normally
                    IconButton(
                        onClick = onSettingsClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
    }
}
