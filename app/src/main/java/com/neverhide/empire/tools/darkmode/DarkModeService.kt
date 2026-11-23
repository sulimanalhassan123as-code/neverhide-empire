package com.neverhide.empire.tools.darkmode

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * SYSTEM-WIDE DARK MODE — four stages of dimming over the whole phone.
 *
 * V2.13.7 (2026-11-23): built because Eye Care at high strength could still
 * block touches. Dark mode is deliberately a SEPARATE, always-safe overlay:
 *
 *  - FLAG_NOT_TOUCHABLE is set unconditionally, so this window can never eat a
 *    tap. The system navigation bar sits above app overlays, which is exactly
 *    why a touchable overlay shows the "only the bottom works" freeze.
 *  - No FLAG_DIM_BEHIND. Two stacked full-screen dims (Eye Care + dim-behind)
 *    add up, and Android blocks touches that cross an obscuring stack.
 *  - Deepest stage is 72% black, under the ~80% threshold where Android starts
 *    treating a passthrough window as obscuring.
 *  - Starting dark mode stops Eye Care first, so only one dim window ever
 *    exists at a time.
 */
class DarkModeService : Service() {

    companion object {
        const val CHANNEL_ID = "dark_mode"
        const val NOTIF_ID = 9005
        const val ACTION_STOP = "com.neverhide.empire.STOP_DARK_MODE"

        val STAGE_NAMES = arrayOf("Light dark", "Dark", "Heavy dark", "Deep dark")

        /** Alpha out of 255 for each stage: 20%, 40%, 58%, 72%. */
        private val STAGE_ALPHA = intArrayOf(51, 102, 148, 184)

        fun stageAlpha(stage: Int) = STAGE_ALPHA[stage.coerceIn(0, STAGE_ALPHA.size - 1)]
        fun stageName(stage: Int) = STAGE_NAMES[stage.coerceIn(0, STAGE_NAMES.size - 1)]

        fun start(context: Context) {
            val i = Intent(context, DarkModeService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DarkModeService::class.java))
        }

        fun isRunning(context: Context) =
            context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
                .getBoolean("dark_mode_enabled", false)

        fun stage(context: Context) =
            context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
                .getInt("dark_mode_stage", 1)
    }

    private var overlayView: FrameLayout? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            getSharedPreferences("empire_prefs", MODE_PRIVATE).edit()
                .putBoolean("dark_mode_enabled", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        // Rebuild if the stage changed while the service is already running.
        if (overlayView != null) {
            rebuild()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        // Only one dim window at a time: dark mode wins over the warm filter.
        runCatching {
            com.neverhide.empire.tools.eyecare.EyeCareService.stop(this)
            getSharedPreferences("empire_prefs", MODE_PRIVATE).edit()
                .putBoolean("eye_care_enabled", false).apply()
        }

        createChannel()
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(NOTIF_ID, buildNotification())
        }
        addOverlay()
    }

    private fun addOverlay() {
        val prefs = getSharedPreferences("empire_prefs", MODE_PRIVATE)
        val stage = prefs.getInt("dark_mode_stage", 1)
        val alpha = stageAlpha(stage)

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = FrameLayout(this).apply {
            setBackgroundColor(Color.argb(alpha, 0, 0, 0))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Not touchable is non-negotiable: this window must never eat a tap.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }
        runCatching { wm.addView(view, params) }
        overlayView = view
        prefs.edit().putBoolean("dark_mode_enabled", true).apply()
    }

    private fun rebuild() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView?.let { runCatching { wm.removeView(it) } }
        overlayView = null
        addOverlay()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Dark Mode", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "System-wide dark overlay with four strengths" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val stage = getSharedPreferences("empire_prefs", MODE_PRIVATE).getInt("dark_mode_stage", 1)
        val builder = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, CHANNEL_ID)
        else android.app.Notification.Builder(this)

        return builder
            .setContentTitle("Dark mode active")
            .setContentText("${stageName(stage)} • whole screen")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                0, "Turn off dark mode",
                PendingIntent.getService(
                    this, 2,
                    Intent(this, DarkModeService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    override fun onDestroy() {
        overlayView?.let {
            runCatching { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
        }
        overlayView = null
        getSharedPreferences("empire_prefs", MODE_PRIVATE)
            .edit().putBoolean("dark_mode_enabled", false).apply()
        super.onDestroy()
    }
}
