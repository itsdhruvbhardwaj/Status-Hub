package com.dhruv.status.hub

import android.Manifest
import android.app.Activity.RESULT_OK
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dhruv.status.hub.ui.screens.DownloadFromLinkScreen
import com.dhruv.status.hub.ui.screens.HomeScreen
import com.dhruv.status.hub.ui.screens.OnboardingScreen
import com.dhruv.status.hub.ui.screens.RecentDownloadsScreen
import com.dhruv.status.hub.ui.theme.StatusHubTheme
import com.dhruv.status.hub.ui.viewmodels.DownloadViewModel
import com.dhruv.status.hub.utils.*
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.play.core.ktx.isFlexibleUpdateAllowed
import com.google.android.play.core.ktx.installStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * MainActivity
 * 
 * The main entry point of the application. It handles initialization,
 * onboarding flow, and theme management.
 */
class MainActivity : ComponentActivity() {
    
    private lateinit var appUpdateManager: AppUpdateManager
    private val sharedUrlState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        appUpdateManager = AppUpdateManagerFactory.create(this)
        
        DownloadManager.init(this)
        handleIntent(intent)

        CoroutineScope(Dispatchers.IO).launch {
            MobileAds.initialize(this@MainActivity) {
                val testDeviceIds = listOf("c14c5401-8498-42be-bb07-acecd71fe275")
                val configuration = RequestConfiguration.Builder()
                    .setTestDeviceIds(testDeviceIds)
                    .build()
                MobileAds.setRequestConfiguration(configuration)
                AdsManager.loadInterstitial(this@MainActivity)
            }
        }

        enableEdgeToEdge()
        
        setContent {
            val context = LocalContext.current
            val systemInDarkTheme = isSystemInDarkTheme()
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            
            // Get ViewModel
            val downloadViewModel: DownloadViewModel = viewModel()

            // Update Listener for Flexible Updates
            val installStateListener = remember {
                InstallStateUpdatedListener { state ->
                    if (state.installStatus == InstallStatus.DOWNLOADED) {
                        scope.launch {
                            val result = snackbarHostState.showSnackbar(
                                message = "An update has just been downloaded.",
                                actionLabel = "RESTART"
                            )
                            if (result == SnackbarResult.ActionPerformed) {
                                appUpdateManager.completeUpdate()
                            }
                        }
                    }
                }
            }

            DisposableEffect(Unit) {
                appUpdateManager.registerListener(installStateListener)
                onDispose {
                    appUpdateManager.unregisterListener(installStateListener)
                }
            }

            val updateLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult()
            ) { result ->
                if (result.resultCode != RESULT_OK) {
                    // Update failed or cancelled by user
                }
            }

            LaunchedEffect(Unit) {
                appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
                    if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                        info.isFlexibleUpdateAllowed
                    ) {
                        appUpdateManager.startUpdateFlowForResult(
                            info,
                            updateLauncher,
                            AppUpdateOptions.defaultOptions(AppUpdateType.FLEXIBLE)
                        )
                    }
                }
            }

            // Request Permissions (Notifications & Storage)
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { /* Syncing disabled to prevent fetching older files */ }

            LaunchedEffect(Unit) {
                val permissions = mutableListOf<String>()
                
                // Notification Permission (Android 13+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // Storage Permissions
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                        permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED)
                        permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
                } else {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                        permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                    }
                }

                if (permissions.isNotEmpty()) {
                    permissionLauncher.launch(permissions.toTypedArray())
                }
            }
            
            var currentThemePref by remember { 
                mutableStateOf(getAppTheme(context)) 
            }

            val themeRefreshTrigger = remember { mutableIntStateOf(0) }
            
            LaunchedEffect(themeRefreshTrigger.value) {
                currentThemePref = getAppTheme(context)
            }

            val useDarkTheme = when (currentThemePref) {
                THEME_LIGHT -> false
                THEME_DARK -> true
                else -> systemInDarkTheme
            }

            var currentScreen by remember { mutableStateOf("home") }
            val sharedUrl by sharedUrlState

            LaunchedEffect(sharedUrl) {
                if (sharedUrl != null) {
                    currentScreen = "download_link"
                }
            }

            StatusHubTheme(darkTheme = useDarkTheme) {
                var onboardingFinished by remember { 
                    mutableStateOf(isOnboardingComplete(context)) 
                }
                
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    if (onboardingFinished) {
                        when (currentScreen) {
                            "home" -> {
                                HomeScreen(
                                    onThemeChange = { themeRefreshTrigger.value += 1 },
                                    onNavigateToDownloadLink = { 
                                        sharedUrlState.value = null
                                        currentScreen = "download_link" 
                                    },
                                    onNavigateToRecentDownloads = { currentScreen = "recent_downloads" }
                                )
                            }
                            "download_link" -> {
                                DownloadFromLinkScreen(
                                    initialUrl = sharedUrl,
                                    onBack = { 
                                        sharedUrlState.value = null
                                        currentScreen = "home" 
                                    },
                                    onNavigateToRecentDownloads = { currentScreen = "recent_downloads" },
                                    onThemeChange = { themeRefreshTrigger.value += 1 }
                                )
                            }
                            "recent_downloads" -> {
                                RecentDownloadsScreen(
                                    onBack = { currentScreen = "home" },
                                    onThemeChange = { themeRefreshTrigger.value += 1 },
                                    viewModel = downloadViewModel
                                )
                            }
                        }
                    } else {
                        OnboardingScreen(
                            onContinue = {
                                setOnboardingComplete(context)
                                onboardingFinished = true
                            }
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        appUpdateManager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus == InstallStatus.DOWNLOADED) {
                // Update already downloaded
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = IntentUtils.extractUrlFromIntent(intent)
        if (url != null) {
            sharedUrlState.value = url
        }
    }
}