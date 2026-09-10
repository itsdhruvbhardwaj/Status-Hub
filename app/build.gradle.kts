import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

/*
 * ============================================================
 * LOCAL PROPERTIES
 * ============================================================
 *
 * local.properties:
 *
 * FASTSAVER_API_KEY=your_test_key_here
 * STATUS_HUB_API_BASE_URL=https://your-backend-domain.com
 *
 * IMPORTANT:
 * - FASTSAVER_API_KEY is used ONLY by DEBUG builds.
 * - RELEASE builds never receive the FastSaver API key.
 * - STATUS_HUB_API_BASE_URL is used by RELEASE builds.
 *
 * local.properties must NOT be committed to Git.
 */

val localProperties = Properties()

val localPropertiesFile =
    rootProject.file("local.properties")

if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use {
        localProperties.load(it)
    }
}

/*
 * Test FastSaver API key.
 *
 * This is intentionally used only for DEBUG.
 */
val fastSaverApiKey =
    localProperties
        .getProperty("FASTSAVER_API_KEY", "")
        .trim()

/*
 * Production Status Hub backend URL.
 *
 * Example:
 *
 * STATUS_HUB_API_BASE_URL=https://api.example.com
 */
val statusHubApiBaseUrl =
    localProperties
        .getProperty("STATUS_HUB_API_BASE_URL", "")
        .trim()

/*
 * ============================================================
 * BUILD CONFIG ESCAPING
 * ============================================================
 */

val escapedFastSaverApiKey =
    fastSaverApiKey
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

val escapedBackendUrl =
    statusHubApiBaseUrl
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")

android {

    namespace = "com.dhruv.status.hub"

    compileSdk = 36

    buildFeatures {
        compose = true
        buildConfig = true
    }

    defaultConfig {

        applicationId = "com.dhruv.status.hub"

        minSdk = 24

        targetSdk = 36

        versionCode = 11

        versionName = "1.4.2"

        testInstrumentationRunner =
            "androidx.test.runner.AndroidJUnitRunner"

        /*
         * ====================================================
         * FASTSAVER API KEY
         * ====================================================
         *
         * DEFAULT IS EMPTY.
         *
         * The DEBUG build overrides this below with the
         * test key from local.properties.
         *
         * RELEASE explicitly remains empty.
         */
        buildConfigField(
            "String",
            "FASTSAVER_API_KEY",
            "\"\""
        )

        /*
         * ====================================================
         * STATUS HUB BACKEND URL
         * ====================================================
         *
         * This is not a secret.
         *
         * RELEASE uses this URL to communicate with the
         * Status Hub backend.
         */
        buildConfigField(
            "String",
            "STATUS_HUB_API_BASE_URL",
            "\"$escapedBackendUrl\""
        )

        ndk {
            // Only include ARM architectures to save size.
            abiFilters.addAll(
                listOf(
                    "armeabi-v7a",
                    "arm64-v8a"
                )
            )
        }
    }

    buildTypes {

        /*
         * ====================================================
         * DEBUG
         * ====================================================
         *
         * Facebook / Instagram Story:
         *
         * Android
         *     ↓
         * FastSaver
         *
         * Uses the TEST FastSaver API key.
         */
        debug {

            buildConfigField(
                "String",
                "FASTSAVER_API_KEY",
                "\"$escapedFastSaverApiKey\""
            )
        }

        /*
         * ====================================================
         * RELEASE
         * ====================================================
         *
         * Facebook / Instagram Story:
         *
         * Android
         *     ↓
         * Status Hub Backend
         *     ↓
         * FastSaver
         *
         * IMPORTANT:
         * The production FastSaver API key is NOT included
         * in the Android Release build.
         */
        release {

            isMinifyEnabled = true

            isShrinkResources = true

            /*
             * Explicitly keep the FastSaver API key empty
             * in RELEASE.
             */
            buildConfigField(
                "String",
                "FASTSAVER_API_KEY",
                "\"\""
            )

            proguardFiles(
                getDefaultProguardFile(
                    "proguard-android-optimize.txt"
                ),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {

        sourceCompatibility =
            JavaVersion.VERSION_11

        targetCompatibility =
            JavaVersion.VERSION_11
    }

    kotlinOptions {

        jvmTarget = "11"
    }
}

dependencies {

    // ========================================================
    // AdMob
    // ========================================================

    implementation(
        libs.play.services.ads
    )

    // ========================================================
    // Google Play In-App Updates
    // ========================================================

    implementation(
        libs.play.app.update
    )

    // ========================================================
    // Networking
    // ========================================================

    implementation(
        libs.okhttp
    )

    implementation(
        libs.okhttp.logging
    )

    // ========================================================
    // NewPipe Extractor
    // ========================================================

    implementation(
        libs.newpipe.extractor
    )

    // ========================================================
    // Lifecycle & ViewModel
    // ========================================================

    implementation(
        libs.androidx.lifecycle.viewmodel.compose
    )

    implementation(
        libs.androidx.lifecycle.viewmodel.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.ktx
    )

    implementation(
        libs.androidx.lifecycle.runtime.compose
    )

    // ========================================================
    // Image & Video Loading
    // ========================================================

    implementation(
        libs.coil.compose
    )

    implementation(
        libs.coil.video
    )

    // ========================================================
    // Navigation
    // ========================================================

    implementation(
        libs.androidx.navigation.compose
    )

    // ========================================================
    // Coroutines
    // ========================================================

    implementation(
        libs.kotlinx.coroutines.android
    )

    // ========================================================
    // Media3 / ExoPlayer
    // ========================================================

    implementation(
        libs.media3.exoplayer
    )

    implementation(
        libs.media3.ui
    )

    // ========================================================
    // Room
    // ========================================================

    implementation(
        libs.androidx.room.runtime
    )

    implementation(
        libs.androidx.room.ktx
    )

    ksp(
        libs.androidx.room.compiler
    )

    // ========================================================
    // Compose
    // ========================================================

    implementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    implementation(
        libs.androidx.compose.ui
    )

    implementation(
        libs.androidx.compose.ui.graphics
    )

    implementation(
        libs.androidx.compose.ui.tooling.preview
    )

    implementation(
        libs.androidx.compose.material3
    )

    implementation(
        libs.icons.extended
    )

    // ========================================================
    // AndroidX Core
    // ========================================================

    implementation(
        libs.androidx.core.ktx
    )

    implementation(
        libs.androidx.activity.compose
    )

    implementation(
        libs.androidx.documentfile
    )

    // ========================================================
    // Testing
    // ========================================================

    testImplementation(
        libs.junit
    )

    androidTestImplementation(
        libs.androidx.junit
    )

    androidTestImplementation(
        libs.androidx.espresso.core
    )

    androidTestImplementation(
        platform(
            libs.androidx.compose.bom
        )
    )

    androidTestImplementation(
        libs.androidx.compose.ui.test.junit4
    )

    debugImplementation(
        libs.androidx.compose.ui.tooling
    )

    debugImplementation(
        libs.androidx.compose.ui.test.manifest
    )
}