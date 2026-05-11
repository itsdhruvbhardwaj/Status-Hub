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
 * Checks if the user has manually enabled Dark Mode in settings.
 */
fun isDarkModeEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("dark_mode", false)
}

/**
 * Updates the Dark Mode preference.
 */
fun setDarkModeEnabled(context: Context, enabled: Boolean) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("dark_mode", enabled).apply()
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
