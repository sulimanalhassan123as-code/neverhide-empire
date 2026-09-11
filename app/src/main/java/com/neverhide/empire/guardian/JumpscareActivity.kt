package com.neverhide.empire.guardian

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The Adrenaline Jumpscare — fullscreen scare that pops over the lock screen
 * when a wrong password attempt is detected.
 *
 * Four themes: 0 Water 🌊  1 Fire 🔥  2 Thunder ⚡  3 Shadow Void 🌑
 * Renders with a dependency-free custom View (instant launch), vibrates,
 * plays a themed siren at max alarm volume, shows the intruder selfie if one
 * was captured, and auto-dismisses after ~10 seconds.
 */
class JumpscareActivity : ComponentActivity() {

    companion object {
        fun launch(context: Context, theme: Int, photoPath: String?) {
            GuardianLauncher.launch(context, theme, photoPath)
        }
    }

    private var vibrator: Vibrator? = null
    private var soundPool: SoundPool? = null
    private var sirenId = 0
    private var sirenStream = 0

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val theme = intent.getIntExtra("theme", 0)
        val photoPath = intent.getStringExtra("photo")

        // Max volume on the alarm stream
        val audio = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        audio.setStreamVolume(
            android.media.AudioManager.STREAM_ALARM,
            audio.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0
        )

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        root.addView(
            JumpscareCanvas(this, theme),
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val text = TextView(this).apply {
            text = when (theme) {
                1 -> "🔥 YOU ARE BURNING BRIDGES\nWRONG PASSWORD DETECTED"
                2 -> "⚡ THUNDER STRIKES THE THIEF\nWRONG PASSWORD DETECTED"
                3 -> "🌑 THE VOID SEES YOU\nWRONG PASSWORD DETECTED"
                else -> "🌊 THE STORM RISES\nWRONG PASSWORD DETECTED"
            }
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            setShadowLayer(12f, 0f, 0f, glowColor(theme))
            setPadding(48, 0, 48, 300)
        }
        root.addView(
            text,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Intruder selfie preview (if captured)
        if (photoPath != null && File(photoPath).exists()) {
            runCatching {
                BitmapFactory.decodeFile(photoPath)?.let { bmp ->
                    val iv = ImageView(this).apply {
                        setImageBitmap(bmp)
                        background = ContextCompat.getDrawable(
                            this@JumpscareActivity,
                            android.R.drawable.dialog_holo_light_frame
                        )
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        alpha = 0.9f
                    }
                    root.addView(
                        iv, FrameLayout.LayoutParams(
                            300, 380,
                            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        ).apply { bottomMargin = 460 }
                    )
                }
            }
        }

        // GUARDIAN 2.0 — FX mode: 1 = gunshot + cracked screen (default), 0 = classic siren
        val fxMode = getSharedPreferences("guardian_prefs", MODE_PRIVATE)
            .getInt("guardian_fx_mode", 1)
        if (fxMode == 1) {
            // Shattered-glass overlay on top of everything
            root.addView(CrackOverlayView(this), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }

        setContentView(root)

        startVibration()
        if (fxMode == 1) startGunshot() else startSiren()

        android.os.Handler(mainLooper).postDelayed({ finish() }, 10_000)
        root.setOnClickListener { finish() } // owner taps to dismiss
    }

    private fun glowColor(theme: Int): Int = when (theme) {
        1 -> 0xFFFF5722.toInt()
        2 -> 0xFF00E5FF.toInt()
        3 -> 0xFF7C4DFF.toInt()
        else -> 0xFF0288D1.toInt()
    }

    private fun startVibration() {
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        val pattern = longArrayOf(
            0, 500, 100, 500, 100, 500, 100, 500, 200, 500, 100, 500, 100, 500
        )
        if (Build.VERSION.SDK_INT >= 26) {
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(pattern, -1)
        }
    }

    private fun startGunshot() {
        // Procedurally-synthesized gunshot at max alarm volume
        soundPool = SoundPool.Builder().setMaxStreams(1)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            ).build()
        GunshotSynth.play(this, soundPool)
    }

    private fun startSiren() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(attrs).build()
        sirenId = soundPool?.load(
            android.provider.Settings.System.DEFAULT_ALARM_ALERT_URI.toString(), 1
        ) ?: -1
        soundPool?.setOnLoadCompleteListener { _, id, status ->
            if (id == sirenId && status == 0) {
                sirenStream = soundPool?.play(sirenId, 1f, 1f, 1, 2, 1f) ?: 0
            }
        }
    }

    override fun onDestroy() {
        vibrator?.cancel()
        runCatching { soundPool?.stop(sirenStream) }
        runCatching { soundPool?.release() }
        soundPool = null
        super.onDestroy()
    }

    /** Lightweight custom view rendering themed particles at ~60fps. */
    private class JumpscareCanvas(context: Context, private val theme: Int) : View(context) {

