package com.neverhide.empire.core

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.neverhide.empire.MainActivity

/**
 * Bulletproof persistent background service — the heartbeat of the Empire.
 *
 * Survival mechanisms:
 *  1. Foreground service with persistent notification (immune to Doze killing)
 *  2. Partial WAKE_LOCK — CPU stays alive even when the screen is off
 *  3. AlarmManager exact self-restart every 15 minutes as backup (works in deep sleep)
 *  4. START_STICKY — Android restarts us if the system kills us for memory
 *  5. onTaskRemoved — restart when swiped from recents
 *  6. BootReceiver — restart after reboot / app update
 *  7. Battery guard / SIM guard receivers re-registered on every cycle
 */
class EmpireBackgroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "empire_bg"
        private const val NOTIF_ID = 9001
        private const val RESTART_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes
        private const val HEARTBEAT_INTERVAL_MS = 60 * 1000L    // 1 minute
        const val ACTION_PULSE = "com.neverhide.empire.ACTION_PULSE"

        fun start(context: Context) {
            val i = Intent(context, EmpireBackgroundService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                ContextCompat.startForegroundService(context, i)
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

    /** One-minute heartbeat: re-asserts the wake lock and keeps the service hot. */
    private val heartbeat = object : Runnable {
        override fun run() {
            if (wakeLock.let { !it.isHeld }) {
                @Suppress("WakelockTimeout")
                wakeLock.acquire()
            }
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Wake the CPU back up the moment the screen turns on
            if (!wakeLock.isHeld) {
                @Suppress("WakelockTimeout")
                wakeLock.acquire()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Empire::Watchdog")
        @Suppress("WakelockTimeout")
        wakeLock.acquire() // held for service lifetime, released in onDestroy

        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_SCREEN_ON))

        createChannel()
        startForeground(NOTIF_ID, buildNotification("Empire is guarding your device"))

        // Start the 1-minute heartbeat
        handler.postDelayed(heartbeat, HEARTBEAT_INTERVAL_MS)

        // Schedule the exact self-restart alarm (fires even in deep sleep)
        scheduleRestartAlarm()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PULSE) {
            // External pulse (from tile, boot receiver, etc.) — just re-assert liveness
            if (!wakeLock.isHeld) {
                @Suppress("WakelockTimeout")
                wakeLock.acquire()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped from recents → restart ourselves immediately
        val restart = Intent(applicationContext, EmpireBackgroundService::class.java)
        restart.setPackage(packageName)
        val pintent = PendingIntent.getService(
            this, 9002, restart,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        am.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 1000, pintent)
        scheduleRestartAlarm()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (wakeLock.isHeld) wakeLock.release()
        runCatching { unregisterReceiver(screenReceiver) }
        super.onDestroy()
    }

    private fun scheduleRestartAlarm() {
        val am = getSystemService(ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, RestartReceiver::class.java)
        val pintent = PendingIntent.getBroadcast(
            this, 9003, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = SystemClock.elapsedRealtime() + RESTART_INTERVAL_MS
        // Exact-while-idle punches through Doze; fall back to inexact if not permitted
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pintent)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pintent)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Empire Watchdog",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Keeps Empire tools alive in deep sleep"
                setShowBadge(false)
            }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openHub = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)

        return builder
            .setContentTitle("Neverhide Empire")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openHub)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_MIN)
            .build()
    }
}

/** Receives the periodic restart alarm and re-launches the service. */
class RestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        EmpireBackgroundService.start(context)
    }
}
