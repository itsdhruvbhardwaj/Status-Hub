package com.dhruv.status.hub.ui.screens

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import com.dhruv.status.hub.ui.components.ActiveDownloadItem
import com.dhruv.status.hub.ui.components.AdBanner
import com.dhruv.status.hub.ui.components.HistoryItem
import com.dhruv.status.hub.ui.components.MediaInfoCard
import com.dhruv.status.hub.ui.viewmodels.DownloadViewModel
import com.dhruv.status.hub.utils.AdsManager
import com.dhruv.status.hub.utils.DownloadManager
import com.dhruv.status.hub.utils.NetworkDownloadUtils
import com.dhruv.status.hub.utils.findActivity

private const val TAG = "DownloadLinkScreen"

/**
 * Screen for analyzing and downloading media from a direct link.
 * Maintained with Paste/Analyze at the top, followed by active downloads and history.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadFromLinkScreen(
    initialUrl: String? = null,
    onBack: () -> Unit,
    onNavigateToRecentDownloads: () -> Unit,
    viewModel: DownloadViewModel = viewModel()
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(initialUrl ?: "") }
    val downloadState by viewModel.downloadState.collectAsState()
    
    val allDownloads by viewModel.allDownloads.collectAsState()
    val speeds by DownloadManager.downloadSpeeds.collectAsState()
    
    val activeDownloads = allDownloads.filter { it.status in listOf("QUEUED", "DOWNLOADING", "PAUSED", "FAILED") }
    val recentCompleted = allDownloads.filter { it.status == "COMPLETED" }.take(5)

    // Handle initial URL analysis
    LaunchedEffect(initialUrl) {
        if (!initialUrl.isNullOrBlank()) {
            url = initialUrl
            Log.d(TAG, "Initial URL received: $initialUrl")
            viewModel.analyzeUrl(initialUrl)
        } else {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            if (clipboard.hasPrimaryClip()) {
                val item = clipboard.primaryClip?.getItemAt(0)
                val clipText = item?.text?.toString() ?: ""
                if (clipText.startsWith("http") && url.isEmpty()) {
                    url = clipText.trim()
                    Log.d(TAG, "Auto-pasted from clipboard: $url")
                }
            }
        }
    }

    BackHandler { 
        viewModel.resetState()
        onBack() 
    }

    Scaffold(
        topBar = {
            Surface(
                shadowElevation = 2.dp,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Status Hub",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 24.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.resetState()
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Section 1: URL Input
                item {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Paste video/audio link...") },
                        placeholder = { Text("https://youtube.com/...") },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                        trailingIcon = {
                            if (url.isNotEmpty()) {
                                IconButton(onClick = { url = "" }) {
                                    Icon(Icons.Default.Close, contentDescription = "Clear")
                                }
                            }
                        },
                        shape = RoundedCornerShape(20.dp),
                        singleLine = true,
                        enabled = downloadState !is NetworkDownloadUtils.DownloadState.Analyzing,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                        )
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                if (clipboard.hasPrimaryClip()) {
                                    val item = clipboard.primaryClip?.getItemAt(0)
                                    val clipText = item?.text?.toString() ?: ""
                                    val cleanedUrl = clipText.trim()
                                    url = cleanedUrl
                                }
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer, 
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Paste", fontWeight = FontWeight.Bold)
                        }

                        Button(
                            onClick = {
                                if (url.isNotBlank()) {
                                    viewModel.analyzeUrl(url)
                                }
                            },
                            modifier = Modifier.weight(1f).height(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            enabled = downloadState !is NetworkDownloadUtils.DownloadState.Analyzing
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Analyze", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(28.dp))
                }

                // Section 2: Analysis Result
                item {
                    AnimatedContent(
                        targetState = downloadState, 
                        label = "download_state",
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }
                    ) { state ->
                        when (state) {
                            is NetworkDownloadUtils.DownloadState.Validating,
                            is NetworkDownloadUtils.DownloadState.Analyzing -> {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp)
                                ) {
                                    CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Analyzing link...")
                                }
                            }
                            
                            is NetworkDownloadUtils.DownloadState.Analyzed -> {
                                MediaInfoCard(
                                    info = state.info,
                                    onDownloadClick = { format, _ ->
                                        val activity = context.findActivity()
                                        AdsManager.handleDownloadAction(activity) {
                                            viewModel.enqueueDownload(context, state.info, format)
                                            Toast.makeText(context, "Download started", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                Spacer(modifier = Modifier.height(28.dp))
                            }

                            is NetworkDownloadUtils.DownloadState.Error -> {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(state.message, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                                    Spacer(Modifier.height(16.dp))
                                    Button(onClick = { viewModel.resetState() }) {
                                        Text("Try Again")
                                    }
                                    Spacer(modifier = Modifier.height(28.dp))
                                }
                            }
                            
                            else -> {}
                        }
                    }
                }

                // Section 3: Active Downloads
                if (activeDownloads.isNotEmpty()) {
                    item {
                        Text(
                            text = "Active Downloads",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                        )
                    }
                    items(activeDownloads, key = { "active_${it.id}" }) { record ->
                        ActiveDownloadItem(
                            record = record,
                            speed = speeds[record.id] ?: 0L,
                            onPause = { viewModel.pauseDownload(context, record.id) },
                            onResume = { viewModel.resumeDownload(context, record.id) },
                            onCancel = { viewModel.cancelDownload(context, record.id) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }

                // Section 4: Recently Completed
                if (recentCompleted.isNotEmpty()) {
                    item {
                        Text(
                            text = "Recently Completed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )
                    }
                    items(recentCompleted, key = { "recent_${it.id}" }) { record ->
                        HistoryItem(
                            record = record,
                            onOpen = { openFile(context, record.fileUri ?: "") },
                            onShare = { shareFile(context, record.fileUri ?: "") },
                            onDelete = { viewModel.deleteRecord(record) }
                        )
                    }
                }
            }
            AdBanner()
        }
    }
}

private fun openFile(context: Context, uriString: String) {
    try {
        val uri = uriString.toUri()
        val mimeType = context.contentResolver.getType(uri)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
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
