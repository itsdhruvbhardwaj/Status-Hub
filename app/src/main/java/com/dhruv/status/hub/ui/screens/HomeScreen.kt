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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import com.dhruv.status.hub.ui.components.AdBanner
import com.dhruv.status.hub.ui.components.InterstitialAdManager
import com.dhruv.status.hub.ui.components.MediaPreviewer
import com.dhruv.status.hub.utils.getDownloadedMedia
import com.dhruv.status.hub.utils.getSavedFolderUri
import com.dhruv.status.hub.utils.saveFolderUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Custom Folder Icon to avoid large material-icons-extended dependency
val FolderIcon: ImageVector
    get() = ImageVector.Builder(
        name = "CustomFolder",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).path(fill = androidx.compose.ui.graphics.SolidColor(Color.Black)) {
        moveTo(10f, 4f)
        lineTo(4f, 4f)
        curveTo(2.9f, 4f, 2.01f, 4.9f, 2.01f, 6f)
        lineTo(2f, 18f)
        curveTo(2f, 19.1f, 2.9f, 20f, 4f, 20f)
        horizontalLineTo(20f)
        curveTo(21.1f, 20f, 22f, 19.1f, 22f, 18f)
        lineTo(22f, 8f)
        curveTo(22f, 6.9f, 21.1f, 6f, 20f, 6f)
        horizontalLineTo(12f)
        lineTo(10f, 4f)
        close()
    }.build()

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

                                var isImage = mimeType.startsWith("image") ||
                                        fileName.endsWith(".jpg") ||
                                        fileName.endsWith(".jpeg") ||
                                        fileName.endsWith(".png") ||
                                        fileName.endsWith(".webp") ||
                                        fileName.endsWith(".heic") ||
                                        fileName.endsWith(".heif")

                                var isVideo = mimeType.startsWith("video") ||
                                        fileName.endsWith(".mp4") ||
                                        fileName.endsWith(".mkv") ||
                                        fileName.endsWith(".3gp") ||
                                        fileName.endsWith(".webm") ||
                                        fileName.endsWith(".mov") ||
                                        fileName.endsWith(".avi") ||
                                        fileName.endsWith(".m4v") ||
                                        fileName.contains("vid_") // Some social media videos have this pattern

                                if (!isImage && !isVideo && !fileName.endsWith(".nomedia")) {
                                    val retriever = MediaMetadataRetriever()
                                    try {
                                        retriever.setDataSource(context, file.uri)
                                        val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                                        val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                                        
                                        if (hasVideo == "yes" || hasAudio == "yes") {
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
                                    } catch (_: Exception) {
                                    } finally {
                                        try { retriever.release() } catch (_: Exception) {}
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

    BackHandler(isSelectionMode) {
        selectedItems.value = emptySet()
    }

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
                                                val intent = Intent(Intent.ACTION_VIEW, "http://sites.google.com/view/status-hub-privacy-policy/home".toUri())
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
                                                    putExtra(Intent.EXTRA_TEXT, "Check out this amazing WhatsApp Status Saver app! Download it here: https://play.google.com/store/apps/details?id=${context.packageName}")
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
                            .background(Color(0xFFF5F5F5))
                    ) {

                        if (folderUri == null) {
                            Column(
                                modifier = Modifier.fillMaxSize().padding(32.dp).verticalScroll(rememberScrollState()),
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
                        } else if (isLoadingFirstTime && imageList.isEmpty() && videoList.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = Color.Black)
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
                                                    Column(
                                                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()), 
                                                        Arrangement.Center, 
                                                        Alignment.CenterHorizontally
                                                    ) {
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
                                                    Column(
                                                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                                        Arrangement.Center,
                                                        Alignment.CenterHorizontally
                                                    ) {
                                                        Text("No videos found", color = Color.Gray)
                                                    }
                                                } else {
                                                    VideoGrid(videoList) { uri ->
                                                        selectedMedia = uri
                                                    }
                                                }
                                            }
                                            2 -> {
                                                if (downloadedList.isEmpty()) {
                                                    Column(
                                                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                                        Arrangement.Center,
                                                        Alignment.CenterHorizontally
                                                    ) {
                                                        Text("No downloads yet.", color = Color.Gray)
                                                    }
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
                        Text("2. Click 'USE THIS FOLDER' at the bottom of your screen.", color = Color.Black, fontWeight = FontWeight.Bold)
                        
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
