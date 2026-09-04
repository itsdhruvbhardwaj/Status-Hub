package com.dhruv.status.hub.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
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
 * the user is in "Selection Mode" or viewing a specific folder.
 * 
 * @param title The title to display.
 * @param isSelectionMode Whether multi-selection is active.
 * @param selectedCount The number of items currently selected.
 * @param onMenuClick Callback for the hamburger menu icon.
 * @param onBackClick Optional callback for a back navigation icon.
 * @param onSettingsClick Callback for the settings icon button.
 * @param onDeleteClick Callback for the delete icon button.
 * @param onClearSelection Callback to clear current selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    title: String = "Status Hub",
    isSelectionMode: Boolean,
    selectedCount: Int,
    onMenuClick: () -> Unit,
    onBackClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onClearSelection: () -> Unit = {}
) {
    Surface(shadowElevation = 4.dp) {
        TopAppBar(
            title = {
                if (isSelectionMode) {
                    Text("$selectedCount Selected", fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        text = title,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 24.sp
                    )
                }
            },
            navigationIcon = {
                if (isSelectionMode) {
                    IconButton(onClick = onClearSelection) {
                        Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                    }
                } else if (onBackClick != null) {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                } else {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Menu")
                    }
                }
            },
            actions = {
                if (isSelectionMode) {
                    IconButton(onClick = onDeleteClick) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                    }
                } else {
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
                navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
    }
}
