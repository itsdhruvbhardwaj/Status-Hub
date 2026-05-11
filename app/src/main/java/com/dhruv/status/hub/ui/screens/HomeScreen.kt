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
import com.dhruv.status.hub.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HomeScreen Composable
 * 
 * The main screen of the application, responsible for displaying WhatsApp statuses (Images/Videos)
 * and downloaded media. It handles folder permissions, data loading, and navigation to settings/previews.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onThemeChange: () -> Unit = {}) {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Manager for interstitial ads
    val adManager = remember { InterstitialAdManager(context) }

    // State for the currently selected tab (0: Images, 1: Videos, 2: Downloads)
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Images", "Videos", "Downloads")

    // State for the media item currently being previewed
    var selectedMedia by remember { mutableStateOf<Uri?>(null) }
    // State to toggle visibility of the Settings screen
    var showSettings by remember { mutableStateOf(false) }

    // Retrieve the saved folder URI from SharedPreferences
    val savedUri = getSavedFolderUri(context)
    var folderUri by remember { mutableStateOf(savedUri) }

    // Lists to hold URIs for different media types
    var imageList by remember { mutableStateOf(listOf<Uri>()) }
    var videoList by remember { mutableStateOf(listOf<Uri>()) }
    var downloadedList by remember { mutableStateOf(listOf<Uri>()) }

    // States for pull-to-refresh and initial loading indicator
    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingFirstTime by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    // Multi-selection state for the Downloads tab
    val selectedItems = remember { mutableStateOf(setOf<Uri>()) }
    val isSelectionMode = selectedItems.value.isNotEmpty()
    
    // States for various dialogs
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPermissionInfoDialog by remember { mutableStateOf(false) }

    // Load an interstitial ad when the screen is first composed
    LaunchedEffect(Unit) {
        adManager.loadAd()
    }

    // Launcher for selecting the WhatsApp statuses directory
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            folderUri = it
            saveFolderUri(context, it)
            // Persist the permission so it survives app restarts
            context.contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    // Function to launch the folder picker with a pre-selected initial URI if possible
    val launchFolderPicker = {
        val authority = "com.android.externalstorage.documents"
        val documentId = "primary:Android/media/com.whatsapp/WhatsApp/Media/.Statuses"
        
        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            DocumentsContract.buildDocumentUri(authority, documentId)
        } else null
        
        folderPicker.launch(initialUri)
    }

    // Show permission info dialog if no folder is selected yet
    LaunchedEffect(Unit) {
        if (folderUri == null) {
            showPermissionInfoDialog = true
        }
    }

    /**
     * Loads media files from the selected folder and the downloads directory.
     * @param isManualRefresh Whether the load was triggered by the user (pull-to-refresh).
     */
    val loadData: suspend (Boolean) -> Unit = { isManualRefresh ->
        if (!isManualRefresh) isLoadingFirstTime = true
        folderUri?.let { uri ->
            withContext(Dispatchers.IO) {
                try {
                    val docFile = DocumentFile.fromTreeUri(context, uri)
                    if (docFile != null && docFile.canRead()) {
                        val images = mutableListOf<Uri>()
                        val videos = mutableListOf<Uri>()
                        
                        val autoSaveEnabled = isAutoSaveEnabled(context)

                        // Attempt to find the .Statuses folder within the selected directory
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

                                // Determine if file is an image or video based on mime type or extension
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

                                if (isImage) {
                                    images.add(file.uri)
                                    if (autoSaveEnabled) downloadMedia(context, file.uri, true)
                                } else if (isVideo) {
                                    videos.add(file.uri)
                                    if (autoSaveEnabled) downloadMedia(context, file.uri, true)
                                }
                            }
                        }

                        // Update lists on the main thread (reversed to show latest first)
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
        // Load the list of manually saved media
        downloadedList = getDownloadedMedia(context)
        isLoadingFirstTime = false
    }

    // Initial data load and background polling for updates every minute
    LaunchedEffect(folderUri) {
        if (folderUri != null) {
            loadData(false)
            while (isActive) {
                delay(60000)
                loadData(true)
            }
        }
    }

    // Refresh downloaded list when switching to the Downloads tab
    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            downloadedList = getDownloadedMedia(context)
        } else {
            // Clear selection mode when leaving the Downloads tab
            selectedItems.value = emptySet()
        }
    }

    // Action to delete selected items from the Downloads tab
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

    // UI Routing: Show Settings screen if toggled
    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onThemeChange = onThemeChange,
            onHelpClick = {
                showSettings = false
                showPermissionInfoDialog = true
            }
        )
    } else {
        // Handle back button to exit selection mode instead of closing app
        BackHandler(isSelectionMode) {
            selectedItems.value = emptySet()
        }

        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    HomeTopBar(
                        isSelectionMode = isSelectionMode,
                        selectedCount = selectedItems.value.size,
                        onSettingsClick = { showSettings = true },
                        onDeleteClick = { showDeleteDialog = true }
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

                            // Conditional content based on permission and loading state
                            if (folderUri == null) {
                                PermissionRequiredContent { showPermissionInfoDialog = true }
                            } else if (isLoadingFirstTime && imageList.isEmpty() && videoList.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else {
                                // Display the grid for the current tab
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

                            // Bottom navigation bar
                            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                                StatusBottomBar(
                                    selectedTab = selectedTab,
                                    tabs = tabs,
                                    onTabSelected = { selectedTab = it }
                                )
                            }
                        }
                    }
                    // Ad banner at the bottom of the screen
                    AdBanner()
                }
            }

            // Overlay dialogs
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

            // Full-screen media previewer
            selectedMedia?.let { uri ->
                if (selectedTab == 2) {
                    // Previewer specifically for downloaded media (with delete option)
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
                    // Standard previewer for WhatsApp statuses
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

/**
 * UI shown when the app doesn't have access to the WhatsApp status folder yet.
 */
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

/**
 * Content switcher for the main tabs, with transition animations.
 */
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
                0 -> { // Images Tab
                    if (imageList.isEmpty()) {
                        EmptyStateContent("No statuses found.", "Make sure you've viewed statuses in WhatsApp.")
                    } else {
                        ImageGrid(imageList, onClick = onMediaClick)
                    }
                }
                1 -> { // Videos Tab
                    if (videoList.isEmpty()) {
                        EmptyStateContent("No videos found", "")
                    } else {
                        VideoGrid(videoList, onClick = onMediaClick)
                    }
                }
                2 -> { // Downloads Tab
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

/**
 * Generic empty state UI.
 */
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

/**
 * Dialog guiding the user on how to grant folder permissions correctly.
 */
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

/**
 * Confirmation dialog before deleting downloaded media.
 */
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
