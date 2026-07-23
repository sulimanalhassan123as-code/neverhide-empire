package com.neverhide.empire.screenshot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.neverhide.empire.MainActivity
import kotlin.math.abs

/**
 * Draggable floating bubble (SYSTEM_ALERT_WINDOW). Tap = trigger a capture by
 * opening MainActivity's projection flow; drag = reposition anywhere on screen.
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubble: View? = null
    private lateinit var params: WindowManager.LayoutParams

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(2002, buildNotification())
        wm = getSystemService(WindowManager::class.java)
        addBubble()
    }

    private fun addBubble() {
        val view = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            setBackgroundColor(Color.parseColor("#CC00E5FF"))
            setPadding(24, 24, 24, 24)
        }
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40; y = 300
        }

        var initX = 0; var initY = 0
        var touchX = 0f; var touchY = 0f
        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x; initY = params.y
                    touchX = e.rawX; touchY = e.rawY; false
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initX + (e.rawX - touchX).toInt()
                    params.y = initY + (e.rawY - touchY).toInt()
                    wm.updateViewLayout(view, params); true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.rawX - touchX) + abs(e.rawY - touchY)
                    if (moved < 20) triggerCapture()
                    true
                }
                else -> false
            }
        }
        bubble = view
        wm.addView(view, params)
    }

    private fun triggerCapture() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("auto_capture", true)
        )
    }

    private fun buildNotification(): Notification {
        val ch = "bubble_channel"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "Bubble", NotificationManager.IMPORTANCE_MIN)
            )
        }
        return Notification.Builder(this, ch)
            .setContentTitle("Empire bubble active")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }

    override fun onDestroy() {
        bubble?.let { wm.removeView(it) }
        super.onDestroy()
    }
}
