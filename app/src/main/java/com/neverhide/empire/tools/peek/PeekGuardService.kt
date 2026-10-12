package com.neverhide.empire.tools.peek

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.media.FaceDetector
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import com.neverhide.empire.MainActivity
import com.neverhide.empire.faceguard.FaceGuard

/**
 * Peek Guard v2.10.0 — anti-shoulder-surfing.
 *
 * Two shields, both optional, both live in one foreground service:
 *  • PEEK DETECT: front camera samples frames every ~1.5 s, runs the same
 *    rotation-safe 96x96 grid + FaceDetector pipeline as Face Guard, but
 *    for COUNTING faces. You (1 face) = normal. A second face appears
 *    behind you → the screen goes solid BLACK instantly. It clears itself
 *    once the extra face is gone for a few samples.
 *  • DIM SHIELD: a persistent low-brightness dim layer that keeps the
 *    screen readable straight-on but much harder to read from an angle.
 *
 * Honest limits (told to the user up front):
 *  • Android REQUIRES the foreground-service notification — it cannot be
 *    fully hidden without root; it is kept low-key.
 *  • A face far in the background may be too small for the 96px grid.
 *  • Camera use costs battery: preview is capped at 480px and frames are
 *    analyzed at most every 1.5 s.
 */
class PeekGuardService : Service() {

    companion object {
        const val ACTION_START = "com.neverhide.empire.peek.START"
        const val ACTION_STOP = "com.neverhide.empire.peek.STOP"
        const val ACTION_TEST = "com.neverhide.empire.peek.TEST"
        private const val CHANNEL_ID = "peekguard"
        private const val NOTIF_ID = 4243
        private const val SCAN = 96
        private const val SAMPLE_MS = 1500L
        private const val CLEAR_STREAK = 4

        // Live state for the UI — Compose collects this (lesson from the
        // v2.9.7 invisible-recording bug: plain vars are invisible to Compose)
        val uiTick = kotlinx.coroutines.flow.MutableStateFlow(0)
        @Volatile var facesSeen = 0
        @Volatile var blackout = false
        @Volatile var lastSampleAt = 0L
        private fun bump() { uiTick.value = uiTick.value + 1 }

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("peekguard", Context.MODE_PRIVATE)

        fun isRunning(context: Context): Boolean =
            prefs(context).getBoolean("running", false)
    }

    private val main = Handler(Looper.getMainLooper())
    private var camera: android.hardware.Camera? = null
    private var frame: ByteArray? = null
    private var frameW = 0
    private var frameH = 0
    private var peekDetect = true
    private var dimShield = false
    private var testUntil = 0L
    private var clearStreak = 0
    private var overlayView: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var dimLevel = 0.6f   // dim-shield strength (0..1)
    private var lastSample = 0L
    private val sampler = object : Runnable {
        override fun run() {
            analyzeLatestFrame()
            main.postDelayed(this, SAMPLE_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                peekDetect = intent.getBooleanExtra("peek", true)
                dimShield = intent.getBooleanExtra("dim", false)
                dimLevel = intent.getFloatExtra("dimLevel", 0.6f)
                prefs(this).edit()
                    .putBoolean("running", true)
                    .putBoolean("peek", peekDetect)
                    .putBoolean("dim", dimShield)
                    .putFloat("dimLevel", dimLevel)
                    .apply()
                startAsForeground()
                addOverlay()
                openCamera()
                main.postDelayed(sampler, SAMPLE_MS)
            }
            ACTION_STOP -> {
                stopGuard()
            }
            ACTION_TEST -> {
                testUntil = SystemClock.elapsedRealtime() + 4000
                paint()
            }
        }
        return START_NOT_STICKY
    }

    private fun stopGuard() {
        main.removeCallbacks(sampler)
        runCatching { camera?.setPreviewCallback(null) }
        runCatching { camera?.stopPreview() }
        runCatching { camera?.release() }
        camera = null
        removeOverlay()
        prefs(this).edit().putBoolean("running", false).apply()
        blackout = false
        bump()
        stopSelf()
    }

    override fun onDestroy() {
        main.removeCallbacks(sampler)
        runCatching { camera?.release() }
        removeOverlay()
        super.onDestroy()
    }

    // ===================== camera =====================

