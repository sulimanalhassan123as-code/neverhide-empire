package com.neverhide.empire.ghost

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.neverhide.empire.MainActivity

/**
 * 👻 GHOST MODE — hide the Empire's launcher icon completely.
 *
 * How it works: the launcher icon lives on a manifest activity-alias
 * (.GhostAlias, MAIN/LAUNCHER) that targets MainActivity. Hiding =
 * disabling that alias with PackageManager (the standard Android
 * mechanism). MainActivity itself stays fully functional — updates,
 * Guardian, Vault, alerts all keep running invisibly.
 *
 * Return gate: dial the secret code in the stock phone dialer; the
 * SECRET_CODE broadcast re-enables the alias and opens the app.
 *
 * Honest limits (documented, never hidden): the app still appears in
 * Settings → Apps — no non-root app can remove that. The watchdog
 * notification remains visible in the shade. A factory reset or
 * uninstall clears the component state (icon returns).
 *
 * SAFETY: the code is stored in plain prefs AND the owner's private
 * chat history with Lyra. The dialer code must ALWAYS be shown on the
 * toggle screen before hiding.
 */
object GhostMode {
    const val DIAL_CODE = "*#*#2001#*#*"
    const val SECRET_HOST = "2001"
    const val SECRET_ACTION = "android.provider.Telephony.SECRET_CODE"
    private const val PREFS = "empire_prefs"
    private const val KEY = "ghost_mode"

    /** The real Empire launcher alias (shown in NORMAL mode). */
    fun realAlias(context: Context): ComponentName =
        ComponentName(context, "${context.packageName}.GhostAlias")

    /** The Calculator-disguise launcher alias (shown in GHOST mode). */
    fun decoyAlias(context: Context): ComponentName =
        ComponentName(context, "${context.packageName}.GhostCalcAlias")

    /** True when the icon is currently disguised as Calculator. */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    /** Swap the visible launcher icon from Empire -> Calculator disguise. */
    fun hide(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            decoyAlias(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        context.packageManager.setComponentEnabledSetting(
            realAlias(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, true).apply()
    }

    /** Swap the visible launcher icon back from Calculator disguise -> Empire. */
    fun reveal(context: Context) {
        context.packageManager.setComponentEnabledSetting(
            realAlias(context), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
        )
        context.packageManager.setComponentEnabledSetting(
            decoyAlias(context), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, false).apply()
    }

    fun openApp(context: Context) {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
