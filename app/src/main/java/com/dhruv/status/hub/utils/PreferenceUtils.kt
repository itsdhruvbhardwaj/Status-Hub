package com.dhruv.status.hub.utils

import android.content.Context
import android.net.Uri

fun saveFolderUri(context: Context, uri: Uri) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putString("folder_uri", uri.toString()).apply()
}

fun getSavedFolderUri(context: Context): Uri? {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    val uriString = prefs.getString("folder_uri", null)
    return uriString?.let { Uri.parse(it) }
}

fun isOnboardingComplete(context: Context): Boolean {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("onboarding_complete", false)
}

fun setOnboardingComplete(context: Context) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("onboarding_complete", true).apply()
}

fun isAutoSaveEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("auto_save", false)
}

fun setAutoSaveEnabled(context: Context, enabled: Boolean) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("auto_save", enabled).apply()
}

fun isDarkModeEnabled(context: Context): Boolean {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    return prefs.getBoolean("dark_mode", false)
}

fun setDarkModeEnabled(context: Context, enabled: Boolean) {
    val prefs = context.getSharedPreferences("statushub_prefs", Context.MODE_PRIVATE)
    prefs.edit().putBoolean("dark_mode", enabled).apply()
}

fun isFileAlreadyAutoSaved(context: Context, fileName: String): Boolean {
    val prefs = context.getSharedPreferences("statushub_autosave_log", Context.MODE_PRIVATE)
    return prefs.contains(fileName)
}

fun markFileAsAutoSaved(context: Context, fileName: String) {
    val prefs = context.getSharedPreferences("statushub_autosave_log", Context.MODE_PRIVATE)
    prefs.edit().putBoolean(fileName, true).apply()
}
