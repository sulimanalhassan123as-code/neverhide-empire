package com.neverhide.empire.tools.eyecare

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.CountDownTimer
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Eye Care service — two protections:
 *
 * 1. Blue-light filter: a translucent warm-tinted overlay that sits above
 *    everything (TYPE_APPLICATION_OVERLAY) and dims the harsh blue light.
 *    The intensity is user-controlled.
 * 2. Break reminders: the classic 20-20-20 rule — every 20 minutes a gentle
 *    notification tells you to look at something 20 feet away for 20 seconds.
 */
class EyeCareService : Service() {

    companion object {
        const val CHANNEL_ID = "eye_care"
        const val NOTIF_ID = 9003
        private const val BREAK_INTERVAL_MS = 20 * 60 * 1000L // 20 minutes

        fun start(context: Context) {
            val i = Intent(context, EyeCareService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EyeCareService::class.java))
        }

        fun isRunning(context: Context) =
            context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
                .getBoolean("eye_care_enabled", false)
    }

    private var overlayView: FrameLayout? = null
    private var breakTimer: CountDownTimer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("empire_prefs", MODE_PRIVATE)
        val intensity = prefs.getInt("eye_care_intensity", 40) // 0-80

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        // 1. Blue-light overlay
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = FrameLayout(this).apply {
            setBackgroundColor(Color.argb((intensity * 2.2f).toInt().coerceIn(0, 190), 255, 144, 32))
        }
        @SuppressLint("ClickableViewAccessibility")
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
        }
        runCatching { wm.addView(overlayView, params) }

        // 2. Foreground notification (required for FGS on 26+)
        createChannel()
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(NOTIF_ID, buildNotification())
        }

        // 3. 20-20-20 break reminders
        breakTimer = object : CountDownTimer(Long.MAX_VALUE, BREAK_INTERVAL_MS) {
            override fun onTick(millisUntilFinished: Long) {
                notifyBreak()
            }
            override fun onFinish() {}
        }.start()
    }

    private fun notifyBreak() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, CHANNEL_ID)
        else android.app.Notification.Builder(this)

        nm.notify(9004, builder
            .setContentTitle("👀 Eye Break Time")
            .setContentText("Look at something 20 feet away for 20 seconds")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setAutoCancel(true)
            .build())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Eye Care", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Blue-light filter and break reminders" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): android.app.Notification {
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Eye Care Active")
            .setContentText("Blue-light filter on • 20-20-20 reminders")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        breakTimer?.cancel()
        overlayView?.let {
            runCatching {
                (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it)
            }
        }
        overlayView = null
        getSharedPreferences("empire_prefs", MODE_PRIVATE)
            .edit().putBoolean("eye_care_enabled", false).apply()
        super.onDestroy()
    }
}
