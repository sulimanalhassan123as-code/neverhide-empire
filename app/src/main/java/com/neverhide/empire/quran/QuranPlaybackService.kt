package com.neverhide.empire.quran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.IBinder
import android.os.PowerManager

/**
 * QURAN SECTION — offline playback engine.
 *
 * Foreground service + partial wake lock keeps recitation playing even
 * when the phone is in deep sleep / Doze. Auto-advances surah after
 * surah through the downloaded library and remembers the last position,
 * so reopening the app resumes exactly where you stopped.
 */
class QuranPlaybackService : Service() {

    companion object {
        const val CHANNEL = "quran_playback"
        const val NOTIFICATION_ID = 4202
        const val ACTION_PLAY = "PLAY"
        const val ACTION_PAUSE = "PAUSE"
        const val ACTION_STOP = "STOP"
        const val ACTION_NEXT = "NEXT"
        const val ACTION_PREV = "PREV"
        val STATE = "com.neverhide.empire.quran.PLAYBACK_STATE"

        var isPlaying = false
        var currentSurah = 0
        var currentReciter = ""

        fun play(context: Context, surahNumber: Int) {
            val i = Intent(context, QuranPlaybackService::class.java)
                .setAction(ACTION_PLAY).putExtra("surah", surahNumber)
            context.startForegroundService(i)
        }

        fun send(context: Context, action: String) {
            context.startForegroundService(
                Intent(context, QuranPlaybackService::class.java).setAction(action)
            )
        }

        fun stop(context: Context) {
            send(context, ACTION_STOP)
        }
    }

    private var player: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var audioManager: AudioManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(
                NotificationChannel(CHANNEL, "Quran Playback", NotificationManager.IMPORTANCE_LOW)
            )
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neverhide:QuranPlayback").apply {
                setReferenceCounted(false)
            }
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> stopSelf()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK ->
                        player?.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        player?.setVolume(1f, 1f)
                        if (isPlaying) player?.start()
                    }
                }
            }
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val n = intent.getIntExtra("surah", 1)
                startPlayback(n)
            }
            ACTION_PAUSE -> {
                if (player?.isPlaying == true) {
                    player?.pause()
                    isPlaying = false
                    savePosition()
                    updateNotification()
                    broadcast()
                }
            }
            ACTION_NEXT -> startPlayback(currentSurah + 1)
            ACTION_PREV -> startPlayback(currentSurah - 1)
            ACTION_STOP -> {
                savePosition()
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startPlayback(surahNumber: Int) {
        val reciter = QuranLibrary.reciter(this)
        if (surahNumber < 1 || surahNumber > 114) return
        val file = QuranLibrary.fileFor(this, reciter, surahNumber)
        if (!file.exists()) return // only offline files playable

        savePosition()

        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setWakeMode(this@QuranPlaybackService, PowerManager.PARTIAL_WAKE_LOCK)
            setOnCompletionListener {
                // Auto-advance: continuous recitation through the library
                val next = currentSurah + 1
                if (next <= 114 && QuranLibrary.isDownloaded(this@QuranPlaybackService, reciter, next)) {
                    startPlayback(next)
                } else {
                    stopSelf()
                }
            }
            prepare()
            start()
        }
        currentSurah = surahNumber
        currentReciter = reciter.id
        isPlaying = true

        // Remember last-played for auto-resume
        getSharedPreferences(QuranLibrary.PREFS, MODE_PRIVATE).edit()
            .putInt(QuranLibrary.KEY_LAST_SURAH, surahNumber)
            .putLong(QuranLibrary.KEY_LAST_POSITION, 0L)
            .apply()

        wakeLock?.acquire(6 * 60 * 60 * 1000L) // up to 6h continuous playback
        audioManager?.requestAudioFocus(focusRequest!!)
        startForeground(NOTIFICATION_ID, buildNotification())
        broadcast()
    }

    private fun savePosition() {
        val pos = player?.currentPosition ?: 0
        if (pos > 0) {
            getSharedPreferences(QuranLibrary.PREFS, MODE_PRIVATE).edit()
                .putLong(QuranLibrary.KEY_LAST_POSITION, pos.toLong())
                .apply()
        }
    }

    private fun buildNotification(): Notification {
        val surah = Surahs.byNumber(currentSurah)
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, QuranActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun act(action: String, title: String) = Notification.Action.Builder(
            android.R.drawable.ic_media_play, title,
            PendingIntent.getService(
                this, action.hashCode(),
                Intent(this, QuranPlaybackService::class.java).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        ).build()

        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("📖 ${surah.englishName}")
            .setContentText("${surah.arabicName} — ${QuranLibrary.reciter(this@QuranPlaybackService).name}")
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(act(ACTION_PREV, "⏮"))
            .addAction(act(if (isPlaying) ACTION_PAUSE else ACTION_PLAY, if (isPlaying) "⏸" else "▶"))
            .addAction(act(ACTION_NEXT, "⏭"))
            .build()
    }

    private fun updateNotification() {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
    }

    private fun broadcast() {
        sendBroadcast(Intent(STATE).apply {
            setPackage(packageName)
            putExtra("surah", currentSurah)
            putExtra("playing", isPlaying)
        })
    }

    override fun onDestroy() {
        savePosition()
        player?.release()
        player = null
        wakeLock?.let { if (it.isHeld) it.release() }
        focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
        isPlaying = false
        broadcast()
        super.onDestroy()
    }
}
