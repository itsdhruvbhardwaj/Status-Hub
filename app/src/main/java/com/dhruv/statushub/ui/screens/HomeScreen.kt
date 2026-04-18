package com.dhruv.statushub.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.dhruv.statushub.ui.components.VideoPlayer
import com.dhruv.statushub.ui.theme.StatusHubTheme
import androidx.compose.animation.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.ui.input.pointer.pointerInput


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

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Images", "Videos", "Favorites")

    var selectedMedia by remember { mutableStateOf<Uri?>(null) }

    val savedUri = getSavedFolderUri(context)
    var folderUri by remember { mutableStateOf<Uri?>(savedUri) }

    var imageList by remember { mutableStateOf(listOf<Uri>()) }
    var videoList by remember { mutableStateOf(listOf<Uri>()) }

    // ================= FOLDER PICKER =================

    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->

        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data

            uri?.let {
                folderUri = it
                saveFolderUri(context, it)

                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        if (folderUri == null) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            folderPicker.launch(intent)
        }
    }

    // ================= LOAD DATA =================

    LaunchedEffect(folderUri) {
        folderUri?.let { uri ->

            val docFile = DocumentFile.fromTreeUri(context, uri)
            if (docFile == null) return@LaunchedEffect

            val images = mutableListOf<Uri>()
            val videos = mutableListOf<Uri>()

            val statusFolder = docFile.findFile(".Statuses")
                ?: docFile.findFile("Statuses")

            statusFolder?.listFiles()?.forEach { file ->
                if (file.type?.startsWith("image") == true) {
                    images.add(file.uri)
                } else if (file.type?.startsWith("video") == true) {
                    videos.add(file.uri)
                }
            }

            imageList = images.reversed()
            videoList = videos.reversed()
        }
    }

    // ================= UI =================

    Scaffold(
        topBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.White)
                    .shadow(6.dp) // 🔥 stronger shadow
            ) {
                TopAppBar(
                    title = {
                        Text(
                            text = "Status Hub",
                            fontWeight = FontWeight.Bold
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.White
                    )
                )
            }
        }
    ) { innerPadding ->

        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(Color(0xFFF5F5F5))
        ) {

            // ================= CONTENT =================

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {

                    if (targetState > initialState) {
                        // 👉 Forward (right to left)
                        slideInHorizontally(
                            initialOffsetX = { it }
                        ) + fadeIn(tween(20)) togetherWith
                                slideOutHorizontally(
                                    targetOffsetX = { -it }
                                ) + fadeOut(tween(20))
                    } else {
                        // 👉 Backward (left to right)
                        slideInHorizontally(
                            initialOffsetX = { -it }
                        ) + fadeIn(tween(20)) togetherWith
                                slideOutHorizontally(
                                    targetOffsetX = { it }
                                ) + fadeOut(tween(20))
                    }

                },
                label = "tab_slide_animation"
            ) { targetTab ->

                when (targetTab) {

                    0 -> {
                        if (imageList.isEmpty()) {
                            Text(
                                text = "No statuses found.\nOpen WhatsApp and view some statuses.",
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            ImageGrid(imageList) { uri ->
                                selectedMedia = uri
                            }
                        }
                    }

                    1 -> {
                        if (videoList.isEmpty()) {
                            Text(
                                text = "No videos found",
                                modifier = Modifier.align(Alignment.Center)
                            )
                        } else {
                            VideoGrid(videoList) { uri ->
                                selectedMedia = uri
                            }
                        }
                    }

                    2 -> Text("Favorites Screen", modifier = Modifier.padding(16.dp))
                }
            }

            // ================= BOTTOM TAB =================

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp)
                    .fillMaxWidth()
                    .shadow(12.dp, RoundedCornerShape(50))
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.7f))
                    .padding(6.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                tabs.forEachIndexed { index, title ->

                    val isSelected = selectedTab == index

                    val bgColor by animateColorAsState(
                        targetValue = if (isSelected) Color.Black else Color.Transparent,
                        label = ""
                    )

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(50)) // 🔥 MUST be before shadow
                            .then(
                                if (isSelected)
                                    Modifier.shadow(
                                        elevation = 6.dp,
                                        shape = RoundedCornerShape(50),
                                        clip = true // 🔥 prevents overflow
                                    )
                                else Modifier
                            )
                            .background(
                                color = bgColor,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable(
                                indication = null, // ❌ removes ripple
                                interactionSource = remember { MutableInteractionSource() }
                            ) {
                                selectedTab = index
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            color = if (isSelected) Color.White else Color.Black
                        )
                    }
                }
            }

            // ================= FULLSCREEN PREVIEW =================

            selectedMedia?.let { uri ->

                val isVideo = uri.toString().contains(".mp4", true)

                // 🔥 Back press closes preview
                BackHandler {
                    selectedMedia = null
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                ) {

                    if (isVideo) {

                        val currentIndex = videoList.indexOf(uri)

                        val pagerState = rememberPagerState(
                            initialPage = currentIndex.coerceAtLeast(0),
                            pageCount = { videoList.size }
                        )

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->

                            Box(modifier = Modifier.fillMaxSize()) {

                                VideoPlayer(
                                    uri = videoList[page],
                                    modifier = Modifier.fillMaxSize()
                                )

                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 20.dp, bottom = 90.dp) // 🔥 lifted above nav bar
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .clickable {
                                            // TODO: download
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp) // 🔥 bold size
                                    )
                                }
                            }
                        }
                    } else {

                        // 🖼 IMAGE PAGER
                        val currentIndex = imageList.indexOf(uri)

                        val pagerState = rememberPagerState(
                            initialPage = currentIndex.coerceAtLeast(0),
                            pageCount = { imageList.size }
                        )

                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->

                            Box(modifier = Modifier.fillMaxSize()) {

                                AsyncImage(
                                    model = imageList[page],
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // 🔥 Download button
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(end = 20.dp, bottom = 90.dp) // 🔥 lifted above nav bar
                                        .size(56.dp)
                                        .clip(RoundedCornerShape(50))
                                        .background(Color.Black.copy(alpha = 0.7f))
                                        .clickable {
                                            // TODO: download
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Download",
                                        tint = Color.White,
                                        modifier = Modifier.size(28.dp) // 🔥 bold size
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ================= PREVIEW =================

@Preview(showBackground = true)
@Composable
fun HomeScreenPreview() {
    StatusHubTheme {
        HomeScreen()
    }
}