package com.dhruv.status.hub.ui.screens

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhruv.status.hub.data.DownloadRecord
import com.dhruv.status.hub.ui.components.AdBanner
import com.dhruv.status.hub.ui.components.HistoryItem
import com.dhruv.status.hub.ui.viewmodels.DownloadViewModel
import java.text.SimpleDateFormat
import java.util.*

/**
 * RecentDownloadsScreen
 * 
 * Displays already downloaded files.
 * Fixed header color (primaryContainer), removed top spacing,
 * and added hold-to-select multi-deletion with a clear selection cross icon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentDownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = viewModel()
) {
    val context = LocalContext.current
    val allDownloads by viewModel.allDownloads.collectAsState()
    
    // Only show completed downloads
    val completedDownloads = allDownloads.filter { it.status == "COMPLETED" }
    
    // Multi-selection state
    var selectedItems by remember { mutableStateOf(setOf<DownloadRecord>()) }
    val isSelectionMode = selectedItems.isNotEmpty()

    var showDeleteDialog by remember { mutableStateOf<List<DownloadRecord>?>(null) }

    // Intercept back button if in selection mode to clear it
    BackHandler(isSelectionMode) {
        selectedItems = emptySet()
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 4.dp) {
                TopAppBar(
                    title = {
                        if (isSelectionMode) {
                            Text("${selectedItems.size} Selected", fontWeight = FontWeight.Bold)
                        } else {
                            Text(
                                text = "Downloads",
                                fontWeight = FontWeight.ExtraBold,
                                fontSize = 24.sp
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (isSelectionMode) selectedItems = emptySet()
                            else onBack()
                        }) {
                            Icon(
                                imageVector = if (isSelectionMode) Icons.Default.Close else Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = if (isSelectionMode) "Clear Selection" else "Back"
                            )
                        }
                    },
                    actions = {
                        if (isSelectionMode) {
                            IconButton(onClick = { showDeleteDialog = selectedItems.toList() }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
                            }
                        }
                    },
                    // Changed to primaryContainer to match the Home screen header style
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Box(modifier = Modifier.weight(1f)) {
                if (completedDownloads.isEmpty()) {
                    EmptyHistoryContent(modifier = Modifier.fillMaxSize())
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                        // Top padding is 0 to remove space before the first date header
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 0.dp)
                    ) {
                        // Group downloads by date (Today, Yesterday, etc.)
                        val grouped = groupHistory(completedDownloads)
                        grouped.forEach { (section, records) ->
                            item {
                                Text(
                                    text = section,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.primary,
                                    // Minimized padding to pull it closer to the TopAppBar
                                    modifier = Modifier.padding(top = 8.dp, bottom = 8.dp)
                                )
                            }
                            items(records, key = { it.id }) { record ->
                                val isSelected = selectedItems.contains(record)
                                HistoryItem(
                                    record = record,
                                    isSelected = isSelected,
                                    isSelectionMode = isSelectionMode,
                                    onOpen = { 
                                        if (isSelectionMode) {
                                            selectedItems = if (isSelected) selectedItems - record else selectedItems + record
                                        } else {
                                            openFile(context, record.fileUri ?: "") 
                                        }
                                    },
                                    onLongClick = {
                                        if (!isSelectionMode) {
                                            selectedItems = setOf(record)
                                        } else {
                                            selectedItems = if (isSelected) selectedItems - record else selectedItems + record
                                        }
                                    },
                                    onShare = { shareFile(context, record.fileUri ?: "") },
                                    onDelete = { showDeleteDialog = listOf(record) }
                                )
                            }
                        }
                    }
                }
            }
            AdBanner()
        }
    }

    showDeleteDialog?.let { recordsToDelete ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(if (recordsToDelete.size > 1) "Delete ${recordsToDelete.size} Items" else "Delete Download", fontWeight = FontWeight.Bold) },
            text = { Text(if (recordsToDelete.size > 1) "Are you sure you want to delete these items?" else "Would you like to delete only the history record or also delete the file '${recordsToDelete[0].fileName}' from your device?") },
            confirmButton = {
                Button(
                    onClick = {
                        recordsToDelete.forEach { viewModel.deleteFileAndRecord(context, it) }
                        selectedItems = emptySet()
                        showDeleteDialog = null
                        Toast.makeText(context, "${recordsToDelete.size} items deleted", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(50)
                ) {
                    Text("Delete Files & History")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDeleteDialog = null }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            recordsToDelete.forEach { viewModel.deleteRecord(it) }
                            selectedItems = emptySet()
                            showDeleteDialog = null
                            Toast.makeText(context, "Records removed from history", Toast.LENGTH_SHORT).show()
                        }
                    ) {
                        Text("Remove from History")
                    }
                }
            }
        )
    }
}

@Composable
private fun EmptyHistoryContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Download, 
                contentDescription = null, 
                modifier = Modifier.size(64.dp), 
                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "No downloads found", 
                color = MaterialTheme.colorScheme.outline, 
                fontWeight = FontWeight.Medium
            )
        }
    }
}

private fun groupHistory(records: List<DownloadRecord>): Map<String, List<DownloadRecord>> {
    val today = Calendar.getInstance().apply { 
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0) 
    }.timeInMillis
    val yesterday = today - DateUtils.DAY_IN_MILLIS

    return records.groupBy { record ->
        when {
            record.timestamp >= today -> "Today"
            record.timestamp >= yesterday -> "Yesterday"
            else -> SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(record.timestamp))
        }
    }
}

private fun openFile(context: Context, uriString: String) {
    try {
        val uri = uriString.toUri()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, uriString: String) {
    try {
        val uri = uriString.toUri()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = context.contentResolver.getType(uri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Media"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share file", Toast.LENGTH_SHORT).show()
    }
}
