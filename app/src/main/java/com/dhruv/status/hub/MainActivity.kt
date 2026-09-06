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
import com.dhruv.status.hub.ui.screens.DownloadFromLinkScreen
import com.dhruv.status.hub.ui.screens.HomeScreen
import com.dhruv.status.hub.ui.screens.OnboardingScreen
import com.dhruv.status.hub.ui.screens.RecentDownloadsScreen
import com.dhruv.status.hub.ui.theme.StatusHubTheme
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

            // Request Notification Permission for Android 13+
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
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
                                    onNavigateToRecentDownloads = { currentScreen = "recent_downloads" }
                                )
                            }
                            "recent_downloads" -> {
                                RecentDownloadsScreen(
                                    onBack = { currentScreen = "home" }
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
                // If update is already downloaded, it will trigger the listener or handle here if needed.
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
