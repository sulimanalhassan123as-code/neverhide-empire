package com.neverhide.empire.guardian

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

/**
 * GUARDIAN CAPTURE SERVICE — runs the intruder-selfie + location + alert
 * pipeline as a genuine FOREGROUND SERVICE (type camera + location).
 *
 * WHY THIS EXISTS: since Android 9, apps cannot access the camera (and,
 * effectively, cannot reliably get a fresh location fix either) from a
 * plain background thread — only from the foreground or from a proper
 * foreground service declaring the matching type. The old code tried to
 * open the camera straight from the DeviceAdminReceiver callback on a raw
 * background Thread, which is exactly why every selfie came back
 * "camera was blocked" and location came back empty. This service fixes
 * that by giving the capture pipeline real foreground privileges for the
 * few seconds it needs, then stopping itself.
 */
class GuardianCaptureService : Service() {

    companion object {
        private const val CHANNEL_ID = "guardian_capture"
        private const val NOTIF_ID = 4471
        private const val EXTRA_THEME = "theme"
        private const val WATCHDOG_MS = 25_000L

        fun start(context: Context, theme: Int) {
            val i = Intent(context, GuardianCaptureService::class.java)
                .putExtra(EXTRA_THEME, theme)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(i)
                } else {
                    context.startService(i)
                }
            } catch (_: Exception) {
                // Extremely rare (OEM background-start block) — fall back to
                // firing the alert without the foreground privileges rather
                // than losing the alert entirely.
                JumpscareActivity.launch(context, theme, null)
                GuardianAlert.fire(context, null)
            }
        }
    }

    private val watchdog = Handler(Looper.getMainLooper())
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID, "Guardian Alert",
                        NotificationManager.IMPORTANCE_LOW
                    ).apply { description = "Capturing security evidence" }
                )
            }
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Neverhide Guardian")
            .setContentText("Securing device evidence…")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val theme = intent?.getIntExtra(EXTRA_THEME, 0) ?: 0

        // Safety net: never hold the foreground service longer than this,
        // even if the camera hangs — the jumpscare + SMS must still fire.
        watchdog.postDelayed({ finish(theme, null) }, WATCHDOG_MS)

        // Now genuinely foreground — camera + location access is legal.
        IntruderCamera.capture(this) { photoPath ->
            finish(theme, photoPath)
        }
        return START_NOT_STICKY
    }

    private fun finish(theme: Int, photoPath: String?) {
        if (finished) return
        finished = true
        watchdog.removeCallbacksAndMessages(null)
        JumpscareActivity.launch(this, theme, photoPath)
        // Location fetch + SMS + WhatsApp — still runs from THIS foreground
        // service context, so the location call is also legitimate now.
        GuardianAlert.fire(this, photoPath)
        // Give GuardianAlert's background thread a moment to hand off the
        // location/network requests before we drop foreground privileges.
        Handler(Looper.getMainLooper()).postDelayed({
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, 2_000)
    }
}
