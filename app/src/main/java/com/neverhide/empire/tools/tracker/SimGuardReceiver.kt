package com.neverhide.empire.tools.tracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.telephony.SmsManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.neverhide.empire.tools.siren.SirenService

/**
 * SIM Guard — anti-theft tripwire. Remembers the SIM serial of the phone.
 * On every boot (and SIM state change), if the SIM is different from the one
 * recorded → blare the siren and silently SMS the trusted number with an
 * alert. Personal-use feature (owner + friends), fully transparent.
 */
class SimGuardReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimGuard"
        private const val KEY_SIM = "known_sim_serial"
        private const val KEY_ENABLED = "sim_guard_enabled"
        private const val KEY_TRUSTED = "trusted_number"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, false)) return

        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val currentSim = runCatching {
            @Suppress("MissingPermission")
            if (android.os.Build.VERSION.SDK_INT >= 26) tm.imei else tm.simSerialNumber
        }.getOrNull() ?: return

        val knownSim = prefs.getString(KEY_SIM, null)
        if (knownSim == null) {
            // First run — record this SIM as the trusted one
            prefs.edit().putString(KEY_SIM, currentSim).apply()
            Log.i(TAG, "Baseline SIM recorded")
            return
        }

        if (currentSim != knownSim) {
            Log.w(TAG, "SIM CHANGED! Triggering alarm")
            prefs.edit().putString(KEY_SIM, currentSim).apply() // re-baseline

            // 1. Blare the intruder siren for 60 seconds
            SirenService.start(context, mode = SirenService.MODE_SIM_ALERT, autoStopSeconds = 60)

            // 2. SMS the trusted number (phone tracker alert)
            val trusted = prefs.getString(KEY_TRUSTED, null)
            if (trusted.isNullOrBlank()) return
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                runCatching {
                    SmsManager.getDefault().sendTextMessage(
                        trusted, null,
                        "⚠️ NEVERHIDE ALERT: The SIM card in this phone was just changed. " +
                                "If this wasn't you, the phone may be stolen.",
                        null, null
                    )
                }
            }
        }
    }
}
