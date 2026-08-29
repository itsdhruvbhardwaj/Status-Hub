package com.dhruv.status.hub.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhruv.status.hub.ui.components.MediaInfoCard
import com.dhruv.status.hub.ui.viewmodels.DownloadViewModel
import com.dhruv.status.hub.utils.NetworkDownloadUtils

/**
 * Screen for analyzing and downloading media from a direct link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadFromLinkScreen(
    onBack: () -> Unit,
    onNavigateToRecentDownloads: () -> Unit,
    viewModel: DownloadViewModel = viewModel()
) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    val downloadState by viewModel.downloadState.collectAsState()
    
    var lastInfo by remember { mutableStateOf<NetworkDownloadUtils.MediaInfo?>(null) }
    
    LaunchedEffect(downloadState) {
        if (downloadState is NetworkDownloadUtils.DownloadState.Analyzed) {
            lastInfo = (downloadState as NetworkDownloadUtils.DownloadState.Analyzed).info
        }
    }

    var showDuplicateDialog by remember { mutableStateOf<NetworkDownloadUtils.DownloadState.Duplicate?>(null) }

    LaunchedEffect(Unit) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (clipboard.hasPrimaryClip()) {
            val item = clipboard.primaryClip?.getItemAt(0)
            val clipText = item?.text?.toString() ?: ""
            if (clipText.startsWith("http")) {
                url = clipText
            }
        }
    }

    BackHandler { 
        viewModel.resetState()
        onBack() 
    }

    Scaffold(
        topBar = {
            Surface(shadowElevation = 2.dp) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Download from Link",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Cursive,
                            fontSize = 26.sp
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
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
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
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
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
                enabled = downloadState !is NetworkDownloadUtils.DownloadState.Downloading && 
                         downloadState !is NetworkDownloadUtils.DownloadState.Analyzing,
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
                            url = item?.text?.toString() ?: ""
                        } else {
                            Toast.makeText(context, "Nothing to paste", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer, 
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    enabled = downloadState !is NetworkDownloadUtils.DownloadState.Downloading
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Paste", fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        if (url.isBlank()) {
                            Toast.makeText(context, "Please enter a URL", Toast.LENGTH_SHORT).show()
                        } else {
                            viewModel.analyzeUrl(url)
                        }
                    },
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(16.dp),
                    enabled = downloadState !is NetworkDownloadUtils.DownloadState.Downloading && 
                             downloadState !is NetworkDownloadUtils.DownloadState.Analyzing
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Analyze", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            AnimatedContent(
                targetState = downloadState, 
                contentKey = { it::class },
                label = "download_state",
                transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) }
            ) { state ->
                when (state) {
                    is NetworkDownloadUtils.DownloadState.Validating,
                    is NetworkDownloadUtils.DownloadState.Analyzing -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 32.dp)
                        ) {
                            CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(48.dp))
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Analyzing link...", 
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    is NetworkDownloadUtils.DownloadState.Analyzed -> {
                        MediaInfoCard(
                            info = state.info,
                            onDownloadClick = { isAudioOnly ->
                                viewModel.downloadMedia(context, state.info, isAudioOnly = isAudioOnly)
                            }
                        )
                    }

                    is NetworkDownloadUtils.DownloadState.Downloading -> {
                        val animatedProgress by animateFloatAsState(
                            targetValue = if (state.progress >= 0f) state.progress else 0f,
                            animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                            label = "smooth_progress"
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            shape = RoundedCornerShape(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                            ),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(20.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.size(42.dp)
                                        ) {
                                            Icon(
                                                imageVector = when (lastInfo?.mediaType) {
                                                    "video" -> Icons.Default.VideoFile
                                                    "audio" -> Icons.Default.AudioFile
                                                    else -> Icons.Default.CloudDownload
                                                },
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(16.dp))
                                        Column {
                                            Text("Downloading...", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                                            lastInfo?.let {
                                                Text(
                                                    text = it.fileName,
                                                    fontSize = 12.sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                    if (state.progress >= 0f) {
                                        Text(
                                            text = "${(state.progress * 100).toInt()}%",
                                            fontWeight = FontWeight.Black,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 18.sp
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                if (state.progress >= 0f) {
                                    LinearProgressIndicator(
                                        progress = { animatedProgress },
                                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                                        color = MaterialTheme.colorScheme.primary,
                                        trackColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
                                    )
                                } else {
                                    LinearProgressIndicator(
                                        modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = formatProgressText(state.downloadedBytes, state.totalBytes),
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    fontFamily = FontFamily.Monospace,
                                    modifier = Modifier.align(Alignment.End)
                                )
                            }
                        }
                    }

                    is NetworkDownloadUtils.DownloadState.Success -> {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            shape = RoundedCornerShape(32.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.3f)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Surface(
                                    modifier = Modifier.size(64.dp),
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFF4CAF50).copy(alpha = 0.1f)
                                ) {
                                    Icon(
                                        Icons.Default.DownloadDone, 
                                        null, 
                                        tint = Color(0xFF4CAF50), 
                                        modifier = Modifier.padding(16.dp)
                                    )
                                }
                                
                                Spacer(modifier = Modifier.height(20.dp))
                                Text(
                                    text = "Download Complete!", 
                                    color = MaterialTheme.colorScheme.onSurface, 
                                    fontWeight = FontWeight.Black, 
                                    fontSize = 22.sp
                                )
                                
                                lastInfo?.let {
                                    Text(
                                        text = it.fileName,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 28.dp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Button(
                                        onClick = { openFile(context, state.filePath) }, 
                                        modifier = Modifier.weight(1f).height(50.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.primary
                                        )
                                    ) {
                                        Text("Open", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { shareFile(context, state.filePath) }, 
                                        modifier = Modifier.weight(1f).height(50.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text("Share", fontWeight = FontWeight.Bold)
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Button(
                                    onClick = onNavigateToRecentDownloads,
                                    modifier = Modifier.fillMaxWidth().height(50.dp),
                                    shape = RoundedCornerShape(16.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                ) {
                                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("View All Downloads", fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(20.dp))

                                TextButton(
                                    onClick = { 
                                        url = ""
                                        viewModel.resetState() 
                                    }
                                ) {
                                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "Download another link", 
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                }
                            }
                        }
                    }

                    is NetworkDownloadUtils.DownloadState.UnsupportedSource -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 24.dp)
                        ) {
                            Text(
                                text = "${state.platform} not supported yet.",
                                color = MaterialTheme.colorScheme.primary,
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                text = "Try a direct media link instead.",
                                fontSize = 14.sp,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Button(
                                onClick = { viewModel.resetState() }, 
                                modifier = Modifier.padding(top = 24.dp),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Got it")
                            }
                        }
                    }

                    is NetworkDownloadUtils.DownloadState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(vertical = 24.dp)
                        ) {
                            Surface(
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.padding(bottom = 16.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close, 
                                    null, 
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                            Text(
                                text = state.message, 
                                color = MaterialTheme.colorScheme.error, 
                                textAlign = TextAlign.Center,
                                fontWeight = FontWeight.Medium
                            )
                            Button(
                                onClick = { viewModel.resetState() }, 
                                modifier = Modifier.padding(top = 24.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Try Again")
                            }
                        }
                    }
                    
                    is NetworkDownloadUtils.DownloadState.Duplicate -> {
                        LaunchedEffect(state) { showDuplicateDialog = state }
                    }
                    
                    else -> {}
                }
            }
        }
    }

    showDuplicateDialog?.let { duplicate ->
        AlertDialog(
            onDismissRequest = { showDuplicateDialog = null },
            title = { Text("Already Downloaded") },
            text = { Text("The file '${duplicate.info.fileName}' already exists in your gallery.") },
            confirmButton = {
                Button(onClick = {
                    showDuplicateDialog = null
                    viewModel.downloadMedia(context, duplicate.info, forceDownload = true)
                }) { Text("Download Again") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDuplicateDialog = null
                    openFile(context, duplicate.existingUri.toString())
                }) { Text("Open Existing") }
            }
        )
    }
}

private fun openFile(context: Context, filePath: String) {
    try {
        val uri = filePath.toUri()
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot open file", Toast.LENGTH_SHORT).show()
    }
}

private fun shareFile(context: Context, filePath: String) {
    try {
        val uri = filePath.toUri()
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

/**
 * Formats download progress text (e.g., "1.2 MB / 5.0 MB").
 */
private fun formatProgressText(downloaded: Long, total: Long): String {
    val downloadedMb = downloaded / (1024 * 1024f)
    return if (total > 0) {
        val totalMb = total / (1024 * 1024f)
        "%.1f MB / %.1f MB".format(downloadedMb, totalMb)
    } else {
        "%.1f MB downloaded".format(downloadedMb)
    }
}
