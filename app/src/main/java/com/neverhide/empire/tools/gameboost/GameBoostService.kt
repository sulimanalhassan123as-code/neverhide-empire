package com.neverhide.empire.tools.gameboost

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.widget.Toast
import com.neverhide.empire.MainActivity
import com.neverhide.empire.cleaner.FreezerEngine
import com.neverhide.empire.cleaner.ShizukuShell

/**
 * DLS LIVE BOOST (v2.12.0) — replaced the gamepad overlay.
 *
 * Watches which app is in the FOREGROUND (UsageStatsManager — needs the
 * user to grant Usage Access once). The moment DLS comes to the front:
 * freeze every background app so the match gets ALL CPU/RAM/network.
 * The moment the game is left (match over, app closed): unfreeze
 * everything we froze — instantly and without touching apps the USER
 * froze themselves.
 *
 * NO-STUCK SAFEGUARDS (the Freezer taught us hard lessons):
 *  1. The exact list of apps WE froze is written to prefs BEFORE any
 *     freeze — restore only ever releases those, never user-frozen apps.
 *  2. On every start, any leftover freeze-set from a crashed previous
 *     session is released first.
 *  3. "Game left" is confirmed by 2 consecutive polls (~4s) before
 *     unfreezing — no flicker-freeze cycles.
 *  4. Keyboard, launcher, system UI, the game and Empire are never
 *     frozen (frozen keyboard = typing bug).
 */
class GameBoostService : Service() {

    companion object {
        const val ACTION_START = "com.neverhide.empire.gameboost.START"
        const val ACTION_STOP = "com.neverhide.empire.gameboost.STOP"
        private const val CHANNEL_ID = "gameboost"
        private const val NOTIF_ID = 4245
        private const val POLL_MS = 2000L
        private const val LEAVE_CONFIRM_POLLS = 2

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences("gameboost", Context.MODE_PRIVATE)

        fun isRunning(context: Context) = prefs(context).getBoolean("running", false)
        fun isBoosting(context: Context) = prefs(context).getBoolean("boosting", false)
        fun boostedCount(context: Context) =
            prefs(context).getStringSet("we_froze", emptySet())?.size ?: 0

        fun targetGame(context: Context): String =
            prefs(context).getString("game", "com.firsttouchgames.dls3") ?: "com.firsttouchgames.dls3"

        /** Does this package look like DLS (auto-detect family variants)? */
        private fun looksLikeDls(pkg: String): Boolean =
            pkg.contains("firsttouchgames") ||
                    (pkg.contains("dls") && !pkg.contains("system"))
    }

