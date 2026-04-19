package com.dhruv.statushub.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.dhruv.statushub.ui.components.VideoPlayer
import com.dhruv.statushub.ui.theme.StatusHubTheme
import com.dhruv.statushub.utils.downloadMedia
import com.dhruv.statushub.utils.getDownloadedMedia
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ================= AD COMPONENT =================

@Composable
fun AdBanner(modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            AdView(context).apply {
                setAdSize(AdSize.BANNER)
                // Real Ad Unit ID
                adUnitId = "ca-app-pub-3940256099942544/6300978111"
                loadAd(AdRequest.Builder().build())
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(50.dp) // Ensures visibility once ads start filling
    )
}

// Interstitial Ad Helper
class InterstitialAdManager(private val context: Context) {
    private var interstitialAd: InterstitialAd? = null

    fun loadAd() {
        val adRequest = AdRequest.Builder().build()
        // Real Interstitial Ad Unit ID
        InterstitialAd.load(context, "ca-app-pub-3940256099942544/1033173712", adRequest, object : InterstitialAdLoadCallback() {
            override fun onAdFailedToLoad(adError: LoadAdError) {
                interstitialAd = null
            }

            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialAd = ad
            }
        })
    }

    fun showAd(activity: Activity, onAdDismissed: () -> Unit) {
        if (interstitialAd != null) {
            interstitialAd?.fullScreenContentCallback = object : FullScreenContentCallback() {
                override fun onAdDismissedFullScreenContent() {
                    interstitialAd = null
                    loadAd() // Load next one
                    onAdDismissed()
                }

                override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                    interstitialAd = null
                    onAdDismissed()
                }
            }
            interstitialAd?.show(activity)
        } else {
            onAdDismissed()
        }
    }
}

// ================= STORAGE =================

fun saveFolderUri(context: Context, uri: Uri) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("folder_uri", uri.toString()).apply()
}

fun getSavedFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    val uriString = prefs.getString("folder_uri", null)
    return uriString?.let { Uri.parse(it) }
}

// ================= MAIN SCREEN =================

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

    // Initialize Interstitial Ad
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
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val authority = "com.android.externalstorage.documents"
            val documentId = "primary:Android/media/com.whatsapp/WhatsApp/Media"
            val uri = DocumentsContract.buildTreeDocumentUri(authority, documentId)
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
            }
        } else {
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
        }
        folderPicker.launch(null)
    }

    LaunchedEffect(Unit) {
        if (folderUri == null) {
            launchFolderPicker()
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
        }
    }

    // ================= UI =================

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                Surface(shadowElevation = 4.dp) {
                    TopAppBar(
                        title = {
                            Text(
                                text = "Status Hub",
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Cursive,
                                fontSize = 28.sp,
                                color = Color.Black
                            )
                        },
                        actions = {
                            IconButton(onClick = { launchFolderPicker() }) {
                                Icon(Icons.Default.FolderOpen, contentDescription = "Change Folder", tint = Color.Black)
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color.White,
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
                // Content with Pull to Refresh
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
                                Text("Permission Required", fontWeight = FontWeight.Bold, fontSize = 20.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(8.dp))
                                Text("To view statuses, please select the WhatsApp Statuses folder.\n\nPath: Android > media > com.whatsapp > WhatsApp > Media > .Statuses", textAlign = TextAlign.Center, color = Color.Gray)
                                Spacer(Modifier.height(24.dp))
                                Button(onClick = { launchFolderPicker() }) {
                                    Text("Select Folder")
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
                                                        adManager.showAd(context as Activity) {
                                                            selectedMedia = uri
                                                        }
                                                    }
                                                }
                                            }
                                            1 -> {
                                                if (videoList.isEmpty()) {
                                                    Text("No videos found", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
                                                } else {
                                                    VideoGrid(videoList) { uri ->
                                                        adManager.showAd(context as Activity) {
                                                            selectedMedia = uri
                                                        }
                                                    }
                                                }
                                            }
                                            2 -> {
                                                if (downloadedList.isEmpty()) {
                                                    Text("No downloads yet.", modifier = Modifier.align(Alignment.Center), color = Color.Gray)
                                                } else {
                                                    MediaGrid(downloadedList) { uri ->
                                                        selectedMedia = uri
                                                    }
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

                // Real Banner Ad
                AdBanner(modifier = Modifier.background(Color.White))
            }
        }

        // ================= FULLSCREEN PREVIEW =================
        selectedMedia?.let { uri ->
            val isVideo = context.contentResolver.getType(uri)?.startsWith("video") == true || 
                         uri.toString().lowercase().contains(".mp4") ||
                         uri.toString().lowercase().contains(".mkv") ||
                         uri.toString().lowercase().contains(".3gp") ||
                         uri.toString().lowercase().contains(".mov")

            BackHandler { selectedMedia = null }

            Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
                val currentList = when {
                    selectedTab == 2 -> downloadedList
                    isVideo -> videoList
                    else -> imageList
                }
                val currentIndex = currentList.indexOf(uri)
                val pagerState = rememberPagerState(
                    initialPage = currentIndex.coerceAtLeast(0),
                    pageCount = { currentList.size }
                )

                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    val itemUri = currentList[page]
                    val itemMime = context.contentResolver.getType(itemUri)?.lowercase() ?: ""
                    val isItemVideo = itemMime.startsWith("video") || itemUri.toString().lowercase().contains(".mp4")

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isItemVideo) {
                            VideoPlayer(uri = itemUri, modifier = Modifier.fillMaxSize())
                        } else {
                            AsyncImage(model = itemUri, contentDescription = null, modifier = Modifier.fillMaxSize())
                        }

                        if (selectedTab != 2) {
                            Box(
                                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 40.dp).size(56.dp)
                                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.7f))
                                    .clickable { downloadMedia(context, itemUri) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                }

                IconButton(
                    onClick = { selectedMedia = null },
                    modifier = Modifier.align(Alignment.TopStart).padding(top = 16.dp, start = 16.dp).statusBarsPadding()
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape).size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    StatusHubTheme { HomeScreen() }
}
