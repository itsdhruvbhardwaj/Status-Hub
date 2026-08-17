package com.dhruv.status.hub.utils

import android.content.Context
import android.net.Uri

/**
 * Saves the URI of the selected WhatsApp statuses folder to SharedPreferences.
 */
fun saveFolderUri(context: Context, uri: Uri) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("folder_uri", uri.toString()).apply()
}

/**
 * Retrieves the saved folder URI from SharedPreferences.
 * Returns null if no URI has been saved yet.
 */
fun getSavedFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    val uriString = prefs.getString("folder_uri", null)
    return uriString?.let { Uri.parse(it) }
}

/**
 * Checks if the user has completed the onboarding process.
 */
fun isOnboardingComplete(context: Context): Boolean {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("onboarding_complete", false)
}

/**
 * Marks the onboarding process as complete.
 */
fun setOnboardingComplete(context: Context) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("onboarding_complete", true).apply()
}

/**
 * Checks if the Auto-Save Status feature is enabled.
 */
fun isAutoSaveEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("auto_save", false)
}

/**
 * Enables or disables the Auto-Save Status feature.
 */
fun setAutoSaveEnabled(context: Context, enabled: Boolean) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("auto_save", enabled).apply()
}

/**
 * Theme constants
 */
const val THEME_SYSTEM = "system"
const val THEME_LIGHT = "light"
const val THEME_DARK = "dark"

/**
 * Retrieves the current theme preference.
 */
fun getAppTheme(context: Context): String {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    // Legacy support: check for the old dark_mode boolean if app_theme is not set
    if (!prefs.contains("app_theme") && prefs.contains("dark_mode")) {
        val isDark = prefs.getBoolean("dark_mode", false)
        return if (isDark) THEME_DARK else THEME_LIGHT
    }
    return prefs.getString("app_theme", THEME_SYSTEM) ?: THEME_SYSTEM
}

/**
 * Saves the theme preference.
 */
fun setAppTheme(context: Context, theme: String) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("app_theme", theme).apply()
}

/**
 * Checks if the user has manually enabled Dark Mode in settings.
 * Deprecated: Use getAppTheme instead.
 */
@Deprecated("Use getAppTheme instead", ReplaceWith("getAppTheme(context) == THEME_DARK"))
fun isDarkModeEnabled(context: Context): Boolean {
    val theme = getAppTheme(context)
    return if (theme == THEME_SYSTEM) false else theme == THEME_DARK
}

/**
 * Updates the Dark Mode preference.
 * Deprecated: Use setAppTheme instead.
 */
@Deprecated("Use setAppTheme instead", ReplaceWith("setAppTheme(context, if (enabled) THEME_DARK else THEME_LIGHT)"))
fun setDarkModeEnabled(context: Context, enabled: Boolean) {
    setAppTheme(context, if (enabled) THEME_DARK else THEME_LIGHT)
}

/**
 * Checks if a specific file has already been auto-saved to prevent duplicates.
 * Uses a separate preference file for logging auto-saved filenames.
 */
fun isFileAlreadyAutoSaved(context: Context, fileName: String): Boolean {
    val prefs = context.getSharedPreferences("statushub_autosave_log", Context.MODE_PRIVATE)
    return prefs.contains(fileName)
}

/**
 * Logs a filename as auto-saved.
 */
fun markFileAsAutoSaved(context: Context, fileName: String) {
    val prefs = context.getSharedPreferences("statushub_autosave_log", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(fileName, true).apply()
}

/**
 * Retrieves the set of favorite media URIs.
 */
fun getFavorites(context: Context): Set<String> {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    return prefs.getStringSet("favorites", emptySet()) ?: emptySet()
}

/**
 * Toggles the favorite status of a media URI.
 */
fun toggleFavorite(context: Context, uri: String) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    val favorites = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
    if (favorites.contains(uri)) {
        favorites.remove(uri)
    } else {
        favorites.add(uri)
    }
    prefs.edit().putStringSet("favorites", favorites).apply()
}

/**
 * Checks if a URI is marked as favorite.
 */
fun isFavorite(context: Context, uri: String): Boolean {
    val favorites = getFavorites(context)
    return favorites.contains(uri)
}
