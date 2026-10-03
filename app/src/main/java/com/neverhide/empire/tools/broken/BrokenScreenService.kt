package com.neverhide.empire.tools.broken

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.neverhide.empire.guardian.CrackOverlayView

/**
 * BROKEN SCREEN PRANK — arm it, hand your phone to a friend (or just carry it).
 *
 * Trigger: a very heavy shake (two sharp acceleration spikes within 700ms —
 * normal walking and pockets never fire it, a deliberate hard shake always does).
 *
 * Effect: a loud glass-shatter sound (generated 100% in code, no asset) +
 * a shattered-glass fullscreen overlay (random crack pattern every time).
 * The broken screen STAYS until you tap anywhere — then it vanishes and the
 * prank re-arms itself after a short cooldown.
 */
class BrokenScreenService : Service(), SensorEventListener {

    companion object {
        private const val CHANNEL_ID = "broken_screen"
        private const val NOTIF_ID = 9007
        private const val SHAKE_SPIKE = 22f      // m/s² above gravity
        private const val SHAKE_WINDOW_MS = 700L
        private const val COOLDOWN_MS = 4000L

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val i = Intent(context, BrokenScreenService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BrokenScreenService::class.java))
        }

        fun isRunning(context: Context) =
            context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
                .getBoolean("broken_screen_enabled", false)

        /** Manual test from the Toolkit UI. */
        fun breakNow(context: Context) {
            val i = Intent(context, BrokenScreenService::class.java).putExtra("break_now", true)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private var sensorManager: SensorManager? = null
    private var overlay: View? = null
    private var lastSpike = 0L
    private var lastBreak = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("break_now", false) == true && System.currentTimeMillis() - lastBreak > 800) {
            trigger()
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        createChannel()
        if (Build.VERSION.SDK_INT >= 26) startForeground(NOTIF_ID, buildNotification())

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(this)
        removeOverlay()
        super.onDestroy()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]; val y = event.values[1]; val z = event.values[2]
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble()).toFloat()
        val spike = magnitude - SensorManager.GRAVITY_EARTH

        if (spike > SHAKE_SPIKE) {
            val now = System.currentTimeMillis()
            if (now - lastSpike < SHAKE_WINDOW_MS) {
                lastSpike = 0
                if (now - lastBreak > COOLDOWN_MS) {
                    lastBreak = now
                    trigger()
                }
            } else {
                lastSpike = now // first spike of a heavy shake
            }
        }
    }

    // ===================== TRIGGER =====================

    private fun trigger() {
        if (overlay != null) return // already broken
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(CrackOverlayView(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        // small honest hint so the prank ends instantly for whoever holds it
        val hint = TextView(this).apply {
            text = "tap to continue"
            textSize = 10f
            setTextColor(0x80FFFFFF.toInt())
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 48)
        }
        root.addView(hint, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.setOnTouchListener { v, e ->
            if (e.action == MotionEvent.ACTION_DOWN) {
                removeOverlay()
                v.performClick()
                true
            } else false
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.TOP }

        runCatching { wm.addView(root, params) }.onSuccess { overlay = root }

        // sound + vibration
        Thread { playGlassShatter() }.start()
        runCatching {
            val vib = getSystemService(VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= 26)
                vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 40, 120), -1))
            else @Suppress("DEPRECATION") vib.vibrate(200)
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        val wm = getSystemService(WINDOW_SERVICE) as? WindowManager
        runCatching { wm?.removeView(v) }
    }

    // ===================== SOUND (pure DSP, no assets) =====================

    private fun playGlassShatter() {
        val sr = 44100
        val total = (sr * 0.8f).toInt()
        val buf = ShortArray(total)
        val rnd = java.util.Random()

        // 1. Initial crack: sharp noise burst with fast decay
        for (t in 0 until sr / 6) {
            val env = Math.exp(-t / (sr / 90.0)).toFloat()
            val n = (rnd.nextFloat() * 2 - 1) * 0.9f
            buf[t] = ((n * env) * Short.MAX_VALUE * 0.8f).toInt().toShort()
        }
        // 2. Shards: ~12 high-pitched chirps scattered in the next 500ms,
        //    each a decaying sine at a random glass frequency with a slight
        //    downward bend — the "tinkle" of falling fragments.
        repeat(12) { i ->
            val start = sr / 12 + rnd.nextInt(sr / 2)
            val f0 = 1800f + rnd.nextFloat() * 4200f
            val len = sr / 24 + rnd.nextInt(sr / 16)
            var phase = 0f
            for (t in 0 until len) {
                if (start + t >= total) break
                val bend = f0 * (1f - 0.25f * t / len)
                phase += 2f * Math.PI.toFloat() * bend / sr
                val env = Math.exp((-t / (len / 3.2)).toDouble()).toFloat()
                val s = (Math.sin(phase.toDouble()) * env * 0.5f).toFloat() // full float amplitude
                val idx = start + t
                val cur = buf[idx].toInt() / Short.MAX_VALUE.toFloat()
                buf[idx] = ((cur + s).coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            }
        }
        // 3. Short white noise "settle" tail
        for (t in sr / 2 until total) {
            val env = Math.exp((-(t - sr / 2) / (sr / 24.0)).toDouble()).toFloat() * 0.25f
            buf[t] = (((rnd.nextFloat() * 2 - 1) * env) * Short.MAX_VALUE).toInt().toShort()
        }

        // play loud on the alarm-style stream
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0) }

        val track = AudioTrack(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sr)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build(),
            buf.size * 2,
            AudioTrack.MODE_STATIC,
            1
        )
        runCatching {
            track.write(buf, 0, buf.size)
            track.play()
            val durMs = (total * 1000L) / sr
            Thread.sleep(durMs + 200)
            track.stop(); track.release()
        }
    }

    private fun buildNotification(): android.app.Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, CHANNEL_ID)
        else android.app.Notification.Builder(this)
        return builder
            .setContentTitle("🔨 Broken Screen prank armed")
            .setContentText("Shake the phone HARD to shatter it — tap to repair")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Broken Screen Prank", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Shake-triggered broken screen prank" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
