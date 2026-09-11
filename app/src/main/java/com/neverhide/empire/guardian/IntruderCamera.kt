package com.neverhide.empire.guardian

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Intruder selfie — grabs a single frame from the FRONT camera the moment a
 * wrong password attempt is detected. Runs on a background thread; silently
 * no-ops when the camera can't be opened (e.g. camera restricted while locked
 * on some OEMs). Photos land in filesDir/intruders/.
 */
object IntruderCamera {

    private const val TAG = "IntruderCamera"

    @SuppressLint("MissingPermission")
    fun capture(context: Context, onDone: (String?) -> Unit) {
        Thread {
            var photoPath: String? = null
            try {
                val cameraManager = context.getSystemService(Context.CAMERA_SERVICE)
                        as android.hardware.camera2.CameraManager
                val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                    val facing = cameraManager.getCameraCharacteristics(id)
                        .get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                    facing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
                } ?: cameraManager.cameraIdList.firstOrNull()

                if (cameraId == null) throw IllegalStateException("No camera found")

                val handler = android.os.HandlerThread("IntruderCam").apply { start() }.looper
                val deviceReady = java.util.concurrent.CountDownLatch(1)
                var cameraDevice: android.hardware.camera2.CameraDevice? = null

                cameraManager.openCamera(cameraId, object : android.hardware.camera2.CameraDevice.StateCallback() {
                    override fun onOpened(camera: android.hardware.camera2.CameraDevice) {
                        cameraDevice = camera
                        deviceReady.countDown()
                    }
                    override fun onDisconnected(camera: android.hardware.camera2.CameraDevice) {
                        camera.close(); deviceReady.countDown()
                    }
                    override fun onError(camera: android.hardware.camera2.CameraDevice, error: Int) {
                        camera.close(); deviceReady.countDown()
                    }
                }, android.os.Handler(handler))

                deviceReady.await(3, java.util.concurrent.TimeUnit.SECONDS)

                cameraDevice?.let { device ->
                    val reader = android.media.ImageReader.newInstance(640, 480,
                        android.graphics.ImageFormat.JPEG, 1)
                    val readerReady = java.util.concurrent.CountDownLatch(1)
                    var image: android.media.Image? = null

                    reader.setOnImageAvailableListener({ r ->
                        image = r.acquireLatestImage()
                        readerReady.countDown()
                    }, android.os.Handler(handler))

                    val surface = reader.surface
                    device.createCaptureSession(
                        listOf(surface),
                        object : android.hardware.camera2.CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: android.hardware.camera2.CameraCaptureSession) {
                                try {
                                    val request = device.createCaptureRequest(
                                        android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
                                    ).apply { addTarget(surface) }.build()
                                    session.capture(request, null, android.os.Handler(handler))
                                } catch (e: Exception) {
                                    Log.e(TAG, "capture failed", e)
                                    readerReady.countDown()
                                }
                            }
                            override fun onConfigureFailed(session: android.hardware.camera2.CameraCaptureSession) {
                                readerReady.countDown()
                            }
                        }, android.os.Handler(handler))

                    readerReady.await(3, java.util.concurrent.TimeUnit.SECONDS)

                    image?.use { img ->
                        val buffer = img.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)

                        val dir = File(context.filesDir, "intruders").apply { mkdirs() }
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val file = File(dir, "intruder_$stamp.jpg")
                        file.writeBytes(bytes)
                        photoPath = file.absolutePath
                    }

                    device.close()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Intruder selfie unavailable: ${e.message}")
                photoPath = null
            }
            android.os.Handler(context.mainLooper).post { onDone(photoPath) }
        }.start()
    }

    /** Latest N intruder photos, newest first. */
    fun latestPhotos(context: Context, count: Int = 12): List<File> {
        val dir = File(context.filesDir, "intruders")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.sortedByDescending { it.name }?.take(count) ?: emptyList()
    }
}