        private val particles = List(160) { Particle(Random.nextFloat(), Random.nextFloat()) }
        private var t = 0f
        private var last = System.nanoTime()
        private val paint = Paint().apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private data class Particle(val u: Float, val v: Float)

        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Long.MAX_VALUE
            addUpdateListener {
                val now = System.nanoTime()
                t += (now - last) / 1_000_000_000f
                last = now
                invalidate()
            }
            start()
        }

        override fun onDetachedFromWindow() {
            animator.cancel()
            super.onDetachedFromWindow()
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(
                when (theme) {
                    1 -> 0xFF1A0000.toInt()
                    2 -> 0xFF00121A.toInt()
                    3 -> 0xFF05000A.toInt()
                    else -> 0xFF001220.toInt()
                }
            )
            when (theme) {
                1 -> drawFire(canvas, w, h)
                2 -> drawThunder(canvas, w, h)
                3 -> drawVoid(canvas, w, h)
                else -> drawWater(canvas, w, h)
            }
        }

        private fun drawFire(c: Canvas, w: Float, h: Float) {
            particles.forEach { p ->
                val y = h - ((p.u + t * (0.3f + p.v * 0.7f)) % 1f) * h
                val x = p.v * w + sin(t * 4f + p.u * 20f) * 24f
                paint.color = if ((p.u * 10).toInt() % 3 == 0) 0xFFFFD600.toInt() else 0xFFFF3D00.toInt()
                val size = 14f * (1f - (y / h) * 0.6f)
                c.drawCircle(x, y, size, paint)
            }
        }

        private fun drawWater(c: Canvas, w: Float, h: Float) {
            repeat(4) { layer ->
                val phase = t * 1.6f + layer * 1.4f
                val baseY = h * (0.35f + layer * 0.14f)
                val amp = 40f + layer * 12f
                val path = Path().apply { moveTo(0f, h) }
                var x = 0f
                while (x <= w) {
                    path.lineTo(x, baseY + sin((x / w) * 9.4f + phase) * amp)
                    x += 16f
                }
                path.lineTo(w, h); path.close()
                paint.color = when (layer) {
                    0 -> 0xFF0277BD.toInt(); 1 -> 0xFF0288D1.toInt()
                    2 -> 0xFF039BE5.toInt(); else -> 0xFF4FC3F7.toInt()
                }
                paint.alpha = 160
                c.drawPath(path, paint)
            }
            particles.take(60).forEach { p ->
                val y = h - ((p.u + t * 0.25f) % 1f) * h
                paint.color = 0xAA4FC3F7.toInt()
                c.drawCircle(p.v * w, y, 6f * p.v, paint)
            }
        }

        private fun drawThunder(c: Canvas, w: Float, h: Float) {
            val flash = (sin(t * 18f) + 1f) / 2f
            paint.color = 0xFF00121A.toInt()
            c.drawRect(0f, 0f, w, h, paint)
            paint.color = Color.argb((flash * 90).toInt(), 0, 229, 255)
            c.drawRect(0f, 0f, w, h, paint)

            repeat(3) { bolt ->
                var x = w * (0.2f + bolt * 0.3f)
                var y = 0f
                paint.color = 0xFF00E5FF.toInt()
                paint.strokeWidth = 6f - bolt * 1.5f
                val path = Path().apply { moveTo(x, y) }
                while (y < h) {
                    x += (Random.nextFloat() - 0.5f) * 90f
                    y += h / 14f
                    path.lineTo(x, y)
                }
                c.drawPath(path, paint)
            }
            particles.take(50).forEach { p ->
                val x = (p.u + sin(t * 3f + p.v * 10f) * 0.1f) * w
                val y = (p.v + t * 0.5f) % 1f * h
                paint.color = 0xFF00E5FF.toInt()
                c.drawCircle(x, y, 5f * p.u, paint)
            }
        }

        private fun drawVoid(c: Canvas, w: Float, h: Float) {
            val pulse = (sin(t * 3f) + 1f) / 2f
            paint.color = 0xFF05000A.toInt()
            c.drawRect(0f, 0f, w, h, paint)
            repeat(5) { ring ->
                val r = (t * 180f + ring * 160f) % (h * 0.9f)
                paint.color = Color.argb(
                    (180 * (1f - r / (h * 0.9f))).toInt(), 124, 77, 255
                )
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 8f * pulse + 2f
                c.drawCircle(w / 2f, h / 2f, r, paint)
                paint.style = Paint.Style.FILL
            }
            particles.take(40).forEach { p ->
                val x = w / 2f + sin(t * 1.2f + p.u * 6.28f) * p.v * w * 0.4f
                val y = h / 2f + cos(t * 1.5f + p.v * 6.28f) * p.u * h * 0.4f
                paint.color = Color.argb(120, 49, 27, 146)
                c.drawCircle(x, y, 10f + 14f * pulse * p.u, paint)
            }
        }
    }
}