    private var pollThread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startAsForeground("Watching for the game…")
                // safeguard 2: release leftovers from any crashed session
                Thread { releaseAllWeFroze(true) }.start()
                startPolling()
            }
            ACTION_STOP -> {
                Thread { releaseAllWeFroze(false) }.start()
                stopPolling()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopPolling()
        // last-ditch: try to release synchronously before the process ends
        runCatching { releaseAllWeFroze(false) }
        super.onDestroy()
    }

    // ===================== foreground detection =====================

    private fun currentForeground(): String? {
        return try {
            val usm = getSystemService(UsageStatsManager::class.java) ?: return null
            val now = System.currentTimeMillis()
            val events = usm.queryEvents(now - 10_000, now)
            val e = UsageEvents.Event()
            var fg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND ||
                    e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
                ) fg = e.packageName
            }
            fg
        } catch (ex: Exception) { null }
    }

    private fun gameInForeground(): Boolean {
        val fg = currentForeground() ?: return false
        val target = targetGame(this)
        return fg == target || looksLikeDls(fg)
    }

    // ===================== poll loop =====================

    @SuppressLint("VisibleForTests")
    private fun startPolling() {
        if (pollThread?.isAlive == true) return
        pollThread = Thread {
            var leaveStreak = 0
            while (!Thread.currentThread().isInterrupted) {
                try { Thread.sleep(POLL_MS) } catch (e: InterruptedException) { break }
                if (!FreezerEngine.hasPower(this)) {
                    // no Shizuku -> can't freeze/unfreeze: fail SAFE (restore + stop)
                    releaseAllWeFroze(false)
                    mainToast("Live Boost: Shizuku lost — everything restored.")
                    stopSelfClean()
                    break
                }
                val boosting = prefs(this).getBoolean("boosting", false)
                if (gameInForeground()) {
                    leaveStreak = 0
                    if (!boosting) {
                        startBoost()
                    } else {
                        updateNotif("LIVE — ${prefs(this).getStringSet("we_froze", emptySet())?.size ?: 0} background apps frozen for the match")
                    }
                } else if (boosting) {
                    leaveStreak++
                    if (leaveStreak >= LEAVE_CONFIRM_POLLS) {
                        leaveStreak = 0
                        // game left -> restore immediately
                        val n = releaseAllWeFroze(false)
                        updateNotif("Game ended — $n apps restored. Watching for the next match…")
                        mainToast("Match over — all apps back to normal ✅")
                    }
                } else {
                    updateNotif("Watching for the game…")
                }
            }
        }.apply { start() }
    }

    private fun stopPolling() {
        pollThread?.interrupt()
        pollThread = null
    }

    // ===================== freeze / restore =====================

    private fun neverFreeze(): MutableSet<String> {
        val keep = mutableSetOf(
            packageName, targetGame(this),
            "com.android.systemui", "com.sec.android.app.launcher",
            "com.android.launcher3", "com.google.android.apps.nexuslauncher"
        )
        runCatching {
            val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ime?.substringBefore("/")?.let { keep.add(it) }
        }
        return keep
    }

    private fun startBoost() {
        if (!FreezerEngine.hasPower(this)) return
        val keep = neverFreeze()
        // one cheap truth-read: apps the user froze themselves stay untouched
        val alreadyFrozen = FreezerEngine.realFrozenNow(this)
        val targets = FreezerEngine.listFreezableApps(this)
            .map { it.packageName }
            .filter { !keep.contains(it) && !alreadyFrozen.contains(it) }
        // safeguard 1: record EXACTLY what we freeze, before freezing
        prefs(this).edit()
            .putStringSet("we_froze", targets.toSet())
            .putBoolean("boosting", true)
            .apply()
        targets.forEach { pkg -> FreezerEngine.setSuspended(this, pkg, true) }
        val saved = applyNetworkBoost()
        updateNotif("LIVE — ${targets.size} apps frozen${if (saved) " + Data Saver ON (WhatsApp whitelisted)" else ""}")
        mainToast("📡 LIVE: ${targets.size} apps frozen${if (saved) " + Data Saver ON — WhatsApp stays connected" else ""} — full power to the match")
    }

    /** Releases exactly what WE froze. Returns how many. */
    private fun releaseAllWeFroze(silent: Boolean): Int {
        val p = prefs(this)
        val weFroze = p.getStringSet("we_froze", emptySet())?.toSet() ?: emptySet()
        weFroze.forEach { pkg -> FreezerEngine.setSuspended(this, pkg, false) }
        runCatching { removeNetworkBoost() }
        p.edit()
            .putStringSet("we_froze", emptySet<String>())
            .putBoolean("boosting", false)
            .apply()
        if (!silent && weFroze.isNotEmpty()) mainToast("Restored ${weFroze.size} apps ✅")
        return weFroze.size
    }

    // ===================== network boost (v2.12.1) =====================

    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null
    private val whiteApps: List<String>
        get() = listOf("com.whatsapp", "com.whatsapp.w4b", targetGame(this), packageName)

    private fun netpolicy(cmd: String): String? =
        runCatching { ShizukuShell.run(this, cmd)?.second?.trim() }.getOrNull()

    /**
     * NETWORK HOLD — the strongest legal move without root:
     *  1. Wi-Fi low-latency lock (lower ping while on Wi-Fi)
     *  2. Data Saver ON — Android itself restricts ALL background data
     *  3. WhatsApp (and the game) whitelisted, so messages keep trickling
     * Returns true if the shell legs ran (Shizuku up).
     */
    private fun applyNetworkBoost(): Boolean {
        // 1. Wi-Fi lock — pure app API, no shell needed
        runCatching {
            val wm = getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            if (wm.isWifiEnabled) {
                val mode = if (android.os.Build.VERSION.SDK_INT >= 29)
                    4 /* WIFI_MODE_FULL_LOW_LATENCY */ else 3 /* HIGH_PERF */
                wifiLock = wm.createWifiLock(mode, "empire_gameboost").also { it.acquire() }
            }
        }
        // 2. + 3. Data Saver + whitelist via Shizuku shell
        val p = prefs(this)
        val prev = netpolicy("cmd netpolicy get restrict-background")
        if (prev != null) {
            val wasOn = prev.contains("true") || prev.contains("enabled")
            if (!p.contains("ds_prev")) {
                p.edit().putString("ds_prev", wasOn.toString()).putBoolean("ds_applied", true).apply()
            }
            netpolicy("cmd netpolicy set restrict-background true")
            val pm = packageManager
            for (pkg in whiteApps) {
                runCatching {
                    val uid = pm.getApplicationInfo(pkg, 0).uid
                    netpolicy("cmd netpolicy add restrict-background-whitelist $uid")
                }
            }
            return true
        }
        return false
    }

    /** Restores Data Saver to whatever it was BEFORE the match; releases the Wi-Fi lock. */
    private fun removeNetworkBoost() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        val p = prefs(this)
        if (!p.getBoolean("ds_applied", false)) return
        val wasOn = p.getString("ds_prev", "false") == "true"
        netpolicy("cmd netpolicy set restrict-background ${if (wasOn) "true" else "false"}")
        // only remove our whitelist entries if Data Saver is now OFF
        // (if the user had Data Saver on before, leave whitelists untouched)
        if (!wasOn) {
            val pm = packageManager
            for (pkg in whiteApps) {
                runCatching {
                    val uid = pm.getApplicationInfo(pkg, 0).uid
                    netpolicy("cmd netpolicy remove restrict-background-whitelist $uid")
                }
            }
        }
        p.edit().remove("ds_prev").remove("ds_applied").apply()
    }

    // ===================== plumbing =====================

    private fun mainToast(msg: String) {
        android.os.Handler(mainLooper).post {
            runCatching { Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    private fun updateNotif(text: String) {
        runCatching {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIF_ID, buildNotif(text))
        }
    }

    private fun stopSelfClean() {
        stopPolling()
        prefs(this).edit().putBoolean("running", false).apply()
        stopSelf()
    }

    private fun startAsForeground(text: String) {
        createChannel()
        prefs(this).edit().putBoolean("running", true).apply()
        startForeground(NOTIF_ID, buildNotif(text))
    }

    private fun buildNotif(text: String): Notification {
        val stopPI = PendingIntent.getService(
            this, 1,
            Intent(this, GameBoostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openPI = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26)
            Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b
            .setContentTitle("📡 DLS Live Boost")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openPI)
            .addAction(0, "Stop & restore", stopPI)
            .setOngoing(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "DLS Live Boost", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }
}
