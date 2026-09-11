package com.neverhide.empire.guardian

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.view.View
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * GUARDIAN 2.0 FX — the MacroDroid-style punishment pack.
 *
 * 1. GunshotSynth: a procedurally-synthesized GUNSHOT (no sound files
 *    needed — pure math): white-noise crack + sub-bass thump + reverb
 *    tail, rendered once to a WAV and played at max alarm volume.
 * 2. CrackOverlayView: a SHATTERED-SCREEN effect — jagged cracks
 *    radiating from a random impact point with concentric ring
 *    fractures and a white flash, drawn on a transparent overlay so it
 *    looks like the phone's glass broke.
 */
object GunshotSynth {

    private const val RATE = 44100

    /** Render a ~1.2s gunshot WAV to cache. Returns the file (cached). */
    fun get(context: Context): File {
        val f = File(context.cacheDir, "gunshot.wav")
        if (f.exists()) return f
        val n = (RATE * 1.2).toInt()
        val pcm = ShortArray(n)

        // --- Phase 1: the crack (white noise, hyper-fast decay) ---
        var noise = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            noise = noise * 0.55 + (Random.nextDouble() * 2 - 1) * 0.45  // lowpassed-ish snap
            val crackEnv = exp(-t * 90.0)
            // --- Phase 2: the body thump (150 Hz sweep down to 45 Hz) ---
            val freq = 150.0 * exp(-t * 8.0) + 45.0
            val bodyEnv = exp(-t * 14.0)
            val body = sin(2 * PI * freq * t) * bodyEnv * 0.9
            // --- Phase 3: reverb tail (sparse echoes of the crack) ---
            val tail = noise * exp(-t * 3.0) * 0.25 * (1 + 0.6 * sin(2 * PI * 0.7 * t))
            val v = (noise * crackEnv * 1.4 + body + tail) * 12000
            pcm[i] = v.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }

        // soft clip for punch
        for (i in pcm.indices) {
            val x = pcm[i] / 32767.0
            pcm[i] = ((x * (1.0 - x * x / 3.0)) * 32767.0).toInt().toShort()
        }

        writeWav(f, pcm)
        return f
    }

    /** Play the gunshot at MAX alarm volume. Returns the SoundPool stream id. */
    fun play(context: Context, pool: SoundPool?): Int {
        return runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audio.setStreamVolume(
                AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
            )
            val p = pool ?: SoundPool.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                ).build()
            val id = p.load(get(context).absolutePath, 1)
            // SoundPool.load is async; poll briefly then play
            Thread {
                for (i in 0 until 50) {
                    Thread.sleep(60)
                    val s = p.play(id, 1f, 1f, 1, 0, 1f)
                    if (s != 0) return@Thread
                }
            }.start()
            1
        }.getOrDefault(0)
    }

    private fun writeWav(out: File, pcm: ShortArray) {
        val dataSize = pcm.size * 2
        val fos = FileOutputStream(out)
        fun le(v: Int) {
            fos.write(v and 0xFF); fos.write((v shr 8) and 0xFF)
            fos.write((v shr 16) and 0xFF); fos.write((v shr 24) and 0xFF)
        }
        fos.write("RIFF".toByteArray()); le(36 + dataSize)
        fos.write("WAVE".toByteArray())
        fos.write("fmt ".toByteArray()); le(16)
        le(1); le(1)
        le(RATE); le(RATE * 2)
        le(2); le(16)
        fos.write("data".toByteArray()); le(dataSize)
        for (s in pcm) {
            fos.write(s.toInt() and 0xFF); fos.write((s.toInt() shr 8) and 0xFF)
        }
        fos.close()
    }
}

/**
 * Shattered-glass overlay — procedural crack pattern from a random
 * impact point. Looks exactly like a broken phone screen. :)
 */
class CrackOverlayView(context: android.content.Context) : View(context) {

    private val crackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.5f
    }
    private val thinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 230, 235, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private var flashAlpha = 220
    private val flashPaint = Paint().apply { color = Color.WHITE }
    private val segments = 9 + (0..5).random()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width * (0.25f + Math.random().toFloat() * 0.5f)
        val cy = height * (0.2f + Math.random().toFloat() * 0.6f)
        val reach = min(width, height) * 0.9f

        // radial jagged cracks
        repeat(segments) { s ->
            val base = (2 * PI * s / segments).toFloat()
            val path = Path()
            path.moveTo(cx, cy)
            var x = cx; var y = cy
            var ang = base
            repeat(7) {
                ang += (Math.random().toFloat() - 0.5f) * 0.5f
                val seg = reach / 7f * (0.6f + Math.random().toFloat() * 0.8f)
                x += (seg * cos(ang)); y += (seg * sin(ang))
                path.lineTo(x, y)
            }
            canvas.drawPath(path, crackPaint)
            // thin satellite hairline cracks
            repeat(2) {
                val p2 = Path()
                p2.moveTo(x, y)
                val a2 = ang + (Math.random().toFloat() - 0.5f) * 1.6f
                p2.lineTo(x + reach * 0.12f * cos(a2), y + reach * 0.12f * sin(a2))
                canvas.drawPath(p2, thinPaint)
            }
        }

        // concentric ring fractures
        var r = reach * 0.10f
        while (r < reach * 0.7f) {
            val ring = Path()
            val steps = 40
            for (i in 0..steps) {
                val a = (2 * PI * i / steps).toFloat()
                val jitter = 1f + (Math.random().toFloat() - 0.5f) * 0.15f
                val px = cx + r * jitter * cos(a)
                val py = cy + r * jitter * sin(a)
                if (i == 0) ring.moveTo(px, py) else ring.lineTo(px, py)
            }
            canvas.drawPath(ring, thinPaint)
            r *= 1.35f
        }

        // white impact flash
        flashPaint.alpha = flashAlpha
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
        if (flashAlpha > 0) {
            flashAlpha = (flashAlpha * 0.86f).toInt().coerceAtLeast(0)
            postInvalidateOnAnimation()
        }
    }
}
