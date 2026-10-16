package com.neverhide.empire.tools.gamepad

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.neverhide.empire.MainActivity
import com.neverhide.empire.cleaner.FreezerEngine

/**
 * DLS PAD — floating football controller over the game.
 *
 * Design: run-circle (left) + A/B/C neon buttons (right). The pad sits
 * where YOUR thumbs rest; each press is forwarded to the game's own
 * control coordinates (calibrated in the Pad settings).
 *
 * GAME MODE: freezes background apps (FreezerEngine/Shizuku) so nothing
 * competes for CPU/RAM/network during a live match; unfreezes on stop.
 */
class GamePadOverlayService : Service() {

    companion object {
        const val ACTION_START = "com.neverhide.empire.gamepad.START"
        const val ACTION_STOP = "com.neverhide.empire.gamepad.STOP"
        private const val CHANNEL_ID = "dlspad"
        private const val NOTIF_ID = 4244

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("dlspad", Context.MODE_PRIVATE)

        fun isRunning(context: Context) = prefs(context).getBoolean("running", false)

        /** game control positions (screen px) — calibrated by user */
        fun gamePoints(p: SharedPreferences, w: Int, h: Int): Map<String, FloatArray> = mapOf(
            "joy" to floatArrayOf(
                p.getFloat("gameJoyX", 0.13f) * w,
                p.getFloat("gameJoyY", 0.86f) * h
            ),
            "A" to floatArrayOf(
                p.getFloat("gameAX", 0.80f) * w,
                p.getFloat("gameAY", 0.66f) * h
            ),
            "B" to floatArrayOf(
                p.getFloat("gameBX", 0.88f) * w,
                p.getFloat("gameBY", 0.78f) * h
            ),
            "C" to floatArrayOf(
                p.getFloat("gameCX", 0.71f) * w,
                p.getFloat("gameCY", 0.76f) * h
            )
        )
    }

