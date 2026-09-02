package com.dhruv.status.hub.ui.screens

import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.dhruv.status.hub.data.DownloadRecord
import com.dhruv.status.hub.ui.components.AdBanner
import com.dhruv.status.hub.ui.viewmodels.DownloadViewModel
import com.dhruv.status.hub.utils.DownloadManager
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentDownloadsScreen(
    onBack: () -> Unit,
    viewModel: DownloadViewModel = viewModel()
) {
    val context = LocalContext.current
    val allDownloads by viewModel.allDownloads.collectAsState()
    val speeds by DownloadManager.downloadSpeeds.collectAsState()
    
    val activeDownloads = allDownloads.filter { it.status == "DOWNLOADING" }
    val pausedDownloads = allDownloads.filter { it.status == "PAUSED" }
    val queuedDownloads = allDownloads.filter { it.status == "QUEUED" }
    val failedDownloads = allDownloads.filter { it.status == "FAILED" }
    val completedDownloads = allDownloads.filter { it.status == "COMPLETED" }
    
    var showDeleteDialog by remember { mutableStateOf<DownloadRecord?>(null) }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 4.dp) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Downloads",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Cursive,
                            fontSize = 28.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                )
            }
        },
        bottomBar = {
            AdBanner()
        }
    ) { innerPadding ->
        if (allDownloads.isEmpty()) {
            EmptyHistoryContent(modifier = Modifier.padding(innerPadding))
        } else {
            LazyColumn(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp)
            ) {
                // ACTIVE Section
                if (activeDownloads.isNotEmpty() || pausedDownloads.isNotEmpty() || failedDownloads.isNotEmpty()) {
                    item {
                        Text(
                            text = "ACTIVE",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(activeDownloads + pausedDownloads + failedDownloads, key = { it.id }) { record ->
                        ActiveDownloadItem(
                            record = record,
                            speed = speeds[record.id] ?: 0L,
                            onPause = { viewModel.pauseDownload(context, record.id) },
                            onResume = { viewModel.resumeDownload(context, record.id) },
                            onCancel = { viewModel.cancelDownload(context, record.id) }
                        )
                    }
                }

                // QUEUED Section
                if (queuedDownloads.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "QUEUED",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(queuedDownloads, key = { it.id }) { record ->
                        ActiveDownloadItem(
                            record = record,
                            speed = 0L,
                            onPause = {},
                            onResume = {},
                            onCancel = { viewModel.cancelDownload(context, record.id) }
                        )
                    }
                }

                // COMPLETED Section
                if (completedDownloads.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "COMPLETED",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    
                    val grouped = groupHistory(completedDownloads)
                    grouped.forEach { (section, records) ->
                        item {
                            Text(
                                text = section,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(records, key = { it.id }) { record ->
                            HistoryItem(
                                record = record,
                                onOpen = { openFile(context, record.fileUri ?: "") },
                                onShare = { shareFile(context, record.fileUri ?: "") },
                                onDelete = { showDeleteDialog = record }
                            )
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(100.dp)) }
            }
        }
    }

    showDeleteDialog?.let { record ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Download") },
            text = { Text("Would you like to delete only the history record or also delete the file '${record.fileName}' from your device?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFileAndRecord(context, record)
                        showDeleteDialog = null
                        Toast.makeText(context, "File and record deleted", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Delete File & History", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showDeleteDialog = null }) {
                        Text("Cancel")
                    }
                    TextButton(
                        onClick = {
                            viewModel.deleteRecord(record)
                            showDeleteDialog = null
                            Toast.makeText(context, "Record removed from history", Toast.LENGTH_SHORT).show()
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
fun ActiveDownloadItem(
    record: DownloadRecord,
    speed: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    val isQueued = record.status == "QUEUED"
    val isFailed = record.status == "FAILED"
    val isPaused = record.status == "PAUSED"
    val progress = if (record.totalBytes > 0) record.downloadedBytes.toFloat() / record.totalBytes else 0f
    val percentText = if (record.totalBytes > 0) "${(progress * 100).toInt()}%" else "..."
    
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isQueued) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        ),
        border = if (isQueued) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)) else null
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Thumbnail
                Box(
                    modifier = Modifier.size(50.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (!record.thumbnailUrl.isNullOrEmpty()) {
                        AsyncImage(
                            model = record.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = if (isQueued) Icons.Default.HourglassEmpty
                            else if (record.mediaType == "audio") Icons.Default.MusicNote 
                            else Icons.Default.PlayArrow,
                            contentDescription = null,
                            tint = if (isQueued) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                
                Spacer(Modifier.width(12.dp))
                
                Column(modifier = Modifier.weight(1f)) {
                    Text(record.fileName, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                    Text(
                        text = if (isQueued) "Waiting for connection..." 
                               else "${record.quality} • ${record.format.uppercase()}", 
                        fontSize = 12.sp, 
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                if (record.status == "DOWNLOADING") {
                    IconButton(onClick = onPause) { Icon(Icons.Default.Pause, null) }
                } else if (isPaused || isQueued || isFailed) {
                    IconButton(onClick = onResume) { Icon(Icons.Default.PlayArrow, null) }
                }
                IconButton(onClick = onCancel) { Icon(Icons.Default.Close, null, tint = MaterialTheme.colorScheme.error) }
            }

            Spacer(Modifier.height(16.dp))
            
            if (isQueued) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(5.dp)),
                        color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = percentText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isFailed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            Spacer(Modifier.height(12.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(
                        text = if (isQueued) "Queued" 
                               else "${formatFileSize(record.downloadedBytes)} / ${formatFileSize(record.totalBytes)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isFailed) {
                        Text(
                            text = record.errorMessage ?: "Download failed",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                
                if (record.status == "DOWNLOADING" && speed > 0) {
                    val remainingBytes = record.totalBytes - record.downloadedBytes
                    val eta = if (remainingBytes > 0) remainingBytes / speed else 0L
                    
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = "Speed: ${formatFileSize(speed)}/s",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = if (eta > 0) "Remaining: ~${formatEta(eta)}" else "Finishing...",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryItem(
    record: DownloadRecord,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        onClick = onOpen,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                if (!record.thumbnailUrl.isNullOrEmpty()) {
                    AsyncImage(
                        model = record.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (record.mediaType == "audio") Icons.Default.AudioFile else Icons.Default.VideoFile,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(record.fileName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${record.quality} • ${formatFileSize(record.totalBytes)} • ${record.format.uppercase()}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.DeleteOutline, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun EmptyHistoryContent(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(16.dp))
            Text("No downloads found", color = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun groupHistory(records: List<DownloadRecord>): Map<String, List<DownloadRecord>> {
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val yesterday = today - DateUtils.DAY_IN_MILLIS
    return records.groupBy { record ->
        when {
            record.timestamp >= today -> "Today"
            record.timestamp >= yesterday -> "Yesterday"
            else -> SimpleDateFormat("MMMM dd, yyyy", Locale.getDefault()).format(Date(record.timestamp))
        }
    }
}

private fun formatFileSize(size: Long): String {
    if (size <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    val digitGroups = (Math.log10(size.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, units.size - 1)
    return "%.1f %s".format(size / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
}

private fun formatEta(seconds: Long): String {
    return if (seconds >= 3600) {
        "%d:%02d:%02d".format(seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        "%d:%02d".format(seconds / 60, seconds % 60)
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
