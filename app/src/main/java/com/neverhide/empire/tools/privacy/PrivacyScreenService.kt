package com.neverhide.empire.tools.privacy

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Privacy Screen — pocket/cover guard.
 *
 * The moment the proximity sensor is covered (phone in a pocket or bag,
 * flipped face-down against something, held to your ear) the screen goes
 * pitch black. Uncover it and your content instantly comes back.
 *
 * Why: notifications and chats stay private — someone pulling the phone
 * out of your bag, or you leaving it face-down on a table with the screen
 * still on, shows nothing but black.
 *
 * The overlay is FLAG_NOT_TOUCHABLE and fully transparent when active
 * screen content should be visible — it never intercepts your touches
 * and never changes how the phone behaves. It only paints black when
 * the sensor says the screen is covered.
 */
class PrivacyScreenService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL_ID = "privacy_screen"
        private const val NOTIF_ID = 9006

        fun start(context: Context) {
            val i = Intent(context, PrivacyScreenService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PrivacyScreenService::class.java))
        }

        fun isRunning(context: Context) =
            context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
                .getBoolean("privacy_screen_enabled", false)
    }

    private var overlayView: FrameLayout? = null
    private var sensorManager: SensorManager? = null
    private var covered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        // 1. Invisible full-screen overlay that can paint black on demand.
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        overlayView = FrameLayout(this).apply { setBackgroundColor(Color.TRANSPARENT) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP }
        runCatching { wm.addView(overlayView, params) }

        // 2. Foreground notification (required on 26+)
        createChannel()
        if (Build.VERSION.SDK_INT >= 26) startForeground(NOTIF_ID, buildNotification())

        // 3. Proximity sensor
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        overlayView?.let { runCatching { wm?.removeView(it) } }
        overlayView = null
        super.onDestroy()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val near = event.values[0] < (event.sensor.maximumRange.coerceAtMost(4f))
        if (near == covered) return // no state change
        covered = near
        overlayView?.post {
            overlayView?.setBackgroundColor(if (near) Color.BLACK else Color.TRANSPARENT)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun buildNotification(): android.app.Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, CHANNEL_ID)
        else android.app.Notification.Builder(this)
        return builder
            .setContentTitle("🔒 Privacy Screen active")
            .setContentText("Screen blanks automatically when covered — pocket, bag or face-down")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Privacy Screen", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Pocket/cover screen guard" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
