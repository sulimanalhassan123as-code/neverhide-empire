package com.neverhide.empire.guardian

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import com.neverhide.empire.core.EmpireBackgroundService

/**
 * Adrenaline Lock Guardian — device-admin receiver that fires the jumpscare
 * whenever someone enters the wrong lock-screen password.
 *
 * Flow:
 *  1. onPasswordFailed (called by the system after N failed unlock attempts)
 *  2. Capture an intruder selfie with the front camera (best-effort)
 *  3. Fire a full-screen-intent notification that opens JumpscareActivity
 *     (full-screen intent is the ONLY reliable way to launch over the lock
 *     screen from a receiver on Android 10+)
 *  4. Heavy vibration + terrifying tone
 */
class GuardianAdminReceiver : DeviceAdminReceiver() {

    companion object {
        const val GUARDIAN_PREFS = "guardian_prefs"
        const val KEY_ENABLED = "guardian_enabled"
        const val KEY_THEME = "guardian_theme"          // 0 Water 1 Fire 2 Thunder 3 Void
        const val KEY_TRUSTED_NUMBER = "trusted_number"  // SMS alerts on SIM change
        const val KEY_SENSITIVITY = "guardian_sensitivity" // fails before trigger (default 1)
        const val KEY_ALERT_NUMBER = "guardian_alert_number" // SMS alert destination
        const val KEY_FX_MODE = "guardian_fx_mode"           // 1 gunshot+cracks (default), 0 siren

        // De-dup guard: some OEMs (Samsung/Knox included) call BOTH
        // onPasswordFailed overloads for a single failed attempt, which
        // used to fire the whole alert pipeline 2-3x per real attempt.
        @Volatile
        private var lastTriggerMs = 0L
        private const val TRIGGER_DEBOUNCE_MS = 4_000L

        /** Human-readable event log entry for the evidence vault. */
        fun logEvent(context: Context, kind: String) {
            val f = java.io.File(context.filesDir, "intruders")
            f.mkdirs()
            val stamp = java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
            ).format(java.util.Date())
            java.io.File(f, "events.log").appendText("$stamp|$kind\n")
        }

        /** Send an SMS alert to the configured number (best-effort). */
        fun sendSmsAlert(context: Context, photoPath: String?) {
            val number = context.getSharedPreferences(GUARDIAN_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ALERT_NUMBER, "") ?: ""
            if (number.isBlank()) return
            runCatching {
                val sms = android.telephony.SmsManager.getDefault()
                sms.sendTextMessage(
                    number, null,
                    "🚨 NEVERHIDE GUARDIAN ALERT: Wrong password detected on your phone!" +
                            (if (photoPath != null) " Intruder selfie captured — open Empire to see it." else ""),
                    null, null
                )
            }
        }

        fun adminComponent(context: Context) =
            ComponentName(context, GuardianAdminReceiver::class.java)

        fun isAdminActive(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(adminComponent(context))
        }

        fun themeName(theme: Int): String = when (theme) {
            1 -> "🔥 Fire"; 2 -> "⚡ Thunder"; 3 -> "🌑 Shadow Void"; else -> "🌊 Water"
        }
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        trigger(context)
    }

    override fun onPasswordFailed(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordFailed(context, intent, user)
        trigger(context)
    }

    private fun trigger(context: Context) {

        // De-dup: some OEMs fire BOTH onPasswordFailed overloads for ONE
        // real failed attempt. Without this guard that used to send 2-3
        // duplicate alerts (SMS + WhatsApp) per attempt.
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < TRIGGER_DEBOUNCE_MS) return
        lastTriggerMs = now

        val prefs = context.getSharedPreferences(GUARDIAN_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ENABLED, true)) return

        // Respect sensitivity: trigger when failCount >= threshold
        val threshold = prefs.getInt(KEY_SENSITIVITY, 1)
        val prefsMain = context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
        val fails = prefsMain.getInt("guardian_fail_count", 0) + 1
        prefsMain.edit().putInt("guardian_fail_count", fails).apply()
        if (fails < threshold) return

        // Reset counter once triggered
        prefsMain.edit().putInt("guardian_fail_count", 0).apply()

        val theme = prefs.getInt(KEY_THEME, 0)

        // 1+2+3. Selfie + jumpscare + location/SMS/WhatsApp — all run inside
        // a genuine foreground service now (GuardianCaptureService), which
        // is what actually gives the camera and location calls permission
        // to work. Running them from the raw receiver background thread
        // (the old code) is exactly why selfies always said "camera was
        // blocked" and location always said "fixing…".
        GuardianCaptureService.start(context, theme)

        // Vibration + tone for immediate shock even if the capture is delayed
        vibrate(context)
        tone()

        // GUARDIAN 2.0 — evidence log only. The actual SMS now goes out
        // exactly ONCE, from inside GuardianAlert.fire() (called by the
        // capture service above) — the old immediate sendSmsAlert() call
        // here was a second, location-less SMS firing on every attempt,
        // doubling every alert. Removed.
        logEvent(context, "WRONG_PASSWORD")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent) {
        super.onPasswordSucceeded(context, intent)
        clearFails(context)
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordSucceeded(context, intent, user)
        clearFails(context)
    }

    private fun clearFails(context: Context) {
        // Owner unlocked successfully — clear fail counter
        context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
            .edit().putInt("guardian_fail_count", 0).apply()
    }

    private fun vibrate(context: Context) {
        val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        val pattern = longArrayOf(0, 700, 150, 700, 150, 700, 150, 700)
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, -1)
        }
    }

    private fun tone() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_ALARM, 100)
            tg.startTone(ToneGenerator.TONE_SUP_ERROR, 1500)
        } catch (_: Exception) {
        }
    }
}