    private var overlayView: View? = null
    private var gameModeOn = false
    private var frozenForGame = listOf<String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                gameModeOn = prefs(this).getBoolean("gameMode", false)
                startAsForeground()
                addOverlay()
                if (gameModeOn) startGameMode()
            }
            ACTION_STOP -> stopPad()
        }
        return START_NOT_STICKY
    }

    // ===================== GAME MODE =====================

    private fun startGameMode() {
        if (!FreezerEngine.hasPower(this)) return
        Thread {
            // freeze every non-system app EXCEPT DLS-ish games, Empire, launchers.
            // DLS package: com.firsttouchgames.dls3 (older) / dls.kitkab.com variants.
            val keep = hashSetOf(
                packageName,
                "com.firsttouchgames.dls3", "com.firsttouchgames.dlsk",
                "com.dls", "com.android.systemui", "com.sec.android.app.launcher",
                "com.android.launcher3", "com.touchtype.swiftkey"
            )
            val frozen = FreezerEngine.listFreezableApps(this)
                .filter { !keep.contains(it.packageName) }
                .map { it.packageName }
            frozen.forEach { pkg ->
                FreezerEngine.setSuspended(this, pkg, true)
            }
            frozenForGame = frozen
        }.start()
    }

    private fun stopGameMode() {
        if (frozenForGame.isEmpty()) return
        Thread {
            frozenForGame.forEach { pkg -> FreezerEngine.setSuspended(this, pkg, false) }
            frozenForGame = listOf()
        }.start()
    }

    // ===================== overlay =====================

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val dm = resources.displayMetrics
        val v = PadView(this, prefs(this))
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        )
        runCatching { wm.addView(v, params) }
        overlayView = v
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { overlayView?.let { wm.removeView(it) } }
        overlayView = null
    }

    private fun stopPad() {
        stopGameMode()
        removeOverlay()
        prefs(this).edit().putBoolean("running", false).apply()
        stopSelf()
    }

    override fun onDestroy() {
        stopGameMode()
        removeOverlay()
        super.onDestroy()
    }

    private fun startAsForeground() {
        createChannel()
        val stopPI = PendingIntent.getService(
            this, 1,
            Intent(this, GamePadOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        startForeground(NOTIF_ID, b
            .setContentTitle("🎮 DLS Pad active")
            .setContentText(if (gameModeOn) "Game Mode: background apps frozen for stable play" else "Overlay pad running")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openPI)
            .addAction(0, "Stop pad", stopPI)
            .setOngoing(true)
            .build())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "DLS Pad", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    // ===================== the pad view =====================

    private class PadView(ctx: Context, val p: SharedPreferences) : View(ctx) {
        private val metrics = ctx.resources.displayMetrics
        private val density = metrics.density
        private var w = 0f; private var h = 0f

        // pad layout (where the user's thumbs rest)
        private val joyCx get() = 0.17f * w
        private val joyCy get() = 0.86f * h
        private val joyR get() = 66f * density
        private val btnR get() = 34f * density
        private val btnA get() = floatArrayOf(0.82f * w, 0.645f * h)
        private val btnB get() = floatArrayOf(0.90f * w, 0.775f * h)
        private val btnC get() = floatArrayOf(0.735f * w, 0.755f * h)

        // joystick state
        private var joyActive = false
        private var joyLastMove = 0L
        private var joyDirX = 0f
        private var joyDirY = 0f
        private var knobX = 0f
        private var knobY = 0f

        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 0, 229, 255); style = Paint.Style.STROKE; strokeWidth = 3f * density
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(26, 0, 229, 255); style = Paint.Style.FILL
        }
        private val knobPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(150, 0, 229, 255); style = Paint.Style.FILL
        }
        private val btnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val btnStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 2.5f * density
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textAlign = Paint.Align.CENTER
            textSize = 20f * density; isFakeBoldText = true
        }

        override fun onSizeChanged(w2: Int, h2: Int, ow: Int, oh: Int) {
            w = w2.toFloat(); h = h2.toFloat()
            knobX = joyCx; knobY = joyCy
        }

        private val gamePts get() = gamePoints(p, w.toInt(), h.toInt())

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            // run circle
            canvas.drawCircle(joyCx, joyCy, joyR, fillPaint)
            canvas.drawCircle(joyCx, joyCy, joyR, ringPaint)
            canvas.drawCircle(knobX, knobY, 18f * density, knobPaint)
            // A/B/C
            for ((label, pos, color) in listOf(
                Triple("A", btnA, Color.rgb(255, 23, 68)),
                Triple("B", btnB, Color.rgb(0, 191, 165)),
                Triple("C", btnC, Color.rgb(255, 196, 0))
            )) {
                val pressed = pressedBtn == label
                btnPaint.color = if (pressed) color else Color.argb(60, Color.red(color), Color.green(color), Color.blue(color))
                btnPaint.alpha = if (pressed) 235 else 110
                canvas.drawCircle(pos[0], pos[1], btnR, btnPaint)
                btnStroke.color = color
                canvas.drawCircle(pos[0], pos[1], btnR, btnStroke)
                val ty = pos[1] - (textPaint.descent() + textPaint.ascent()) / 2
                canvas.drawText(label, pos[0], ty, textPaint)
            }
            postInvalidateOnAnimationSafe()
        }

        private fun postInvalidateOnAnimationSafe() {
            // only redraw while something is pressed (joystick) — saves battery
            if (joyActive) postInvalidateOnAnimation()
        }

        private var pressedBtn: String? = null

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                    val i = event.actionIndex
                    val x = event.getX(i); val y = event.getY(i)
                    if (insideCircle(x, y, joyCx, joyCy, joyR * 1.15f)) {
                        joyActive = true
                        updateKnob(x, y)
                        startJoy()
                        return true
                    }
                    for ((label, pos, _) in listOf(
                        Triple("A", btnA, 0), Triple("B", btnB, 0), Triple("C", btnC, 0)
                    )) {
                        if (insideCircle(x, y, pos[0], pos[1], btnR * 1.25f)) {
                            pressedBtn = label
                            pressGameBtn(label)
                            invalidate()
                            return true
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    for (i in 0 until event.pointerCount) {
                        val x = event.getX(i); val y = event.getY(i)
                        if (joyActive && insideCircle(x, y, joyCx, joyCy, joyR * 1.6f)) {
                            updateKnob(x, y)
                        }
                    }
                    if (joyActive) moveJoy()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                    if (joyActive) {
                        joyActive = false
                        joyDirX = 0f; joyDirY = 0f
                        knobX = joyCx; knobY = joyCy
                        invalidate()
                    }
                    pressedBtn = null
                    invalidate()
                }
            }
            return true
        }

        private fun updateKnob(x: Float, y: Float) {
            val dx = x - joyCx; val dy = y - joyCy
            val d = kotlin.math.hypot(dx, dy)
            val clamped = if (d > joyR) joyR else d
            val s = if (d > 0) clamped / d else 0f
            knobX = joyCx + dx * s
            knobY = joyCy + dy * s
            joyDirX = dx * s / joyR
            joyDirY = dy * s / joyR
        }

        private fun startJoy() {
            val j = gamePts["joy"] ?: return
            // press the game joystick center and lean in the held direction;
            // a continuing sequence of 150ms drags keeps the player running
            GamePadAccessibilityService.instance?.joyDrag(
                j[0], j[1], joyDirX * 40f, joyDirY * 40f, 150
            )
            joyLastMove = SystemClock.uptimeMillis()
        }

        private fun moveJoy() {
            val now = SystemClock.uptimeMillis()
            if (now - joyLastMove < 120) return
            joyLastMove = now
            startJoy()
        }

        private fun pressGameBtn(label: String) {
            val svc = GamePadAccessibilityService.instance ?: return
            val pt = gamePts[label] ?: return
            svc.tap(pt[0], pt[1])
        }

        private fun insideCircle(x: Float, y: Float, cx: Float, cy: Float, r: Float): Boolean =
            (x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r
    }
}
