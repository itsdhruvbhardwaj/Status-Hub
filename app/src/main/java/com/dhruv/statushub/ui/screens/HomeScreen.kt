package com.dhruv.statushub.ui.screens

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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import com.dhruv.statushub.ui.components.AdBanner
import com.dhruv.statushub.ui.components.InterstitialAdManager
import com.dhruv.statushub.ui.components.MediaPreviewer
import com.dhruv.statushub.ui.theme.StatusHubTheme
import com.dhruv.statushub.utils.getDownloadedMedia
import com.dhruv.statushub.utils.getSavedFolderUri
import com.dhruv.statushub.utils.saveFolderUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adManager = remember { InterstitialAdManager(context) }

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Images", "Videos", "Downloads")

    var selectedMedia by remember { mutableStateOf<Uri?>(null) }

    val savedUri = getSavedFolderUri(context)
    var folderUri by remember { mutableStateOf<Uri?>(savedUri) }

    var imageList by remember { mutableStateOf(listOf<Uri>()) }
    var videoList by remember { mutableStateOf(listOf<Uri>()) }
    var downloadedList by remember { mutableStateOf(listOf<Uri>()) }

    var isRefreshing by remember { mutableStateOf(false) }
    val pullToRefreshState = rememberPullToRefreshState()

    val selectedItems = remember { mutableStateOf(setOf<Uri>()) }
    val isSelectionMode = selectedItems.value.isNotEmpty()
    
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showPermissionInfoDialog by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        adManager.loadAd()
    }

    // ================= FOLDER PICKER =================

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
        val initialUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val authority = "com.android.externalstorage.documents"
            val documentId = "primary:Android/media/com.whatsapp/WhatsApp/Media"
            DocumentsContract.buildTreeDocumentUri(authority, documentId)
        } else null
        
        folderPicker.launch(initialUri)
    }

    LaunchedEffect(Unit) {
        if (folderUri == null) {
            showPermissionInfoDialog = true
        }
    }

    // ================= LOAD DATA FUNCTION =================

    val loadData: suspend () -> Unit = {
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

                                var isImage = mimeType.startsWith("image") ||
                                        fileName.endsWith(".jpg") ||
                                        fileName.endsWith(".jpeg") ||
                                        fileName.endsWith(".png") ||
                                        fileName.endsWith(".webp") ||
                                        fileName.endsWith(".heic")

                                var isVideo = mimeType.startsWith("video") ||
                                        fileName.endsWith(".mp4") ||
                                        fileName.endsWith(".mkv") ||
                                        fileName.endsWith(".3gp") ||
                                        fileName.endsWith(".webm") ||
                                        fileName.endsWith(".mov") ||
                                        fileName.endsWith(".avi")

                                if (!isImage && !isVideo) {
                                    val retriever = MediaMetadataRetriever()
                                    try {
                                        retriever.setDataSource(context, file.uri)
                                        val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                                        if (hasVideo == "yes") {
                                            isVideo = true
                                        } else {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                                                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH)
                                                if (width != null) isImage = true
                                            } else {
                                                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                                                if (width == null) isImage = true
                                            }
                                        }
                                    } catch (e: Exception) {
                                    } finally {
                                        try { retriever.release() } catch (e: Exception) {}
                                    }
                                }

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
    }

    LaunchedEffect(folderUri) {
        loadData()
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

    BackHandler(isSelectionMode) {
        selectedItems.value = emptySet()
    }

    // ================= UI =================

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(shadowElevation = 4.dp) {
                    TopAppBar(
                        title = {
                            if (isSelectionMode) {
                                Text("${selectedItems.value.size} Selected", fontWeight = FontWeight.Bold)
                            } else {
                                Text(
                                    text = "Status Hub",
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Cursive,
                                    fontSize = 28.sp,
                                    color = Color.Black
                                )
                            }
                        },
                        actions = {
                            if (isSelectionMode) {
                                IconButton(onClick = { showDeleteDialog = true }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Black)
                                }
                            } else {
                                IconButton(onClick = { launchFolderPicker() }) {
                                    Icon(Icons.Default.FolderOpen, contentDescription = "Change Folder", tint = Color.Black)
                                }
                                Box {
                                    IconButton(onClick = { showMenu = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "More Options", tint = Color.Black)
                                    }
                                    DropdownMenu(
                                        expanded = showMenu,
                                        onDismissRequest = { showMenu = false },
                                        containerColor = Color.White
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("How to Use / Help", color = Color.Black) },
                                            onClick = {
                                                showMenu = false
                                                showPermissionInfoDialog = true
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Privacy Policy", color = Color.Black) },
                                            onClick = {
                                                showMenu = false
                                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                                                context.startActivity(intent)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Share App", color = Color.Black) },
                                            onClick = {
                                                showMenu = false
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_SUBJECT, "Status Hub App")
                                                    putExtra(Intent.EXTRA_TEXT, "Check out this amazing WhatsApp Status Saver app!")
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "Share via"))
                                            }
                                        )
                                    }
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(0xFFE6E0FF),
                            titleContentColor = Color.Black
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
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        scope.launch {
                            isRefreshing = true
                            loadData()
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
                            .background(Color(0xFFF5F5F5))
                    ) {

                        if (folderUri == null) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(32.dp),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info, 
                                    contentDescription = null, 
                                    modifier = Modifier.size(48.dp), 
                                    tint = Color.Gray
                                )
                                Spacer(Modifier.height(16.dp))
                                Text("Permission Required", fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center, color = Color.Black)
                                Spacer(Modifier.height(8.dp))
                                Text("Grant permission to the WhatsApp Statuses folder to start viewing media.", textAlign = TextAlign.Center, color = Color.DarkGray)
                                Spacer(Modifier.height(24.dp))
                                Button(
                                    onClick = { showPermissionInfoDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                                    shape = RoundedCornerShape(50)
                                ) {
                                    Text("Grant Permission", color = Color.White)
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize()) {
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
                                                    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                                                        Text("No statuses found.", color = Color.Gray)
                                                        Text("Make sure you've viewed statuses in WhatsApp.", fontSize = 12.sp, color = Color.LightGray)
                                                    }
                                                } else {
                                                    ImageGrid(imageList) { uri ->
                                                        selectedMedia = uri
                                                    }
                                                }
                                            }
                                            1 -> {
                                                if (videoList.isEmpty()) {
                                                    Text("No videos found", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
                                                } else {
                                                    VideoGrid(videoList) { uri ->
                                                        selectedMedia = uri
                                                    }
                                                }
                                            }
                                            2 -> {
                                                if (downloadedList.isEmpty()) {
                                                    Text("No downloads yet.", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
                                                } else {
                                                    MediaGrid(
                                                        mediaList = downloadedList,
                                                        selectedItems = selectedItems.value,
                                                        onItemClick = { uri ->
                                                            if (isSelectionMode) {
                                                                if (selectedItems.value.contains(uri)) {
                                                                    selectedItems.value -= uri
                                                                } else {
                                                                    selectedItems.value += uri
                                                                }
                                                            } else {
                                                                selectedMedia = uri
                                                            }
                                                        },
                                                        onItemLongClick = { uri ->
                                                            if (selectedItems.value.contains(uri)) {
                                                                selectedItems.value -= uri
                                                            } else {
                                                                selectedItems.value += uri
                                                            }
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Bottom Navigation
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(horizontal = 12.dp, vertical = 20.dp)
                                .fillMaxWidth()
                                .shadow(12.dp, RoundedCornerShape(50))
                                .clip(RoundedCornerShape(50))
                                .background(Color.White.copy(alpha = 0.9f))
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            tabs.forEachIndexed { index, title ->
                                val isSelected = selectedTab == index
                                val bgColor = if (isSelected) Color.Black else Color.Transparent
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(4.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(color = bgColor, shape = RoundedCornerShape(50))
                                        .clickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() }
                                        ) {
                                            selectedTab = index
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = title,
                                        color = if (isSelected) Color.White else Color.Black,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                    }
                }
                AdBanner()
            }
        }

        // ================= IMPROVED PERMISSION DIALOG =================
        if (showPermissionInfoDialog) {
            AlertDialog(
                onDismissRequest = { showPermissionInfoDialog = false },
                containerColor = Color.White,
                shape = RoundedCornerShape(28.dp),
                title = { 
                    Text(
                        text = "Follow these steps", 
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    ) 
                },
                text = { 
                    Column {
                        Text("1. Click the button below.", color = Color.Black)
                        Spacer(Modifier.height(8.dp))
                        Text("2. Look for the folder named '.Statuses'.", color = Color.Black)
                        Spacer(Modifier.height(8.dp))
                        Text("3. Click 'USE THIS FOLDER' at the bottom of your screen.", color = Color.Black, fontWeight = FontWeight.Bold)
                        
                        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = Color.LightGray)
                        
                        Text("Note: If you don't see the folder, it's located at:", fontSize = 12.sp, color = Color.Gray)
                        Text("Android > media > com.whatsapp > WhatsApp > Media > .Statuses", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.DarkGray)
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showPermissionInfoDialog = false
                            launchFolderPicker()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Grant Permission", color = Color.White)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showPermissionInfoDialog = false }) {
                        Text("Not Now", color = Color.Black.copy(alpha = 0.6f))
                    }
                }
            )
        }

        if (showDeleteDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                containerColor = Color.White,
                shape = RoundedCornerShape(28.dp),
                title = { 
                    Text(text = "Delete Downloads", color = Color.Black, fontWeight = FontWeight.Bold) 
                },
                text = { 
                    Text(text = "Are you sure you want to delete ${selectedItems.value.size} items?", color = Color.Black) 
                },
                confirmButton = {
                    Button(
                        onClick = {
                            deleteSelectedItems()
                            showDeleteDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black, contentColor = Color.White),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteDialog = false }) {
                        Text("Cancel", color = Color.Black.copy(alpha = 0.6f))
                    }
                }
            )
        }

        selectedMedia?.let { uri ->
            val currentList = when {
                selectedTab == 2 -> downloadedList
                context.contentResolver.getType(uri)?.startsWith("video") == true || 
                uri.toString().lowercase().contains(".mp4") -> videoList
                else -> imageList
            }

            MediaPreviewer(
                selectedMedia = uri,
                mediaList = currentList,
                onClose = { selectedMedia = null },
                adManager = adManager,
                showDownloadButton = selectedTab != 2
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    StatusHubTheme { HomeScreen() }
}
