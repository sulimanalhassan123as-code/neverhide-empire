package com.neverhide.empire.screenshot

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import java.io.OutputStream

/**
 * Captures the screen with MediaProjection (no root) and saves a PNG to the
 * gallery via MediaStore. One-shot: grabs a single frame then tears down to
 * preserve battery.
 */
class ScreenshotService : Service() {

    companion object {
        private const val CHANNEL_ID = "screenshot_channel"
        private const val NOTIF_ID = 1001
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenshotService::class.java)
                .putExtra(EXTRA_CODE, resultCode)
                .putExtra(EXTRA_DATA, data)
            context.startForegroundService(i)
        }
    }

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        val code = intent?.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED) ?: return START_NOT_STICKY
        @Suppress("DEPRECATION")
        val data: Intent = intent.getParcelableExtra(EXTRA_DATA) ?: return START_NOT_STICKY

        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = mpm.getMediaProjection(code, data).also {
            it.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { cleanup() }
            }, handler)
        }
        // Small delay lets any triggering UI (bubble/QS shade) disappear first.
        handler.postDelayed({ capture() }, 300)
        return START_NOT_STICKY
    }

    private fun capture() {
        val metrics = DisplayMetrics()
        val wm = getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val w = metrics.widthPixels
        val h = metrics.heightPixels
        val dpi = metrics.densityDpi

        imageReader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        virtualDisplay = projection?.createVirtualDisplay(
            "empire-shot", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader!!.surface, null, handler
        )

        imageReader?.setOnImageAvailableListener({ reader ->
            reader.acquireLatestImage()?.let { image ->
                val plane = image.planes[0]
                val rowPadding = plane.rowStride - plane.pixelStride * w
                val bitmap = Bitmap.createBitmap(
                    w + rowPadding / plane.pixelStride, h, Bitmap.Config.ARGB_8888
                ).apply { copyPixelsFromBuffer(plane.buffer) }
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, w, h)
                saveToGallery(cropped)
                bitmap.recycle()
                image.close()
            }
            handler.post { cleanup(); stopSelf() }
        }, handler)
    }

    private fun saveToGallery(bitmap: Bitmap) {
        val name = "Empire_${System.currentTimeMillis()}.png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/NeverhideEmpire")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return
        resolver.openOutputStream(uri)?.use { out: OutputStream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        if (Build.VERSION.SDK_INT >= 29) {
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        handler.post { Toast.makeText(this, "Screenshot saved", Toast.LENGTH_SHORT).show() }
    }

    private fun cleanup() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.close(); imageReader = null
        projection?.stop(); projection = null
    }

    override fun onDestroy() { cleanup(); super.onDestroy() }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Screenshot", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Neverhide Empire")
            .setContentText("Capturing screenshot...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .build()
    }
}
