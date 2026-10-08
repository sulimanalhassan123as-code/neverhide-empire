package com.neverhide.empire.tools.screenrec

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SCREEN RECORDER — native MediaProjection recording with quality presets.
 *
 * Presets:
 *  MAX      — native resolution (up to device screen), 24 Mbps, 60fps →
 *             archival / re-edit quality. Big files.
 *  HIGH     — 1080p, 16 Mbps, 30fps → the everyday default. Crisp.
 *  WHATSAPP — 720p, 6 Mbps, 30fps → tuned so WhatsApp's own compression
 *             leaves it looking good; ~45 MB per minute.
 *
 * HOW TO KEEP QUALITY ON WHATSAPP (the important part):
 *  Sharing as a DOCUMENT makes WhatsApp send the ORIGINAL file bit-for-bit
 *  — zero re-encode, zero quality loss, at any preset. The gallery's
 *  "Send as document" action does exactly that. "Send as video" re-encodes
 *  (WhatsApp-side, unavoidable) — record with the WHATSAPP preset for that
 *  path.
 */
class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START = "com.neverhide.empire.screenrec.START"
        const val ACTION_STOP = "com.neverhide.empire.screenrec.STOP"
        const val ACTION_PAUSE = "com.neverhide.empire.screenrec.PAUSE"
        const val ACTION_RESUME = "com.neverhide.empire.screenrec.RESUME"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_PRESET = "preset"
        private const val EXTRA_MIC = "mic"
        const val NOTIF_ID = 4242

        const val PRESET_MAX = "max"
        const val PRESET_HIGH = "high"
        const val PRESET_WHATSAPP = "whatsapp"

        // Live state for the UI
        @Volatile var isRecording = false
        @Volatile var isPaused = false
        @Volatile var startedAt = 0L
        @Volatile var lastSavedPath: String? = null
        @Volatile var lastError: String? = null

        fun recordingsDir(context: Context): File {
            val dir = File(
                context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
                "NeverhideScreen"
            )
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

        fun start(context: Context, resultCode: Int, data: Intent, preset: String, mic: Boolean) {
            val i = Intent(context, ScreenRecordService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_DATA, data)
                putExtra(EXTRA_PRESET, preset)
                putExtra(EXTRA_MIC, mic)
            }
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, ScreenRecordService::class.java).setAction(ACTION_STOP)
            )
        }

        fun togglePause(context: Context) {
            val a = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            context.startService(Intent(context, ScreenRecordService::class.java).setAction(a))
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var outFile: File? = null
    private var projectionToken: Intent? = null
    private var resultCode = 0
    private var mic = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                projectionToken = @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
                mic = intent.getBooleanExtra(EXTRA_MIC, false)
                val preset = intent.getStringExtra(EXTRA_PRESET) ?: PRESET_HIGH
                if (!isRecording) beginRecording(preset)
            }
            ACTION_STOP -> finishRecording()
            ACTION_PAUSE -> {
                if (isRecording && !isPaused) {
                    runCatching { recorder?.pause() }
                    isPaused = true
                    updateNotification()
                }
            }
            ACTION_RESUME -> {
                if (isRecording && isPaused) {
                    runCatching { recorder?.resume() }
                    isPaused = false
                    updateNotification()
                }
            }
        }
        return START_NOT_STICKY
    }

    // ===================== recording =====================

    private fun beginRecording(preset: String) {
        // Android 14+: must be foreground with the mediaProjection type
        // BEFORE requesting the projection instance.
        startAsForeground()
        try {
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = mgr.getMediaProjection(resultCode, projectionToken!!)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { finishRecording() }
            }, Handler(Looper.getMainLooper()))

            val metrics = resources.displayMetrics
            val presetVal = when (preset) {
                PRESET_MAX -> 0
                PRESET_WHATSAPP -> 1
                else -> 2
            }
            val w = intArrayOf(
                metrics.widthPixels.coerceAtMost(1440), 720, 1080
            )[presetVal]
            val h = intArrayOf(
                metrics.heightPixels.coerceAtMost(3200), 1280, 1920
            )[presetVal]
            val fps = intArrayOf(60, 30, 30)[presetVal]
            val bitrate = intArrayOf(24_000_000, 6_000_000, 16_000_000)[presetVal]
            // fit the aspect ratio to the actual screen so nothing stretches
            val scale = minOf(w.toFloat() / metrics.widthPixels, h.toFloat() / metrics.heightPixels)
            val vw = (metrics.widthPixels * scale).toInt() and 0x7FFFFFFE // even numbers
            val vh = (metrics.heightPixels * scale).toInt() and 0x7FFFFFFE

            val rec = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
            else @Suppress("DEPRECATION") MediaRecorder()
            rec.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            if (mic) {
                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (mic) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setVideoSize(vw, vh)
            rec.setVideoFrameRate(fps)
            rec.setVideoEncodingBitRate(bitrate)

            val dir = recordingsDir(this)
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            outFile = File(dir, "screen_$stamp.mp4")
            rec.setOutputFile(outFile!!.absolutePath)
            rec.setMaxFileSize(0) // unlimited — user decides with presets
            rec.prepare()
            rec.start()

            val vd = projection!!.createVirtualDisplay(
                "neverhide_rec", vw, vh, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                rec.surface, null, null
            )
            display = vd
            recorder = rec
            isRecording = true
            isPaused = false
            startedAt = System.currentTimeMillis()
            lastError = null
            updateNotification()
        } catch (e: Exception) {
            lastError = e.message ?: "recorder error"
            cleanup()
            stopSelf()
        }
    }

    private fun finishRecording() {
        if (!isRecording) { stopSelf(); return }
        runCatching { recorder?.stop() }
        lastSavedPath = outFile?.absolutePath
        cleanup()
        stopSelf()
    }

    private fun cleanup() {
        runCatching { display?.release() }
        runCatching { recorder?.release() }
        runCatching { projection?.stop() }
        display = null
        recorder = null
        projection = null
        isRecording = false
        isPaused = false
    }

    // ===================== notification =====================

    private fun startAsForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(
            "screenrec", "Screen Recorder", NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(ch)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, buildNotif("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, buildNotif("Starting…"))
        }
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotif(if (isPaused) "PAUSED" else "Recording screen"))
    }

    private fun buildNotif(text: String): Notification {
        val stopPI = PendingIntent.getService(
            this, 1,
            Intent(this, ScreenRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val pausePI = PendingIntent.getService(
            this, 2,
            Intent(this, ScreenRecordService::class.java)
                .setAction(if (isPaused) ACTION_RESUME else ACTION_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, "screenrec")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Neverhide Screen Recorder")
            .setContentText(text)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause, if (isPaused) "Resume" else "Pause", pausePI)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop & Save", stopPI)
            .build()
    }
}
