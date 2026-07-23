package com.neverhide.empire.core

import android.content.Context

/**
 * Tracks whether we have already shown the one-shot permission batch, so we
 * only bombard the user with dialogs on the very first launch.
 */
object PermissionManager {
    private const val PREFS = "empire_prefs"
    private const val KEY_ASKED = "asked_once"

    fun hasAskedOnce(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ASKED, true).apply()
    }
}
