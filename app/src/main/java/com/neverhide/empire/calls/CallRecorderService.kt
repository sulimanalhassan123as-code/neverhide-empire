package com.neverhide.empire.calls

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CALLS SECTION — Call Recorder.
 *
 * HONEST ENGINEERING: Android 10+ blocks third-party apps from the
 * raw call stream (CAPTURE_AUDIO_OUTPUT is system-only). The method
 * real recorders like Cube ACR use on modern Android: put the call on
 * SPEAKER and record the microphone — this works on every phone with
 * zero special privileges. This service does exactly that:
 *
 *  1. Detects call state (ringing / active / ended) via TelephonyManager
 *  2. When recording is started during a call, forces speakerphone ON
 *  3. Records the mic to a WAV file
 *  4. AUTO-STOPS and saves when the call goes idle
 *  5. Stores number + timestamp + duration as the filename metadata
 *
 * Legal note shown in UI: recording calls may require consent by law
 * depending on country — the user is responsible for compliance.
 */
class CallRecorderService : Service() {

    companion object {
        const val CHANNEL = "call_recorder"
        const val NOTIFICATION_ID = 4400
        const val ACTION_START = "com.neverhide.empire.callrec.START"
        const val ACTION_STOP = "com.neverhide.empire.callrec.STOP"
        val STATE_CHANGED = "com.neverhide.empire.callrec.STATE"

        var isRecording = false
        var recordedNumber = ""
        var lastPhoneState = TelephonyManager.CALL_STATE_IDLE

        fun dir(context: Context): File =
            File(context.filesDir, "call-recordings").apply { mkdirs() }

        fun start(context: Context) {
            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return
            context.startForegroundService(
                Intent(context, CallRecorderService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, CallRecorderService::class.java).setAction(ACTION_STOP)
            )
        }
    }

    private val RATE = 44100
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var telephony: TelephonyManager? = null
    private var outFile: File? = null
    private var pcmAccumulator: File? = null
    private var prevSpeakerOn = false
    private var touchedAudio = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Call Recorder", NotificationManager.IMPORTANCE_LOW)
        )
        telephony = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        // Track call state so we auto-stop when the call ends
        @Suppress("DEPRECATION")
        telephony?.listen(object : PhoneStateListener() {
            @Deprecated("TelephonyCallback on newer APIs")
            override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                lastPhoneState = state
                if (!incomingNumber.isNullOrBlank()) recordedNumber = incomingNumber
                sendBroadcast(Intent(STATE_CHANGED).apply {
                    setPackage(packageName)
                    putExtra("state", state)
                    putExtra("recording", isRecording)
                })
                if (state == TelephonyManager.CALL_STATE_IDLE && isRecording) {
                    stopSelf()
                }
            }
        }, PhoneStateListener.LISTEN_CALL_STATE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_START -> if (!isRecording) startRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        // ===== SHIZUKU TRUE-CAPTURE PATH =====
        // If Shizuku is running + permission granted, record BOTH sides
        // directly from the call stream — no speakerphone needed.
        if (ShizukuCallCapture.available() && ShizukuCallCapture.permissionGranted()) {
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val safeNumber = recordedNumber.replace(Regex("[^0-9+]"), "").ifBlank { "unknown" }
            val wav = File(dir(this), "${safeNumber}_$stamp.wav")
            if (ShizukuCallCapture.start(this, wav.absolutePath)) {
                isRecording = true
                startForeground(NOTIFICATION_ID, buildNotification("📡 TRUE CAPTURE — both sides, direct from call stream"))
                return
            }
        }
        // ===== SPEAKER-MIC FALLBACK PATH =====
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, RATE / 2)
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord = null
            stopSelf()
            return
        }

        // HEADSET-AWARE ROUTING (v2.5.1): forcing speakerphone while a
        // wired/Bluetooth headset is connected fights the call's audio
        // routing on Samsung devices — calls fail to connect until the
        // headset is removed. If a headset is connected we record the
        // near side with the mic and DO NOT touch the audio path at all.
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val headsetConnected = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
            it.type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }
        if (headsetConnected) {
            captureText = "🎧 Headset connected — call audio untouched, near side recorded. Tap to stop."
        } else {
            prevSpeakerOn = am.isSpeakerphoneOn
            touchedAudio = true
            runCatching { am.isSpeakerphoneOn = true }
        }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neverhide:CallRec").apply {
                setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L)
            }

        isRecording = true
        startForeground(NOTIFICATION_ID, buildNotification(captureText))

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeNumber = recordedNumber.replace(Regex("[^0-9+]"), "").ifBlank { "unknown" }
        pcmAccumulator = File(dir(this), "tmp_$stamp.pcm")

        recordingThread = Thread {
            try {
                val buf = ShortArray(RATE / 2)
                audioRecord?.startRecording()
                val fos = FileOutputStream(pcmAccumulator)
                while (isRecording) {
                    val n = audioRecord?.read(buf, 0, buf.size) ?: 0
                    if (n > 0) {
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            val s = buf[i].toInt()
                            bytes[i * 2] = (s and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        fos.write(bytes)
                    }
                }
                fos.close()
                pcmToWav(pcmAccumulator!!, File(dir(this), "${safeNumber}_$stamp.wav"))
                pcmAccumulator?.delete()
            } catch (_: Exception) {
                runCatching { pcmAccumulator?.delete() }
            } finally {
                runCatching { audioRecord?.stop() }
                audioRecord?.release(); audioRecord = null
            }
        }.apply { start() }
    }

    /** Wrap raw PCM in a playable WAV container. */
    private fun pcmToWav(pcm: File, out: File) {
        val dataSize = pcm.length().toInt()
        val fos = FileOutputStream(out)
        fun le(v: Int) { fos.write(v and 0xFF); fos.write((v shr 8) and 0xFF); fos.write((v shr 16) and 0xFF); fos.write((v shr 24) and 0xFF) }
        fos.write("RIFF".toByteArray()); le(36 + dataSize)
        fos.write("WAVE".toByteArray())
        fos.write("fmt ".toByteArray()); le(16)
        le(1); le(1)                      // PCM, mono
        le(RATE); le(RATE * 2)
        le(2); le(16)
        fos.write("data".toByteArray()); le(dataSize)
        pcm.inputStream().use { it.copyTo(fos) }
        fos.close()
    }

    private var captureText = "Speaker is ON so both sides are captured. Tap to stop."

    private fun buildNotification(text: String = "Speaker is ON so both sides are captured. Tap to stop."): Notification {
        captureText = text
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, CallRecorderService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 1,
            Intent(this, CallRecordingsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("⏺ Recording call")
            .setContentText(captureText)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPi)
            .addAction(android.R.drawable.ic_media_pause, "Stop & save", stopPi)
            .build()
    }

    override fun onDestroy() {
        isRecording = false
        runCatching { ShizukuCallCapture.stop() }
        // Restore the audio routing we changed (v2.5.1) — never leave
        // the speaker forced on after a recording ends
        if (touchedAudio) {
            runCatching {
                val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.isSpeakerphoneOn = prevSpeakerOn
            }
            touchedAudio = false
        }
        recordingThread?.join(1500)
        // Give the writer a moment, then release everything
        runCatching { audioRecord?.release() }
        wakeLock?.let { if (it.isHeld) it.release() }
        @Suppress("DEPRECATION")
        telephony?.listen(null, PhoneStateListener.LISTEN_NONE)
        sendBroadcast(Intent(STATE_CHANGED).apply { setPackage(packageName); putExtra("recording", false) })
        super.onDestroy()
    }
}