    @SuppressLint("NewApi")
    private fun openCamera() {
        if (camera != null) return
        runCatching {
            val cam = android.hardware.Camera.open(android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT)
                ?: throw IllegalStateException("front camera busy")
            val params = cam.parameters
            val size = params.supportedPreviewSizes
                .filter { it.width <= 480 }
                .minByOrNull { it.width * it.height } ?: params.previewSize
            params.setPreviewSize(size.width, size.height)
            cam.parameters = params
            cam.setDisplayOrientation(90)
            // no visible preview — dummy texture, service has no UI surface
            cam.setPreviewTexture(SurfaceTexture(10))
            cam.setPreviewCallback { data, cam2 ->
                val s = cam2.parameters.previewSize
                frame = data; frameW = s.width; frameH = s.height
            }
            cam.startPreview()
            camera = cam
        }.onFailure {
            Toast.makeText(this, "Peek Guard: camera unavailable — ${it.message}", Toast.LENGTH_LONG).show()
            stopGuard()
        }
    }

    // ===================== peek analysis =====================

    private fun analyzeLatestFrame() {
        val data = frame ?: return
        if (!peekDetect && !dimShield) return
        lastSample = System.currentTimeMillis()
        lastSampleAt = lastSample
        var count = 0
        runCatching {
            val gray = FaceGuard.nv21ToGray(data, frameW, frameH)
            // rotation-safe: try 0/90/270 grids, keep the best face count
            val candidates = listOf(gray, FaceGuard.rot90(gray), FaceGuard.rot270(gray))
            for (g in candidates) {
                val bmp = Bitmap.createBitmap(SCAN, SCAN, Bitmap.Config.RGB_565)
                bmp.setPixels(toPixels(g), 0, SCAN, 0, 0, SCAN, SCAN)
                val detector = FaceDetector(SCAN, SCAN, 4)
                val faces = arrayOfNulls<FaceDetector.Face>(4)
                detector.findFaces(bmp, faces)
                val n = faces.count { it != null }
                if (n > count) count = n
                bmp.recycle()
            }
        }
        facesSeen = count
        // state machine: 2+ faces => black. 0-1 faces for CLEAR_STREAK
        // consecutive samples (or no face at all) => release the blackout.
        if (count >= 2) {
            clearStreak = 0
        } else {
            clearStreak++
        }
        paint()
        bump()
    }

    private fun toPixels(gray: ByteArray): IntArray {
        val px = IntArray(gray.size)
        for (i in gray.indices) {
            val v = gray[i].toInt() and 0xFF
            px[i] = 0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        return px
    }

    // ===================== overlay =====================

    private fun addOverlay() {
        if (overlayView != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val v = View(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        params.dimAmount = 0f
        runCatching { wm.addView(v, params) }
        overlayView = v
        overlayParams = params
        paint()
    }

    private fun removeOverlay() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        runCatching { overlayView?.let { wm.removeView(it) } }
        overlayView = null
    }

    private fun paint() {
        val v = overlayView ?: return
        val testing = SystemClock.elapsedRealtime() < testUntil
        val shouldBlack = (peekDetect && facesSeen >= 2) || testing
        blackout = shouldBlack
        main.post {
            runCatching {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val lp = overlayParams
                when {
                    shouldBlack -> {
                        v.setBackgroundColor(Color.BLACK)
                        lp?.dimAmount = 1.0f
                    }
                    dimShield -> {
                        // readable straight-on, dark from an angle
                        v.setBackgroundColor(0x66000000)   // 40% black tint
                        lp?.dimAmount = dimLevel
                    }
                    else -> {
                        v.setBackgroundColor(Color.TRANSPARENT)
                        lp?.dimAmount = 0f
                    }
                }
                if (lp != null) runCatching { wm.updateViewLayout(v, lp) }
            }
        }
    }

    // ===================== foreground =====================

    private fun startAsForeground() {
        createChannel()
        startForeground(NOTIF_ID, buildNotif())
    }

    private fun buildNotif(): Notification {
        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopPI = PendingIntent.getService(
            this, 1,
            Intent(this, PeekGuardService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return builder
            .setContentTitle("🕵️ Peek Guard active")
            .setContentText("Watching for faces behind you — dim shield: ${if (dimShield) "ON" else "off"}")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(openPI)
            .addAction(0, "Stop", stopPI)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Peek Guard", NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}
