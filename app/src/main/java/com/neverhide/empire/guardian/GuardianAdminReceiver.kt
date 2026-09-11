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

        // 1. Best-effort intruder selfie (silently fails if camera blocked while locked)
        IntruderCamera.capture(context) { photoPath ->
            // 2. Fire the jumpscare — full-screen intent notification
            JumpscareActivity.launch(context, theme, photoPath)
        }

        // 3. Vibration + tone for immediate shock even if the activity is delayed
        vibrate(context)
        tone()
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
            tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_LOOP, 1500)
        } catch (_: Exception) {
        }
    }
}
