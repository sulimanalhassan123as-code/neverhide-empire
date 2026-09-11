package com.neverhide.empire.quran

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * QURAN SECTION — bulk offline downloader.
 *
 * Downloads every missing surah for the selected reciter into the
 * private library. Foreground service + wake lock means downloads
 * survive the screen turning off and Doze. Fully resumable: re-running
 * skips every surah already on disk.
 */
class QuranDownloadService : Service() {

    companion object {
        const val CHANNEL = "quran_download"
        const val NOTIFICATION_ID = 4201
        const val ACTION_STOP = "com.neverhide.empire.quran.STOP_DOWNLOAD"
        val PROGRESS = "com.neverhide.empire.quran.DOWNLOAD_PROGRESS"

        var isRunning = false
        var lastDone = 0
        var lastTotal = 114

        fun start(context: Context) {
            context.startForegroundService(Intent(context, QuranDownloadService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, QuranDownloadService::class.java))
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var cancelled = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled = true
            stopSelf()
            return START_NOT_STICKY
        }
        if (isRunning) return START_STICKY
        isRunning = true

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Neverhide:QuranDownload").apply {
                setReferenceCounted(false)
                acquire(4 * 60 * 60 * 1000L) // up to 4 hours
            }

        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(0, 114))

        Thread {
            val reciter = QuranLibrary.reciter(this)
            val missing = Surahs.all.filter {
                !QuranLibrary.isDownloaded(this, reciter, it.number)
            }
            lastTotal = missing.size
            lastDone = 0

            missing.forEach { surah ->
                if (cancelled) return@Thread
                val ok = runCatching { downloadSurah(reciter, surah.number) }.getOrDefault(false)
                if (ok) lastDone++
                sendBroadcast(Intent(PROGRESS).apply {
                    setPackage(packageName)
                    putExtra("done", lastDone)
                    putExtra("total", lastTotal)
                    putExtra("surah", surah.number)
                })
                if (!ok) {
                    // A surah failed — skip and continue; user can re-run to resume.
                    return@forEach
                }
                notifyProgress()
            }

            isRunning = false
            wakeLock?.let { if (it.isHeld) it.release() }
            stopSelf()
        }.start()

        return START_STICKY
    }

    private fun downloadSurah(reciter: QuranLibrary.Reciter, n: Int): Boolean {
        val url = "${reciter.url}/%03d.mp3".format(n)
        val dir = QuranLibrary.dirFor(this, reciter)
        val tmp = File(dir, "%03d.part".format(n))
        val out = QuranLibrary.fileFor(this, reciter, n)

        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        conn.inputStream.use { input ->
            FileOutputStream(tmp).use { input.copyTo(it) }
        }
        return if (tmp.length() > 0) { tmp.renameTo(out); true } else { tmp.delete(); false }
    }

    private fun notifyProgress() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(lastDone, lastTotal))
    }

    private fun buildNotification(done: Int, total: Int): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, QuranActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 1,
            Intent(this, QuranDownloadService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("📖 Quran — downloading offline library")
            .setContentText("$done / $total surahs done")
            .setProgress(total, done, false)
            .setOngoing(true)
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .build()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Quran Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onDestroy() {
        cancelled = true
        isRunning = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
