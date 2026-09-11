package com.neverhide.empire.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.VibrationEffect
import android.os.Vibrator
import com.neverhide.empire.tools.siren.SirenService

/**
 * Battery guard — fires a loud alarm + vibration when the battery goes low,
 * and silences it when the battery recovers. Toggle: "low_battery_alarm" in
 * empire_prefs. This is the classic "battery low alarm" feature.
 */
class BatteryGuardReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("low_battery_alarm", false)) return

        when (intent.action) {
            Intent.ACTION_BATTERY_LOW -> {
                // Vibrate pattern: buzz-buzz-buzz
                vibrate(context)
                // Start the siren (foreground service with max-volume alarm tone)
                SirenService.start(context, mode = SirenService.MODE_BATTERY, autoStopSeconds = 15)
            }
            Intent.ACTION_BATTERY_OKAY -> {
                SirenService.stop(context)
            }
        }
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = longArrayOf(0, 400, 200, 400, 200, 400)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }
}
