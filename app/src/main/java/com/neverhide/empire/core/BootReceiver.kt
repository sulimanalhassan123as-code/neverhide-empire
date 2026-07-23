package com.neverhide.empire.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the Empire background service after device reboot. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            EmpireBackgroundService.start(context)
        }
    }
}
