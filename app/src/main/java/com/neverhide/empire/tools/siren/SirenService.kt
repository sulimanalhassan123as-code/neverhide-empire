package com.neverhide.empire.tools.siren

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.hardware.camera2.CameraManager

/**
 * Find-my-phone Siren service — generates a loud two-tone emergency siren
 * purely in code (AudioTrack sine sweep, no asset needed), plays it at max
 * volume on the ALARM stream, and strobes the camera flash. Used by:
 *  • Manual "find my phone" trigger
 *  • Low-battery alarm (BatteryGuardReceiver)
 *  • SIM-change alert (SimGuardReceiver)
 */
class SirenService : Service() {

    companion object {
        const val CHANNEL_ID = "empire_siren"
        const val NOTIF_ID = 9005

        const val MODE_FIND_PHONE = "find_phone"
        const val MODE_BATTERY = "battery"
        const val MODE_SIM_ALERT = "sim_alert"
        const val MODE_INTRUDER = "intruder"

        private const val SAMPLE_RATE = 44100
        private const val SWEEP_SECONDS = 4 // one siren loop

        fun start(context: Context, mode: String = MODE_FIND_PHONE, autoStopSeconds: Int = 30) {
            val i = Intent(context, SirenService::class.java).apply {
                putExtra("mode", mode)
                putExtra("auto_stop", autoStopSeconds)
            }
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SirenService::class.java))
        }
    }

    private var audioTrack: AudioTrack? = null
    private var torchHandler: Handler? = null
    private var torchOn = false
    private var autoStopHandler: Handler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.getStringExtra("mode") ?: MODE_FIND_PHONE
        val autoStop = intent?.getIntExtra("auto_stop", 30) ?: 30

        createChannel()
        if (Build.VERSION.SDK_INT >= 26) {
            startForeground(NOTIF_ID, buildNotification(mode))
        }

        startSiren(mode)
        startTorchStrobe()

        // Auto-stop so it never wails forever
        autoStopHandler = Handler(Looper.getMainLooper())
        autoStopHandler?.postDelayed({ stopSelf() }, autoStop * 1000L)

        return START_NOT_STICKY
    }

    /** Generate a classic two-tone emergency siren loop in pure code. */
    private fun startSiren(mode: String) {
        stopSirenSound()

        val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0
        )

        val totalSamples = SAMPLE_RATE * SWEEP_SECONDS
        val pcm = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i / SAMPLE_RATE.toFloat()
            // Frequency sweeps 600Hz -> 1200Hz and back over the loop
            val phase = t % SWEEP_SECONDS
            val freq = if (phase < SWEEP_SECONDS / 2f)
                600f + (600f * (phase / (SWEEP_SECONDS / 2f)))
            else
                1200f - (600f * ((phase - SWEEP_SECONDS / 2f) / (SWEEP_SECONDS / 2f)))
            val sample = kotlin.math.sin(2 * Math.PI * freq * t)
            // Intruder/SIM modes get a harsher square-ish tone
            val shaped = if (mode == MODE_INTRUDER || mode == MODE_SIM_ALERT) {
                if (sample > 0) 0.9 else -0.9
            } else sample * 0.9
            pcm[i] = (shaped * Short.MAX_VALUE * 0.85).toInt().toShort()
        }

        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build().apply {
                write(pcm, 0, pcm.size)
                setLoopPoints(0, pcm.size, -1) // loop forever until stopped
                play()
            }
    }

    /** Strobe the camera flash for visual find-my-phone effect. */
    private fun startTorchStrobe() {
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        torchHandler = Handler(Looper.getMainLooper())
        val toggle = object : Runnable {
            override fun run() {
                torchOn = !torchOn
                val id = cm.cameraIdList.firstOrNull { cameraId ->
                    cm.getCameraCharacteristics(cameraId)
                        .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
                if (id != null) {
                    runCatching { cm.setTorchMode(id, torchOn) }
                }
                torchHandler?.postDelayed(this, 300)
            }
        }
        torchHandler?.post(toggle)
    }

    private fun stopSirenSound() {
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }

    private fun stopTorch() {
        torchHandler?.removeCallbacksAndMessages(null)
        val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.forEach { id ->
            runCatching { cm.setTorchMode(id, false) }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Emergency Siren", NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Find-my-phone siren and security alerts" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(mode: String): android.app.Notification {
        val text = when (mode) {
            MODE_BATTERY -> "🔋 Low battery alarm"
            MODE_SIM_ALERT -> "⚠️ SIM card changed!"
            MODE_INTRUDER -> "🚨 Intruder alert"
            else -> "🔊 Finding your phone…"
        }
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Neverhide Empire Siren")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        autoStopHandler?.removeCallbacksAndMessages(null)
        stopSirenSound()
        stopTorch()
        super.onDestroy()
    }
}
