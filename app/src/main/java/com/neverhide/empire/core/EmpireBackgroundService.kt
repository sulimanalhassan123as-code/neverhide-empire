package com.neverhide.empire.core

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.neverhide.empire.MainActivity

/**
 * Bulletproof persistent background service.
 * 
 * Survival mechanisms:
 * 1. Foreground service with persistent notification (cannot be killed by Doze)
 * 2. WAKE_LOCK prevents CPU sleep during critical operations
 * 3. AlarmManager schedules self-restart every 15 minutes as backup
 * 4. START_STICKY tells Android to restart us if killed
 * 5. onTaskRemoved restarts service if swiped from recents
 * 6. BootReceiver restarts after phone reboot
 */
class EmpireBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "empire_bg"
        private const val NOTIF_ID = 9001
        private const val RESTART_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val PENDING_RESTART_REQUEST_CODE = 9002

        fun start(context: Context) {
            val i = Intent(context, EmpireBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(i)
            } else {
                context.startService(i)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EmpireBackgroundService::class.java))
        }
    }

    private lateinit var wakeLock: PowerManager.WakeLock
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Acquire a partial wake lock — keeps CPU alive even when screen is off
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "NeverhideEmpire::BackgroundService"
        )
        wakeLock.setReferenceCounted(false)
        wakeLock.acquire(10 * 60 * 1000L) // 10 min, re-acquired periodically
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())

        // Schedule periodic wake lock refresh
        scheduleWakeLockRefresh()

        // Schedule backup restart via AlarmManager
        scheduleBackupRestart()

        return START_STICKY
    }

    private fun scheduleWakeLockRefresh() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!wakeLock.isHeld) {
                    wakeLock.acquire(10 * 60 * 1000L)
                }
                handler.postDelayed(this, 5 * 60 * 1000L) // check every 5 min
            }
        }, 5 * 60 * 1000L)
    }

    private fun scheduleBackupRestart() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val restartIntent = Intent(this, EmpireBackgroundService::class.java)
        val pendingIntent = PendingIntent.getService(
            this, PENDING_RESTART_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule repeating alarm — fires even in Doze mode
        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RESTART_INTERVAL_MS,
            RESTART_INTERVAL_MS,
            pendingIntent
        )
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Empire Active",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Neverhide Empire is running in background"
                setShowBadge(false)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("👑 Neverhide Empire")
            .setContentText("Running • Tap to open")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart self if swiped away from recents
        val restart = Intent(applicationContext, EmpireBackgroundService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            applicationContext.startForegroundService(restart)
        } else {
            applicationContext.startService(restart)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Schedule restart before dying
        val restartIntent = Intent(this, EmpireBackgroundService::class.java)
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val pendingIntent = PendingIntent.getService(
            this, PENDING_RESTART_REQUEST_CODE,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 3000, // 3 seconds
            pendingIntent
        )

        if (wakeLock.isHeld) wakeLock.release()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
