package com.dhruv.status.hub.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.dhruv.status.hub.R
import com.dhruv.status.hub.ui.components.*
import com.dhruv.status.hub.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onThemeChange: () -> Unit = {},
    onNavigateToDownloadLink: () -> Unit = {},
    onNavigateToRecentDownloads: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Images", "Videos", "Downloads")

    var currentFolder by remember { mutableStateOf<String?>(null) }
    var selectedMedia by remember { mutableStateOf<Uri?>(null) }
    var showSettings by remember { mutableStateOf(false) }

    val savedUri = getSavedFolderUri(context)
    var folderUri by remember { mutableStateOf(savedUri) }

    var imageList by remember { mutableStateOf(listOf<Uri>()) }
    var videoList by remember { mutableStateOf(listOf<Uri>()) }
    var downloadedList by remember { mutableStateOf(listOf<Uri>()) }
    var favorites by remember { mutableStateOf(getFavorites(context)) }

    var isRefreshing by remember { mutableStateOf(false) }
    var isLoadingFirstTime by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    val selectedItems = remember { mutableStateOf(setOf<Uri>()) }
    val isSelectionMode = selectedItems.value.isNotEmpty()
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPermissionInfoDialog by remember { mutableStateOf(false) }

    // Filter downloaded media based on the current folder
    val filteredDownloadedList = remember(downloadedList, currentFolder, favorites) {
        if (currentFolder == null) downloadedList
        else {
            when (currentFolder) {
                "Images" -> downloadedList.filter { context.contentResolver.getType(it)?.startsWith("image") == true }
                "Videos" -> downloadedList.filter { context.contentResolver.getType(it)?.startsWith("video") == true }
                "Audios" -> downloadedList.filter { 
                    val type = context.contentResolver.getType(it) ?: ""
                    type.startsWith("audio") || it.toString().lowercase().let { s -> s.contains(".mp3") || s.contains(".m4a") }
                }
                "Favorites" -> downloadedList.filter { favorites.contains(it.toString()) }
                else -> downloadedList
            }
        }
    }

    // Clear selection when folder or tab changes
    LaunchedEffect(selectedTab, currentFolder) {
        selectedItems.value = emptySet()
    }

    // Preload ad on screen start
    LaunchedEffect(Unit) { 
        AdsManager.loadInterstitial(context) 
    }

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            folderUri = it
            saveFolderUri(context, it)
            context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        if (folderUri == null) showPermissionInfoDialog = true
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
                                val type = file.type?.lowercase() ?: ""
                                if (type.startsWith("image")) images.add(file.uri)
                                else if (type.startsWith("video")) videos.add(file.uri)
                            }
                        }
                        withContext(Dispatchers.Main) {
                            imageList = images.reversed()
                            videoList = videos.reversed()
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        }
        downloadedList = FileUtils.getDownloadedMedia(context)
        isLoadingFirstTime = false
    }

    LaunchedEffect(folderUri) {
        if (folderUri != null) {
            loadData(false)
            while (isActive) { delay(60000); loadData(true) }
        }
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == 2) {
            downloadedList = FileUtils.getDownloadedMedia(context)
            favorites = getFavorites(context)
        } else {
            currentFolder = null
        }
    }

    val deleteSelectedItems = {
        scope.launch {
            withContext(Dispatchers.IO) {
                selectedItems.value.forEach { uri ->
                    try { context.contentResolver.delete(uri, null, null) } catch (e: Exception) {}
                }
            }
            selectedItems.value = emptySet()
            downloadedList = FileUtils.getDownloadedMedia(context)
            favorites = getFavorites(context)
        }
    }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, onThemeChange = onThemeChange, 
            onHelpClick = { showSettings = false; showPermissionInfoDialog = true })
    } else {
        BackHandler(drawerState.isOpen || isSelectionMode || currentFolder != null) {
            when {
                drawerState.isOpen -> scope.launch { drawerState.close() }
                isSelectionMode -> selectedItems.value = emptySet()
                currentFolder != null -> currentFolder = null
                else -> (context as? Activity)?.finish()
            }
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                HomeDrawerContent(
                    selectedTab = selectedTab,
                    onHomeClick = { scope.launch { drawerState.close() }; selectedTab = 0 },
                    onDownloadLinkClick = { scope.launch { drawerState.close() }; onNavigateToDownloadLink() },
                    onRecentDownloadsClick = { scope.launch { drawerState.close() }; onNavigateToRecentDownloads() },
                    onDownloadsClick = { scope.launch { drawerState.close() }; selectedTab = 2; currentFolder = null },
                    onFavoritesClick = { scope.launch { drawerState.close() }; selectedTab = 2; currentFolder = "Favorites" },
                    onSettingsClick = { scope.launch { drawerState.close() }; showSettings = true },
                    onHelpClick = { scope.launch { drawerState.close() }; showPermissionInfoDialog = true }
                )
            }
        ) {
            Scaffold(
                topBar = {
                    HomeTopBar(
                        title = currentFolder ?: if (selectedTab == 2) "Downloads" else "Status Hub",
                        isSelectionMode = isSelectionMode,
                        selectedCount = selectedItems.value.size,
                        onMenuClick = { scope.launch { drawerState.open() } },
                        onBackClick = if (currentFolder != null && !isSelectionMode) { { currentFolder = null } } else null,
                        onSettingsClick = { showSettings = true },
                        onDeleteClick = { showDeleteDialog = true },
                        onClearSelection = { selectedItems.value = emptySet() }
                    )
                }
            ) { innerPadding ->
                Column(Modifier
                    .padding(innerPadding)
                    .fillMaxSize()) {
                    PullToRefreshBox(
                        isRefreshing = isRefreshing,
                        onRefresh = { scope.launch { isRefreshing = true; loadData(true); delay(500); isRefreshing = false } },
                        state = pullToRefreshState,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        Box(Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)) {
                            if (folderUri == null) {
                                PermissionRequiredContent { showPermissionInfoDialog = true }
                            } else if (isLoadingFirstTime && imageList.isEmpty() && videoList.isEmpty()) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                            } else {
                                HomeTabContent(
                                    selectedTab = selectedTab,
                                    currentFolder = currentFolder,
                                    onFolderSelected = { currentFolder = it },
                                    imageList = imageList,
                                    videoList = videoList,
                                    downloadedList = downloadedList,
                                    filteredDownloadedList = filteredDownloadedList,
                                    favorites = favorites,
                                    selectedItems = selectedItems.value,
                                    isSelectionMode = isSelectionMode,
                                    onMediaClick = { uri ->
                                        val type = context.contentResolver.getType(uri) ?: ""
                                        if (type.startsWith("audio") || uri.toString().lowercase().let { it.contains(".mp3") || it.contains(".m4a") }) {
                                            openAudioExternally(context, uri)
                                        } else {
                                            selectedMedia = uri
                                        }
                                    },
                                    onSelectionChange = { selectedItems.value = it },
                                    onDeleteSingle = { uri ->
                                        selectedItems.value = setOf(uri)
                                        showDeleteDialog = true
                                    }
                                )
                            }
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                StatusBottomBar(selectedTab = selectedTab, tabs = tabs, onTabSelected = { selectedTab = it })
                            }
                        }
                    }
                    AdBanner()
                }
            }

            if (showPermissionInfoDialog) PermissionInfoDialog(onDismiss = { showPermissionInfoDialog = false }, onConfirm = { showPermissionInfoDialog = false; launchFolderPicker() })
            if (showDeleteDialog) DeleteConfirmationDialog(selectedCount = selectedItems.value.size, onDismiss = { showDeleteDialog = false; if (!isSelectionMode) selectedItems.value = emptySet() }, onConfirm = { deleteSelectedItems(); showDeleteDialog = false })
            
            selectedMedia?.let { uri ->
                if (selectedTab == 2) {
                    DownloadedMediaPreviewer(
                        selectedMedia = uri, 
                        mediaList = filteredDownloadedList, 
                        onClose = { 
                            selectedMedia = null
                            downloadedList = FileUtils.getDownloadedMedia(context)
                            favorites = getFavorites(context)
                        }, 
                        onDelete = { deleteUri -> 
                            scope.launch { 
                                withContext(Dispatchers.IO) { 
                                    try { context.contentResolver.delete(deleteUri, null, null) } catch (e: Exception) {} 
                                }
                                downloadedList = FileUtils.getDownloadedMedia(context)
                                favorites = getFavorites(context)
                                selectedMedia = null
                            }
                        }
                    )
                } else {
                    MediaPreviewer(
                        selectedMedia = uri, 
                        mediaList = if (context.contentResolver.getType(uri)?.startsWith("video") == true) videoList else imageList, 
                        onClose = { selectedMedia = null }, 
                        showDownloadButton = true
                    )
                }
            }
        }
    }
}

