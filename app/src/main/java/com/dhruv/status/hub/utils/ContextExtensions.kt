package com.dhruv.status.hub.utils

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * Extension function for Context to find the nearest Activity.
 * 
 * This is useful in Jetpack Compose when an Activity instance is needed 
 * (e.g., for showing ads or requesting permissions) from a Composable where 
 * only LocalContext.current is easily available.
 * 
 * @return The Activity instance if found, otherwise null.
 */
fun Context.findActivity(): Activity? {
    var context = this
    // Iterate through context wrappers to find the underlying Activity
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}
