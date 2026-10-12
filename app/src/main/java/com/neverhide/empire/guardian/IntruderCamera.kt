package com.neverhide.empire.guardian

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Intruder selfie (v2.10.2 rewrite).
 *
 * Old version failed silently on the SM-A716V. Fixes:
 *  - up to 3 attempts, each with a longer open timeout (cold front camera)
 *  - proper STILL_CAPTURE request (PREVIEW into a JPEG reader returns nothing
 *    on many Samsung devices)
 *  - ~600ms warm-up of a repeating preview so auto-exposure/focus settle
 *    (first frame was black/dark)
 *  - JPEG orientation set so the photo is upright
 *  - the failure REASON is stored in lastError and shown in the alert text
 *    instead of a blind "camera was blocked"
 */
object IntruderCamera {

    private const val TAG = "IntruderCamera"
    @Volatile var lastError: String = ""

    @SuppressLint("MissingPermission")
    fun capture(context: Context, onDone: (String?) -> Unit) {
        Thread {
            var photoPath: String? = null
            lastError = ""
            if (context.checkSelfPermission(android.Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                lastError = "camera permission not granted"
            } else {
                // Free the front camera from our OWN other camera holders
                // (Peek Guard, Face Guard) so Android doesn't refuse us with
                // CAMERA_IN_USE. Selfie of the intruder beats everything.
                runCatching {
                    context.startService(
                        android.content.Intent(context,
                            com.neverhide.empire.tools.peek.PeekGuardService::class.java)
                            .setAction(com.neverhide.empire.tools.peek.PeekGuardService.ACTION_YIELD)
                    )
                }
                try { Thread.sleep(700) } catch (_: InterruptedException) {}
                for (attempt in 1..5) {
                    photoPath = tryOnce(context, attempt)
                    if (photoPath != null) { lastError = ""; break }
                    Log.w(TAG, "attempt $attempt failed: $lastError")
                    // camera busy -> wait longer so the other holder can let go
                    val wait = if (lastError.contains("in use", true) ||
                        lastError.contains("CAMERA_IN_USE")) 1200L else 400L
                    try { Thread.sleep(wait) } catch (_: InterruptedException) {}
                }
            }
            android.os.Handler(context.mainLooper).post { onDone(photoPath) }
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun tryOnce(context: Context, attempt: Int): String? {
        val thread = HandlerThread("IntruderCam$attempt").apply { start() }
        val handler = Handler(thread.looper)
        var device: CameraDevice? = null
        var reader: ImageReader? = null
        var session: CameraCaptureSession? = null
        try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = mgr.cameraIdList.firstOrNull { cid ->
                mgr.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            } ?: mgr.cameraIdList.firstOrNull()
            ?: run { lastError = "no camera found"; return null }

            val chars = mgr.getCameraCharacteristics(id)
            val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 270

            // ---- open ----
            val opened = CountDownLatch(1)
            var openErr = ""
            mgr.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device = camera; opened.countDown() }
                override fun onDisconnected(camera: CameraDevice) {
                    openErr = "camera disconnected (in use?)"; camera.close(); opened.countDown()
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    openErr = "camera error code $error"; camera.close(); opened.countDown()
                }
            }, handler)
            val openWait = 3L + attempt * 2   // 5s, 7s, 9s
            if (!opened.await(openWait, TimeUnit.SECONDS) || device == null) {
                lastError = if (openErr.isNotEmpty()) openErr else "camera open timed out (${openWait}s)"
                return null
            }
            val dev = device!!

            // ---- jpeg size: smallest >= 640 wide (fast, still clear) ----
            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val size = map?.getOutputSizes(ImageFormat.JPEG)
                ?.filter { it.width >= 640 }
                ?.minByOrNull { it.width * it.height }
                ?: android.util.Size(640, 480)

            val imgLatch = CountDownLatch(1)
            var captured: ByteArray? = null
            reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
            reader.setOnImageAvailableListener({ r ->
                var img: Image? = null
                try {
                    img = r.acquireLatestImage()
                    if (img != null && captured == null) {
                        val buf = img.planes[0].buffer
                        val bytes = ByteArray(buf.remaining())
                        buf.get(bytes)
                        captured = bytes
                        imgLatch.countDown()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "read image", e)
                } finally { img?.close() }
            }, handler)

            // ---- session ----
            val sessionLatch = CountDownLatch(1)
            var sessErr = ""
            dev.createCaptureSession(listOf(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) { session = s; sessionLatch.countDown() }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessErr = "session configure failed"; sessionLatch.countDown()
                    }
                }, handler)
            if (!sessionLatch.await(4, TimeUnit.SECONDS) || session == null) {
                lastError = if (sessErr.isNotEmpty()) sessErr else "session timed out"
                return null
            }
            val sess = session!!

            // ---- warm up AE/AF with a repeating preview, then one still ----
            val previewReq = dev.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            }.build()
            sess.setRepeatingRequest(previewReq, null, handler)
            Thread.sleep(600)
            // discard any warm-up frame the listener grabbed first
            captured = null

            val stillReq = dev.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(reader.surface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                set(CaptureRequest.JPEG_QUALITY, 90.toByte())
                set(CaptureRequest.JPEG_ORIENTATION, (sensorOrientation + 0) % 360)
            }.build()
            sess.stopRepeating()
            sess.capture(stillReq, null, handler)

            if (!imgLatch.await(5, TimeUnit.SECONDS) || captured == null) {
                lastError = "no image returned by camera"
                return null
            }
            val bytes = captured!!
            if (bytes.size < 2_000) { lastError = "image empty"; return null }

            val dir = File(context.filesDir, "intruders").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "intruder_$stamp.jpg")
            file.writeBytes(bytes)
            return file.absolutePath
        } catch (e: SecurityException) {
            lastError = "camera blocked by system policy (${e.message})"
            return null
        } catch (e: Exception) {
            lastError = "${e.javaClass.simpleName}: ${e.message}"
            return null
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader?.close() }
            thread.quitSafely()
        }
    }

    /** Latest N intruder photos, newest first. */
    fun latestPhotos(context: Context, count: Int = 12): List<File> {
        val dir = File(context.filesDir, "intruders")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.sortedByDescending { it.name }?.take(count) ?: emptyList()
    }
}
