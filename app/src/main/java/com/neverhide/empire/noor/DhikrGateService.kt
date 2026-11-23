package com.neverhide.empire.noor

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * NOOR GATE — the dhikr you must complete before the phone is yours.
 *
 * V2.13.8 (2026-11-23): the owner asked for a very aggressive gate: after every
 * unlock, Astaghfirullah three times before anything else works.
 *
 * HOW IT BLOCKS WITHOUT LOCKING HIM OUT
 * A full-screen TYPE_APPLICATION_OVERLAY that IS touchable, so every tap lands on
 * the gate instead of the app underneath. System bars still work (they always draw
 * above app overlays), but tapping a notification opens the app *behind* the gate,
 * so nothing is usable until the three taps land.
 *
 * SAFETY, learned the hard way from the Eye Care freeze:
 *  - Watchdog: the gate removes itself after 90 seconds no matter what.
 *  - Hold anywhere for 4 seconds to escape immediately.
 *  - The notification carries a Turn off gate action.
 *  - Incoming calls still ring and can be answered from the notification.
 */
class DhikrGateService : Service() {

    companion object {
        const val CHANNEL_ID = "noor_gate"
        const val NOTIF_ID = 9007
        const val ACTION_STOP = "com.neverhide.empire.STOP_NOOR_GATE"
        const val ACTION_TEST = "com.neverhide.empire.TEST_NOOR_GATE"
        private const val POLL_MS = 400L
        private const val WATCHDOG_MS = 90_000L

        fun start(context: Context) {
            val i = Intent(context, DhikrGateService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DhikrGateService::class.java))
        }

        fun isRunning(context: Context) =
            context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
                .getBoolean("noor_gate_enabled", false)

        fun todayCount(context: Context): Int {
            val p = context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
            val day = p.getLong("noor_gate_day", 0L)
            val today = System.currentTimeMillis() / 86_400_000L
            return if (day == today) p.getInt("noor_gate_count", 0) else 0
        }

        private fun bumpToday(context: Context) {
            val p = context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
            val today = System.currentTimeMillis() / 86_400_000L
            val day = p.getLong("noor_gate_day", 0L)
            val count = if (day == today) p.getInt("noor_gate_count", 0) else 0
            p.edit().putLong("noor_gate_day", today).putInt("noor_gate_count", count + 1).apply()
        }
    }

    private var wm: WindowManager? = null
    private var gate: FrameLayout? = null
    private var gateView: NoorGateView? = null
    private var watchdog: Runnable? = null
    private var wasLocked = true
    private var testMode = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                getSharedPreferences("empire_prefs", MODE_PRIVATE).edit()
                    .putBoolean("noor_gate_enabled", false).apply()
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TEST -> {
                testMode = true
                showGate()
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        createChannel()
        if (Build.VERSION.SDK_INT >= 26) startForeground(NOTIF_ID, buildNotification())
        getSharedPreferences("empire_prefs", MODE_PRIVATE).edit()
            .putBoolean("noor_gate_enabled", true).apply()
        pollUnlock()
    }

    /** Watches for the keyguard dropping, which is the moment the phone opens. */
    private fun pollUnlock() {
        val km = getSystemService(KEYGUARD_SERVICE) as KeyguardManager
        val locked = runCatching { km.isKeyguardLocked }.getOrDefault(false)

        if (wasLocked && !locked && gate == null && !testMode) {
            showGate()
        }
        wasLocked = locked

        main.postDelayed({ pollUnlock() }, POLL_MS)
    }

    private fun showGate() {
        if (gate != null) return
        val wmLocal = wm ?: return

        val view = NoorGateView(this) { dismissGate(completed = true) }
        val root = FrameLayout(this).apply {
            setBackgroundColor(0xFF050B0A.toInt())
            addView(view, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable on purpose: every tap belongs to the gate.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            // Fade in from the launcher so it feels intentional, not like a crash.
            windowAnimations = android.R.style.Animation_Translucent
        }

        runCatching { wmLocal.addView(root, params) }
            .onSuccess {
                gate = root
                gateView = view
                startWatchdog()
            }
    }

    private fun startWatchdog() {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = Runnable {
            if (gate != null) dismissGate(completed = false)
        }.also { main.postDelayed(it, WATCHDOG_MS) }
    }

    private fun dismissGate(completed: Boolean) {
        watchdog?.let { main.removeCallbacks(it) }
        watchdog = null
        gateView?.release()
        gate?.let { v -> runCatching { wm?.removeView(v) } }
        gate = null
        gateView = null
        if (completed) bumpToday(this)
        if (testMode) {
            testMode = false
            stopSelf()
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "NOOR Gate", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Astaghfirullah gate after unlock" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): android.app.Notification {
        val count = todayCount(this)
        val builder = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, CHANNEL_ID)
        else android.app.Notification.Builder(this)

        return builder
            .setContentTitle("NOOR gate armed")
            .setContentText("Three taps after every unlock • $count today")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                0, "Turn off gate",
                PendingIntent.getService(
                    this, 11,
                    Intent(this, DhikrGateService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
            .build()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        gateView?.release()
        gate?.let { v -> runCatching { wm?.removeView(v) } }
        gate = null
        gateView = null
        getSharedPreferences("empire_prefs", MODE_PRIVATE).edit()
            .putBoolean("noor_gate_enabled", false).apply()
        super.onDestroy()
    }
}
