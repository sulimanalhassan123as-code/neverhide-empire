package com.neverhide.empire.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.neverhide.empire.tools.eyecare.EyeCareService
import com.neverhide.empire.tools.siren.SirenService

/**
 * Restarts every Empire component after boot, app update, or package replace.
 * This is what makes the whole suite feel "always alive" to the user.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val okay = action == Intent.ACTION_BOOT_COMPLETED ||
                action == "android.intent.action.QUICKBOOT_POWERON" ||
                action == Intent.ACTION_MY_PACKAGE_REPLACED
        if (!okay) return

        // 1. Heartbeat watchdog
        EmpireBackgroundService.start(context)

        // 2. Restore user-enabled tools
        val prefs = context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)

        if (prefs.getBoolean("eye_care_enabled", false)) {
            EyeCareService.start(context)
        }
        if (prefs.getBoolean("privacy_screen_enabled", false)) {
            com.neverhide.empire.tools.privacy.PrivacyScreenService.start(context)
        }
        if (prefs.getBoolean("low_battery_alarm", false)) {
            // Nothing to start — BatteryGuardReceiver is manifest-registered.
        }
        if (prefs.getBoolean("sim_guard_enabled", false)) {
            // SimGuardReceiver is manifest-registered too; just note the boot.
        }
    }
}