private fun openAudioExternally(context: Context, uri: Uri) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, context.contentResolver.getType(uri) ?: "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open Audio with..."))
    } catch (e: Exception) {
        Toast.makeText(context, "No audio player found", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun HomeTabContent(
    selectedTab: Int,
    currentFolder: String?,
    onFolderSelected: (String?) -> Unit,
    imageList: List<Uri>,
    videoList: List<Uri>,
    downloadedList: List<Uri>,
    filteredDownloadedList: List<Uri>,
    favorites: Set<String>,
    selectedItems: Set<Uri>,
    isSelectionMode: Boolean,
    onMediaClick: (Uri) -> Unit,
    onSelectionChange: (Set<Uri>) -> Unit,
    onDeleteSingle: (Uri) -> Unit = {}
) {
    val context = LocalContext.current
    AnimatedContent(targetState = selectedTab, label = "tab_anim") { targetTab ->
        when (targetTab) {
            0 -> if (imageList.isEmpty()) EmptyStateContent("No statuses found.", "Please watch some statuses on WhatsApp first.") else MediaGrid(mediaList = imageList, selectedItems = selectedItems, onItemClick = { if (isSelectionMode) onSelectionChange(if (selectedItems.contains(it)) selectedItems - it else selectedItems + it) else onMediaClick(it) }, onItemLongClick = { onSelectionChange(if (selectedItems.contains(it)) selectedItems - it else selectedItems + it) })
            1 -> if (videoList.isEmpty()) EmptyStateContent("No videos found.", "Please watch some statuses on WhatsApp first.") else MediaGrid(mediaList = videoList, selectedItems = selectedItems, onItemClick = { if (isSelectionMode) onSelectionChange(if (selectedItems.contains(it)) selectedItems - it else selectedItems + it) else onMediaClick(it) }, onItemLongClick = { onSelectionChange(if (selectedItems.contains(it)) selectedItems - it else selectedItems + it) })
            2 -> {
                if (currentFolder == null) {
                    DownloadsFolderGrid(downloadedList, favorites, onFolderSelected)
                } else {
                    Column(Modifier.fillMaxSize()) {
                        if (filteredDownloadedList.isEmpty()) {
                            EmptyStateContent("Folder is Empty", "")
                        } else {
                            if (currentFolder == "Audios") {
                                AudioRowList(
                                    audioList = filteredDownloadedList, 
                                    selectedItems = selectedItems,
                                    isSelectionMode = isSelectionMode,
                                    onItemClick = { if (isSelectionMode) onSelectionChange(if (selectedItems.contains(it)) selectedItems - it else selectedItems + it) else onMediaClick(it) },
                                    onItemLongClick = { onSelectionChange(if (selectedItems.contains(it)) selectedItems - it else selectedItems + it) },
                                    onDeleteClick = onDeleteSingle
                                )
                            } else {
                                MediaGrid(mediaList = filteredDownloadedList, selectedItems = selectedItems, onItemClick = { if (isSelectionMode) onSelectionChange(if (selectedItems.contains(it)) selectedItems - it else selectedItems + it) else onMediaClick(it) }, onItemLongClick = { onSelectionChange(if (selectedItems.contains(it)) selectedItems - it else selectedItems + it) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AudioRowList(
    audioList: List<Uri>, 
    selectedItems: Set<Uri>,
    isSelectionMode: Boolean,
    onItemClick: (Uri) -> Unit,
    onItemLongClick: (Uri) -> Unit,
    onDeleteClick: (Uri) -> Unit = {}
) {
    val context = LocalContext.current
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp, top = 0.dp)) {
        items(audioList, key = { it.toString() }) { uri ->
            val fileName = remember(uri) { getAudioDisplayName(context, uri) }
            val isSelected = selectedItems.contains(uri)
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 3.dp)
                    .combinedClickable(
                        onClick = { onItemClick(uri) },
                        onLongClick = { onItemLongClick(uri) }
                    ),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = if (isSelected) 0.dp else 1.dp),
                border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)) else null
            ) {
                Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.08f), 
                        shape = RoundedCornerShape(10.dp), 
                        modifier = Modifier.size(44.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isSelected) {
                                Icon(imageVector = Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            } else {
                                Icon(imageVector = Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = fileName, 
                            maxLines = 1, 
                            overflow = TextOverflow.Ellipsis, 
                            fontWeight = FontWeight.Bold, 
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.sp
                        )
                    }
                    
                    if (isSelectionMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onItemClick(uri) },
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { 
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "audio/*"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Audio"))
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Share, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onDeleteClick(uri) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { onItemClick(uri) }, modifier = Modifier.size(32.dp)) {
                                Icon(imageVector = Icons.Default.PlayCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun getAudioDisplayName(context: Context, uri: Uri): String {
    var name = "Unknown Audio"
    try {
        context.contentResolver.query(uri, arrayOf(MediaStore.Audio.Media.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(0) ?: "Unknown Audio"
            }
        }
        if (name == "Unknown Audio") {
            name = DocumentFile.fromSingleUri(context, uri)?.name ?: "Status_Audio"
        }
    } catch (e: Exception) { name = "Status_Audio" }
    return name
}

@Composable
fun DownloadsFolderGrid(downloadedList: List<Uri>, favorites: Set<String>, onFolderSelected: (String) -> Unit) {
    val context = LocalContext.current
    val folders = listOf(
        FolderInfo("Images", Icons.Default.Image, downloadedList.count { context.contentResolver.getType(it)?.startsWith("image") == true }),
        FolderInfo("Videos", Icons.Default.VideoFile, downloadedList.count { context.contentResolver.getType(it)?.startsWith("video") == true }),
        FolderInfo("Audios", Icons.Default.MusicNote, downloadedList.count { 
            val type = context.contentResolver.getType(it) ?: ""
            type.startsWith("audio") || it.toString().lowercase().let { s -> s.contains(".mp3") || s.contains(".m4a") }
        }),
        FolderInfo("Favorites", Icons.Default.Favorite, downloadedList.count { favorites.contains(it.toString()) })
    )
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 0.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(8.dp))
        folders.chunked(2).forEach { rowFolders ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                rowFolders.forEach { folder ->
                    Box(Modifier.weight(1f)) {
                        ModernFolderItem(folder) { onFolderSelected(folder.name) }
                    }
                }
                if (rowFolders.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
fun ModernFolderItem(folder: FolderInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clickable { onClick() }, 
        shape = RoundedCornerShape(20.dp), 
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f), 
                shape = RoundedCornerShape(10.dp), 
                modifier = Modifier.size(40.dp)
            ) { 
                Icon(
                    folder.icon, 
                    null, 
                    tint = MaterialTheme.colorScheme.primary, 
                    modifier = Modifier
                        .padding(10.dp)
                        .size(20.dp)
                ) 
            }
            
            Column {
                Text(
                    folder.name, 
                    fontWeight = FontWeight.Black, 
                    fontSize = 15.sp, 
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${folder.count} items",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Default.ArrowForward,
                        null, 
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}

data class FolderInfo(val name: String, val icon: ImageVector, val count: Int)

@Composable
fun HomeDrawerContent(
    selectedTab: Int,
    onHomeClick: () -> Unit,
    onDownloadLinkClick: () -> Unit,
    onRecentDownloadsClick: () -> Unit,
    onDownloadsClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onHelpClick: () -> Unit
) {
    ModalDrawerSheet(Modifier.width(300.dp)) {
        Column(Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())) {
            DrawerHeader()
            Spacer(Modifier.height(8.dp))
            NavigationDrawerItem(label = { Text("Home") }, selected = selectedTab != 2, onClick = onHomeClick, icon = { Icon(Icons.Default.Home, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            NavigationDrawerItem(label = { Text("Download from Link") }, selected = false, onClick = onDownloadLinkClick, icon = { Icon(Icons.Default.Link, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            NavigationDrawerItem(label = { Text("Recent Downloads") }, selected = false, onClick = onRecentDownloadsClick, icon = { Icon(Icons.Default.History, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            NavigationDrawerItem(label = { Text("Saved Images") }, selected = false, onClick = onDownloadsClick, icon = { Icon(Icons.Default.Image, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            NavigationDrawerItem(label = { Text("Saved Videos") }, selected = false, onClick = onDownloadsClick, icon = { Icon(Icons.Default.VideoFile, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            NavigationDrawerItem(label = { Text("Saved Audios") }, selected = false, onClick = onDownloadsClick, icon = { Icon(Icons.Default.MusicNote, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            NavigationDrawerItem(label = { Text("Favorites") }, selected = false, onClick = onFavoritesClick, icon = { Icon(Icons.Default.Favorite, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            HorizontalDivider(Modifier.padding(vertical = 16.dp, horizontal = 24.dp))
            NavigationDrawerItem(label = { Text("Settings") }, selected = false, onClick = onSettingsClick, icon = { Icon(Icons.Default.Settings, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            NavigationDrawerItem(label = { Text("Help & FAQ") }, selected = false, onClick = onHelpClick, icon = { Icon(Icons.Default.Help, null) }, modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding))
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun DrawerHeader() {
    Column(Modifier.padding(24.dp)) {
        Image(painterResource(R.drawable.app_logo), null, Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp)))
        Spacer(Modifier.height(16.dp))
        Text("Status Hub", fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
        Text("Status Saver & Media Manager", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) {
            Text("1.3.0" , Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun PermissionRequiredContent(onGrantClick: () -> Unit) {
    Column(modifier = Modifier
        .fillMaxSize()
        .padding(32.dp)
        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(imageVector = Icons.Default.Info, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.outline)
        Spacer(Modifier.height(16.dp))
        Text("Permission Required", fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text("Grant permission to the WhatsApp Statuses folder to start viewing media.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
        Spacer(Modifier.height(24.dp))
        Button(onClick = onGrantClick, shape = RoundedCornerShape(50)) { Text("Grant Permission") }
    }
}

@Composable
fun EmptyStateContent(title: String, subtitle: String) {
    val context = LocalContext.current
    val themePref = getAppTheme(context)
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themePref) {
        THEME_LIGHT -> false
        THEME_DARK -> true
        else -> systemInDark
    }
    val illustration = if (isDark) R.drawable.no_status_found_dark else R.drawable.no_status_found_light
    Column(Modifier
        .fillMaxSize()
        .padding(horizontal = 16.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Image(painter = painterResource(id = illustration), contentDescription = null, modifier = Modifier
            .fillMaxWidth()
            .height(280.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.height(16.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
        if (subtitle.isNotEmpty()) Text(subtitle, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
    }
}

@Composable
fun PermissionInfoDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Follow these steps", fontWeight = FontWeight.Bold) }, text = { Column { Text("1. Click the button below."); Spacer(Modifier.height(8.dp)); Text("2. Click 'USE THIS FOLDER' at the bottom of your screen.", fontWeight = FontWeight.Bold); HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant); Text("Note: Folder is located at:", fontSize = 12.sp, color = MaterialTheme.colorScheme.outline); Text("Android > media > com.whatsapp > WhatsApp > Media > .Statuses", fontSize = 12.sp, fontWeight = FontWeight.Bold) } }, confirmButton = { Button(onClick = onConfirm, shape = RoundedCornerShape(50)) { Text("Grant Permission") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Not Now", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) } })
}

@Composable
fun DeleteConfirmationDialog(selectedCount: Int, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Delete Downloads", fontWeight = FontWeight.Bold) }, text = { Text("Are you sure you want to delete $selectedCount items?") }, confirmButton = { Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary), shape = RoundedCornerShape(50)) { Text("Delete") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)) } })
}
