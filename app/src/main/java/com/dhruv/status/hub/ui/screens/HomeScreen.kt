package com.dhruv.status.hub.ui.screens

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.dhruv.status.hub.ui.components.*
import com.dhruv.status.hub.utils.getDownloadedMedia
import com.dhruv.status.hub.utils.getSavedFolderUri
import com.dhruv.status.hub.utils.saveFolderUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onThemeChange: () -> Unit = {}) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adManager = remember { InterstitialAdManager(context) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Images", "Videos", "Downloads")

    var selectedMedia by remember { mutableStateOf<Uri?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val savedUri = getSavedFolderUri(context)
    var folderUri by remember { mutableStateOf(savedUri) }

    var imageList by remember { mutableStateOf(listOf<Uri>()) }
    var videoList by remember { mutableStateOf(listOf<Uri>()) }
    var downloadedList by remember { mutableStateOf(listOf<Uri>()) }

    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingFirstTime by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    val selectedItems = remember { mutableStateOf(setOf<Uri>()) }
    val isSelectionMode = selectedItems.value.isNotEmpty()
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPermissionInfoDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        adManager.loadAd()
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            folderUri = it
            saveFolderUri(context, it)
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    val launchFolderPicker = {
        val authority = "com.android.externalstorage.documents"
        val documentId = "primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses"
        
        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DocumentsContract.buildDocumentUri(authority, documentId)
        } else null
        
        folderPicker.launch(initialUri)
    }

    LaunchedEffect(Unit) {
        if (folderUri == null) {
            showPermissionInfoDialog = true
        }
    }

    val loadData: suspend (Boolean) -> Unit = { isManualRefresh ->
        if (!isManualRefresh) isLoadingFirstTime = true
        folderUri?.let { uri ->
            withContext(Dispatchers.IO) {
                try {
                    val docFile = DocumentFile.fromTreeUri(context, uri)
                    if (docFile != null && docFile.canRead()) {
                        val images = mutableListOf<Uri>()
                        val videos = mutableListOf<Uri>()

                        val statusFolder = when {
                            docFile.name?.contains("Statuses", ignoreCase = true) == true -> docFile
                            docFile.findFile(".Statuses") != null -> docFile.findFile(".Statuses")
                            docFile.findFile("Statuses") != null -> docFile.findFile("Statuses")
                            docFile.findFile("Media")?.findFile(".Statuses") != null -> docFile.findFile("Media")?.findFile(".Statuses")
                            docFile.findFile("Media")?.findFile("Statuses") != null -> docFile.findFile("Media")?.findFile("Statuses")
                            else -> docFile
                        }

                        statusFolder?.listFiles()?.forEach { file ->
                            if (file.isFile) {
                                val mimeType = file.type?.lowercase() ?: ""
                                val fileName = file.name?.lowercase() ?: ""

                                val isImage = mimeType.startsWith("image") ||
                                        fileName.endsWith(".jpg") ||
                                        fileName.endsWith(".jpeg") ||
                                        fileName.endsWith(".png") ||
                                        fileName.endsWith(".webp") ||
                                        fileName.endsWith(".heic") ||
                                        fileName.endsWith(".heif")

                                val isVideo = mimeType.startsWith("video") ||
                                        fileName.endsWith(".mp4") ||
                                        fileName.endsWith(".mkv") ||
                                        fileName.endsWith(".3gp") ||
                                        fileName.endsWith(".webm") ||
                                        fileName.endsWith(".mov") ||
                                        fileName.endsWith(".avi") ||
                                        fileName.endsWith(".m4v") ||
                                        fileName.contains("vid_")

                                if (isImage) images.add(file.uri) else if (isVideo) videos.add(file.uri)
                            }
                        }

                        withContext(Dispatchers.Main) {
                            imageList = images.reversed()
                            videoList = videos.reversed()
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        downloadedList = getDownloadedMedia(context)
        isLoadingFirstTime = false
    }

    LaunchedEffect(folderUri) {
        loadData(false)
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            downloadedList = getDownloadedMedia(context)
        } else {
            selectedItems.value = emptySet()
        }
    }

    val deleteSelectedItems = {
        scope.launch {
            withContext(Dispatchers.IO) {
                selectedItems.value.forEach { uri ->
                    try {
                        context.contentResolver.delete(uri, null, null)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            selectedItems.value = emptySet()
            downloadedList = getDownloadedMedia(context)
            Toast.makeText(context, "Deleted selected items", Toast.LENGTH_SHORT).show()
        }
    }

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onThemeChange = onThemeChange
        )
    } else {
        BackHandler(isSelectionMode) {
            selectedItems.value = emptySet()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    HomeTopBar(
                        isSelectionMode = isSelectionMode,
                        selectedCount = selectedItems.value.size,
                        showMenu = showMenu,
                        onMenuToggle = { showMenu = it },
                        onSettingsClick = { showSettings = true },
                        onDeleteClick = { showDeleteDialog = true },
                        onHelpClick = { showPermissionInfoDialog = true },
                        onPrivacyPolicyClick = {
                            val intent = Intent(Intent.ACTION_VIEW, "http://sites.google.com/view/status-hub-privacy-policy/home".toUri())
                            context.startActivity(intent)
                        },
                        onShareAppClick = {
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Status Hub App")
                                putExtra(Intent.EXTRA_TEXT, "Check out this amazing WhatsApp Status Saver app! Download it here: https://play.google.com/store/apps/details?id=${context.packageName}")
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                        }
                    )
                }
            ) { innerPadding ->

                Column(
                    modifier = Modifier
                        .padding(innerPadding)
                        .fillMaxSize()
                ) {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = {
                            scope.launch {
                                isRefreshing = true
                                loadData(true)
                                delay(500)
                                isRefreshing = false
                            }
                        },
                        state = pullToRefreshState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                        ) {

                            if (folderUri == null) {
                                PermissionRequiredContent { showPermissionInfoDialog = true }
                            } else if (isLoadingFirstTime && imageList.isEmpty() && videoList.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                HomeTabContent(
                                    selectedTab = selectedTab,
                                    imageList = imageList,
                                    videoList = videoList,
                                    downloadedList = downloadedList,
                                    selectedItems = selectedItems.value,
                                    isSelectionMode = isSelectionMode,
                                    onMediaClick = { uri -> selectedMedia = uri },
                                    onSelectionChange = { newSelection -> selectedItems.value = newSelection }
                                )
                            }

                            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                                StatusBottomBar(
                                    selectedTab = selectedTab,
                                    tabs = tabs,
                                    onTabSelected = { selectedTab = it }
                                )
                            }
                        }
                    }
                    AdBanner()
                }
            }

            if (showPermissionInfoDialog) {
                PermissionInfoDialog(
                    onDismiss = { showPermissionInfoDialog = false },
                    onConfirm = {
                        showPermissionInfoDialog = false
                        launchFolderPicker()
                    }
                )
            }

            if (showDeleteDialog) {
                DeleteConfirmationDialog(
                    selectedCount = selectedItems.value.size,
                    onDismiss = { showDeleteDialog = false },
                    onConfirm = {
                        deleteSelectedItems()
                        showDeleteDialog = false
                    }
                )
            }

            selectedMedia?.let { uri ->
                if (selectedTab == 2) {
                    DownloadedMediaPreviewer(
                        selectedMedia = uri,
                        mediaList = downloadedList,
                        onClose = { selectedMedia = null },
                        onDelete = { deleteUri ->
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    try {
                                        context.contentResolver.delete(deleteUri, null, null)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                                downloadedList = getDownloadedMedia(context)
                                selectedMedia = null
                                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                } else {
                    val currentList = if (context.contentResolver.getType(uri)?.startsWith("video") == true || 
                        uri.toString().lowercase().contains(".mp4")) videoList else imageList

                    MediaPreviewer(
                        selectedMedia = uri,
                        mediaList = currentList,
                        onClose = { selectedMedia = null },
                        adManager = adManager,
                        showDownloadButton = true
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRequiredContent(onGrantClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Info, 
            contentDescription = null, 
            modifier = Modifier.size(48.dp), 
            tint = MaterialTheme.colorScheme.outline
        )
        Spacer(Modifier.height(16.dp))
        Text("Permission Required", fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Grant permission to the WhatsApp Statuses folder to start viewing media.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        Spacer(Modifier.height(24.dp))
        Button(
            onClick = onGrantClick,
            shape = RoundedCornerShape(50)
        ) {
            Text("Grant Permission")
        }
    }
}

@Composable
fun HomeTabContent(
    selectedTab: Int,
    imageList: List<Uri>,
    videoList: List<Uri>,
    downloadedList: List<Uri>,
    selectedItems: Set<Uri>,
    isSelectionMode: Boolean,
    onMediaClick: (Uri) -> Unit,
    onSelectionChange: (Set<Uri>) -> Unit
) {
    AnimatedContent(
        targetState = selectedTab,
        transitionSpec = {
            if (targetState > initialState) {
                slideInHorizontally(initialOffsetX = { it }) + fadeIn(tween(20)) togetherWith
                        slideOutHorizontally(targetOffsetX = { -it }) + fadeOut(tween(20))
            } else {
                slideInHorizontally(initialOffsetX = { -it }) + fadeIn(tween(20)) togetherWith
                        slideOutHorizontally(targetOffsetX = { it }) + fadeOut(tween(20))
            }
        },
        label = "tab_slide_animation",
        modifier = Modifier.fillMaxSize()
    ) { targetTab ->
        Box(modifier = Modifier.fillMaxSize()) {
            when (targetTab) {
                0 -> {
                    if (imageList.isEmpty()) {
                        EmptyStateContent("No statuses found.", "Make sure you've viewed statuses in WhatsApp.")
                    } else {
                        ImageGrid(imageList, onClick = onMediaClick)
                    }
                }
                1 -> {
                    if (videoList.isEmpty()) {
                        EmptyStateContent("No videos found", "")
                    } else {
                        VideoGrid(videoList, onClick = onMediaClick)
                    }
                }
                2 -> {
                    if (downloadedList.isEmpty()) {
                        EmptyStateContent("No downloads yet.", "")
                    } else {
                        MediaGrid(
                            mediaList = downloadedList,
                            selectedItems = selectedItems,
                            onItemClick = { uri ->
                                if (isSelectionMode) {
                                    onSelectionChange(if (selectedItems.contains(uri)) selectedItems - uri else selectedItems + uri)
                                } else {
                                    onMediaClick(uri)
                                }
                            },
                            onItemLongClick = { uri ->
                                onSelectionChange(if (selectedItems.contains(uri)) selectedItems - uri else selectedItems + uri)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyStateContent(title: String, subtitle: String) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()), 
        Arrangement.Center, 
        Alignment.CenterHorizontally
    ) {
        Text(title, color = MaterialTheme.colorScheme.outline)
        if (subtitle.isNotEmpty()) {
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f))
        }
    }
}

@Composable
fun PermissionInfoDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text(text = "Follow these steps", fontWeight = FontWeight.Bold) },
        text = { 
            Column {
                Text("1. Click the button below.")
                Spacer(Modifier.height(8.dp))
                Text("2. Click 'USE THIS FOLDER' at the bottom of your screen.", fontWeight = FontWeight.Bold)
                HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text("Note: If you don't see the folder, it's located at:", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                Text("Android > media > com.whatsapp > WhatsApp > Media > .Statuses", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        confirmButton = {
            Button(onClick = onConfirm, shape = RoundedCornerShape(50)) {
                Text("Grant Permission")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Not Now", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    )
}

@Composable
fun DeleteConfirmationDialog(selectedCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(28.dp),
        title = { Text(text = "Delete Downloads", fontWeight = FontWeight.Bold) },
        text = { Text(text = "Are you sure you want to delete $selectedCount items?") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(50)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    )
}
