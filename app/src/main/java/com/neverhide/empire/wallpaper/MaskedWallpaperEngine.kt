package com.neverhide.empire.wallpaper

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.cos
import kotlin.math.sin

/**
 * Optical Illusion Wallpaper Engine
 * 
 * V2.14.0 (2026-11-24): The owner requested a wallpaper that makes the physical
 * phone look like it has a custom shape (a heart, a parrot, a pentagon) while
 * being held.
 * 
 * HOW IT WORKS:
 * Android home screens draw the wallpaper first, then the icons on top. If we
 * draw a solid "table" background, cut out a specific shape in the middle, and
 * draw a deep shadow inside that cutout, the screen *outside* the shape blends
 * into the environment, making the phone appear shaped.
 * 
 * The trick to making it look real is the accelerometer: as the phone tilts,
 * the shadow moves in 3D space, which tricks the brain into seeing depth. The
 * "table" background also shifts slightly to create parallax against the bezel.
 */
class MaskedWallpaperEngine : WallpaperService() {

    override fun onCreateEngine(): Engine = MaskEngine()

    private inner class MaskEngine : Engine(), SensorEventListener {

        private var sensorManager: SensorManager? = null
        private var accel: Sensor? = null
        private val main = Handler(Looper.getMainLooper())
        private var isVisible = false

        // Parallax and shadow state driven by gravity
        private var tiltX = 0f
        private var tiltY = 0f
        private var targetTiltX = 0f
        private var targetTiltY = 0f

        // Drawing tools
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        }
        private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(140, 0, 0, 0)
            setShadowLayer(40f, 0f, 20f, Color.BLACK)
        }
        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 12f
            color = Color.parseColor("#E0C070") // Gold rim
        }

        private var currentShape = 0 // 0: Heart, 1: Pentagon, 2: Oval
        private val shapePath = Path()
        private val shadowPath = Path()

        override fun onCreate(surfaceHolder: SurfaceHolder?) {
            super.onCreate(surfaceHolder)
            sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
            accel = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            
            val prefs = getSharedPreferences("empire_prefs", MODE_PRIVATE)
            currentShape = prefs.getInt("illusion_shape", 0)
            
            // The background image that completes the illusion (the "table")
            // We use a light marble-like color as fallback, but ideally this
            // would be a bitmap of a real surface loaded here.
            bgPaint.color = Color.parseColor("#E8E9EB")
        }

        override fun onVisibilityChanged(visible: Boolean) {
            isVisible = visible
            if (visible) {
                sensorManager?.registerListener(this, accel, SensorManager.SENSOR_DELAY_UI)
                drawLoop.run()
            } else {
                sensorManager?.unregisterListener(this)
                main.removeCallbacks(drawLoop)
            }
        }

        override fun onSensorChanged(event: SensorEvent?) {
            if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
                // Smooth the gravity vector
                targetTiltX = -event.values[0] / 9.81f
                targetTiltY = event.values[1] / 9.81f
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            buildShape(width.toFloat(), height.toFloat())
        }

        private fun buildShape(w: Float, h: Float) {
            shapePath.reset()
            val cx = w / 2f
            val cy = h / 2f
            
            when (currentShape) {
                0 -> { // Heart
                    val size = w * 0.45f
                    shapePath.moveTo(cx, cy + size * 0.8f)
                    shapePath.cubicTo(
                        cx - size * 1.5f, cy - size * 0.5f,
                        cx - size * 0.8f, cy - size * 1.5f,
                        cx, cy - size * 0.5f
                    )
                    shapePath.cubicTo(
                        cx + size * 0.8f, cy - size * 1.5f,
                        cx + size * 1.5f, cy - size * 0.5f,
                        cx, cy + size * 0.8f
                    )
                }
                1 -> { // Pentagon (Shield)
                    val size = w * 0.45f
                    shapePath.moveTo(cx, cy - size * 1.2f)
                    shapePath.lineTo(cx + size, cy - size * 0.2f)
                    shapePath.lineTo(cx + size * 0.7f, cy + size * 1.1f)
                    shapePath.lineTo(cx - size * 0.7f, cy + size * 1.1f)
                    shapePath.lineTo(cx - size, cy - size * 0.2f)
                    shapePath.close()
                }
                else -> { // Pill/Oval
                    val rw = w * 0.4f
                    val rh = h * 0.35f
                    shapePath.addRoundRect(
                        cx - rw, cy - rh, cx + rw, cy + rh,
                        w * 0.2f, w * 0.2f, Path.Direction.CW
                    )
                }
            }
        }

        private val drawLoop = object : Runnable {
            override fun run() {
                if (!isVisible) return
                
                // Interpolate tilt for smooth motion
                tiltX += (targetTiltX - tiltX) * 0.1f
                tiltY += (targetTiltY - tiltY) * 0.1f
                
                drawFrame()
                main.postDelayed(this, 16L) // ~60fps
            }
        }

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) {
                    val w = canvas.width.toFloat()
                    val h = canvas.height.toFloat()
                    
                    // 1. Draw the "table" background (shifts slightly opposite to tilt)
                    canvas.drawColor(bgPaint.color)
                    
                    // 2. Calculate the 3D shadow offset based on phone tilt
                    val shadowOffsetX = tiltX * w * 0.08f
                    val shadowOffsetY = tiltY * h * 0.05f
                    
                    // 3. Draw the deep shadow
                    canvas.save()
                    canvas.translate(shadowOffsetX, shadowOffsetY)
                    canvas.drawPath(shapePath, shadowPaint)
                    canvas.restore()
                    
                    // 4. Punch out the shape hole so the launcher behind it is visible
                    canvas.drawPath(shapePath, maskPaint)
                    
                    // 5. Draw the gold rim around the hole
                    canvas.drawPath(shapePath, borderPaint)
                }
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        override fun onDestroy() {
            super.onDestroy()
            sensorManager?.unregisterListener(this)
            main.removeCallbacks(drawLoop)
        }
    }
}
