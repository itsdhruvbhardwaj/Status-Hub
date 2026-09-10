package com.dhruv.status.hub.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhruv.status.hub.data.DownloadRecord
import com.dhruv.status.hub.ui.components.ActiveDownloadItem
import com.dhruv.status.hub.ui.components.AdBanner
import com.dhruv.status.hub.ui.components.HistoryItem
import com.dhruv.status.hub.ui.components.HomeTopBar
import com.dhruv.status.hub.ui.components.MediaInfoCard
import com.dhruv.status.hub.ui.viewmodels.DownloadViewModel
import com.dhruv.status.hub.utils.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadFromLinkScreen(
    initialUrl: String? = null,
    onBack: () -> Unit,
    onNavigateToRecentDownloads: () -> Unit,
    onThemeChange: () -> Unit = {},
    viewModel: DownloadViewModel = viewModel()
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initialUrl ?: "") }
    val downloadState by viewModel.downloadState.collectAsState()

    val allDownloads by viewModel.allDownloads.collectAsState()
    val speeds by DownloadManager.downloadSpeeds.collectAsState()

    val activeDownloads = allDownloads.filter { it.status in listOf("QUEUED", "DOWNLOADING", "PAUSED", "FAILED", "PROCESSING") }
    val recentCompleted = allDownloads.filter { it.status == "COMPLETED" }.take(5)

    var deleteRecordConfirmation by remember { mutableStateOf<DownloadRecord?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            url = initialUrl
            viewModel.analyzeUrl(initialUrl)
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                if (clipText.startsWith("http") && url.isEmpty()) {
                    url = clipText.trim()
                }
            }
        }
    }

    BackHandler {
        if (showSettings) {
            showSettings = false
        } else {
            viewModel.resetState()
            onBack()
        }
    }

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onThemeChange = onThemeChange,
            onHelpClick = { showSettings = false }
        )
    } else {
        Scaffold(
            topBar = {
                HomeTopBar(
                    title = "Status Hub",
                    subtitle = "Download from Link",
                    isSelectionMode = false,
                    selectedCount = 0,
                    onMenuClick = {},
                    onBackClick = { viewModel.resetState(); onBack() },
                    onSettingsClick = { showSettings = true },
                    onDeleteClick = null
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            placeholder = { Text("Paste link here...", fontSize = 14.sp) },
                            leadingIcon = { Icon(Icons.Default.Link, null, modifier = Modifier.size(20.dp)) },
                            trailingIcon = {
                                if (url.isNotEmpty()) {
                                    IconButton(onClick = { url = "" }) {
                                        Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp),
                            enabled = downloadState !is NetworkDownloadUtils.DownloadState.Analyzing,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    if (clipboard.hasPrimaryClip()) {
                                        url = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: ""
                                    }
                                },
                                modifier = Modifier.weight(1f).height(38.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ),
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Paste", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }

                            Button(
                                onClick = { if (url.isNotBlank()) viewModel.analyzeUrl(url) },
                                modifier = Modifier.weight(1f).height(38.dp),
                                shape = RoundedCornerShape(10.dp),
                                enabled = downloadState !is NetworkDownloadUtils.DownloadState.Analyzing,
                                contentPadding = PaddingValues(0.dp)
                            ) {
                                Icon(Icons.Default.Search, null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Analyze", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    item {
                        AnimatedContent(targetState = downloadState, label = "state_anim") { state ->
                            when (state) {
                                is NetworkDownloadUtils.DownloadState.Validating,
                                is NetworkDownloadUtils.DownloadState.Analyzing -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                                        CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(32.dp))
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text("Analyzing...", fontSize = 12.sp)
                                    }
                                }
                                is NetworkDownloadUtils.DownloadState.Analyzed -> {
                                    MediaInfoCard(
                                        info = state.info,
                                        onDownloadClick = { format, isAudioOnly ->
                                            val activity = context.findActivity()
                                            AdsManager.handleDownloadAction(activity) {
                                                viewModel.enqueueDownload(context, state.info, format, isAudioOnly)
                                                Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                                            }
                                        }
                                    )
                                }
                                is NetworkDownloadUtils.DownloadState.Error -> {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 8.dp)) {
                                        Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center, fontSize = 12.sp)
                                        TextButton(onClick = { viewModel.resetState() }) { Text("Try Again", fontSize = 13.sp) }
                                    }
                                }
                                else -> {}
                            }
                        }
                    }

                    if (activeDownloads.isNotEmpty()) {
                        item {
                            Text("Active Downloads", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                        items(activeDownloads, key = { "active_${it.id}" }) { record ->
                            ActiveDownloadItem(record = record, speed = speeds[record.id] ?: 0L, onPause = { viewModel.pauseDownload(context, record.id) }, onResume = { viewModel.resumeDownload(context, record.id) }, onCancel = { viewModel.cancelDownload(context, record.id) })
                        }
                    }

                    if (recentCompleted.isNotEmpty()) {
                        item {
                            Text("Recently Completed", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                        }
                        items(recentCompleted, key = { "recent_${it.id}" }) { record ->
                            HistoryItem(record = record, onOpen = { openFile(context, record.fileUri ?: "") }, onShare = { shareFile(context, record.fileUri ?: "") }, onDelete = { deleteRecordConfirmation = record })
                        }
                    }
                }
                AdBanner()
            }
        }
    }

    deleteRecordConfirmation?.let { record ->
        AlertDialog(
            onDismissRequest = { deleteRecordConfirmation = null },
            title = { Text("Delete Download?", fontWeight = FontWeight.Bold) },
            text = { Text("Are you sure you want to permanently delete '${record.fileName}'? This will remove the record and the file from your device.", fontSize = 14.sp) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFileAndRecord(context, record)
                        deleteRecordConfirmation = null
                        Toast.makeText(context, "Deleted permanently", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteRecordConfirmation = null }) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
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
