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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeTopBar(
    isSelectionMode: Boolean,
    selectedCount: Int,
    onSettingsClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Surface(shadowElevation = 4.dp) {
        TopAppBar(
            title = {
                if (isSelectionMode) {
                    Text("$selectedCount Selected", fontWeight = FontWeight.Bold)
                } else {
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
                actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )
    }
}
