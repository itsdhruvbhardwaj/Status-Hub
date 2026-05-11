package com.dhruv.status.hub.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * Dark Color Scheme definition.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Indigo40, // Using blue color for buttons/toggles in dark mode for better visibility
    secondary = IndigoGrey80,
    tertiary = IndigoPink80,
    background = BackgroundDark,
    surface = SurfaceDark,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onTertiary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
    primaryContainer = Color(0xFF2D2F31), // Sleek dark header
    onPrimaryContainer = Color.White
)

/**
 * Light Color Scheme definition.
 */
private val LightColorScheme = lightColorScheme(
    primary = Color.Black, // Buttons are black in light mode for a sleek look
    secondary = IndigoGrey40,
    tertiary = IndigoPink40,
    background = BackgroundLight,
    surface = BackgroundLight,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color.Black,
    onSurface = Color.Black,
    primaryContainer = Color(0xFFE6E0FF), // Light purple header
    onPrimaryContainer = Color.Black
)

/**
 * StatusHubTheme
 * 
 * The main theme composable for the application. It handles switching between
 * light and dark color schemes, and optionally supports dynamic colors on Android 12+.
 * 
 * @param darkTheme Whether the dark theme should be applied.
 * @param dynamicColor Whether to use dynamic color (Material You) on supported devices.
 * @param content The composable content to be themed.
 */
@Composable
fun StatusHubTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    // Select the appropriate color scheme
    val colorScheme = when {
        // Dynamic color is available on Android 12+ (API 31+)
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    // Apply the Material 3 Theme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
