package com.neverhide.empire.tools.split

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color as AColor
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.neverhide.empire.cleaner.ShizukuShell
import kotlin.concurrent.thread

/**
 * SPLIT BUBBLE — one tap, two apps, anywhere on the phone.
 *
 * WHY (2026-11-02): the in-app launcher only worked while Empire was on screen.
 * The owner wanted split screen as a phone-wide gesture, from inside any app.
 *
 * HOW IT WORKS
 * A small overlay bubble (TYPE_APPLICATION_OVERLAY) floats over every app.
 * One tap runs the pair through the Shizuku shell, which is the ONLY way an
 * ordinary app can force two OTHER apps into multi-window:
 *
 *   am start --windowingMode 3 -n <appA>/<activity>   (split primary)
 *   am start --windowingMode 4 -n <appB>/<activity>   (split secondary)
 *
 * Without Shizuku, Android gives no API to split two foreign apps, so the tap
 * falls back to FLAG_ACTIVITY_LAUNCH_ADJACENT, which only works if the phone
 * is already in split mode. We say so in a toast instead of pretending.
 */
class SplitBubbleService : Service() {

    private var wm: WindowManager? = null
    private var bubble: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        splitNow()
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()

        if (!Settings.canDrawOverlays(this)) { stopSelf(); return }

        createChannel()
        if (Build.VERSION.SDK_INT >= 26) startForeground(NOTIF_ID, buildNotification())

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildBubble()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBubble() {
        val size = (56 * resources.displayMetrics.density).toInt()

        val label = TextView(this).apply {
            text = "⬒"
            textSize = 24f
            setTextColor(AColor.WHITE)
            gravity = Gravity.CENTER
        }
        val root = FrameLayout(this).apply {
            setBackgroundColor(AColor.TRANSPARENT)
            addView(label, FrameLayout.LayoutParams(size, size))
        }
        val circle = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(AColor.parseColor("#00BFA5"))
            setStroke((2 * resources.displayMetrics.density).toInt(), AColor.parseColor("#0A0A1A"))
        }
        root.background = circle

        val p = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        }

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        root.setOnTouchListener { v: View, e: MotionEvent ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = p.x; startY = p.y
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (dx * dx + dy * dy > 100f) moved = true
                    p.x = startX + dx.toInt()
                    p.y = startY + dy.toInt()
                    runCatching { wm?.updateViewLayout(root, p) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) splitNow()   // single tap = split the saved pair
                    v.performClick()
                    true
                }
                else -> false
            }
        }

        bubble = root
        params = p
        runCatching { wm?.addView(root, p) }
    }

    /** Runs the saved pair. Never blocks the UI thread. */
    private fun splitNow() {
        thread {
            val prefs = getSharedPreferences("empire_prefs", MODE_PRIVATE)
            val a = prefs.getString("split_pkg_a", DEFAULT_A) ?: DEFAULT_A
            val b = prefs.getString("split_pkg_b", DEFAULT_B) ?: DEFAULT_B

            val aAct = launchActivityOf(a)
            val bAct = launchActivityOf(b)

            val viaShizuku = if (aAct != null && bAct != null && ShizukuShell.available() && ShizukuShell.permissionGranted()) {
                val r1 = ShizukuShell.run(this, "am start --windowingMode 3 --activity-reorder-to-front -n $aAct")
                Thread.sleep(700)
                val r2 = ShizukuShell.run(this, "am start --windowingMode 4 --activity-reorder-to-front -n $bAct")
                val ok = r1?.first == 0 && r2?.first == 0
                if (!ok) {
                    // Second attempt without the reorder flag, some ROMs reject the combo
                    ShizukuShell.run(this, "am start --windowingMode 3 -n $aAct")
                    Thread.sleep(600)
                    ShizukuShell.run(this, "am start --windowingMode 4 -n $bAct")
                }
                true
            } else false

            main.post {
                if (viaShizuku) {
                    toast("Split on: ${labelOf(a)} + ${labelOf(b)}")
                } else {
                    fallbackAdjacent(a, b)
                }
            }
        }
    }

    private fun fallbackAdjacent(a: String, b: String) {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(b)
        if (intent == null) {
            toast("Set two apps in Split Screen first")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        runCatching {
            startActivity(intent)
            toast("Opened ${labelOf(b)} beside the current app")
        }.onFailure {
            toast("Start Shizuku for one-tap split of any two apps")
        }
    }

    private fun launchActivityOf(pkg: String): String? {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return null
        val c = i.component ?: return null
        return "${c.packageName}/${c.className}"
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun toast(msg: String) {
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        bubble?.let { v -> runCatching { wm?.removeView(v) } }
        bubble = null
        super.onDestroy()
    }

    private fun buildNotification(): android.app.Notification {
        val builder = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(this, CHANNEL_ID)
        else android.app.Notification.Builder(this)
        return builder
            .setContentTitle("⬒ Split bubble active")
            .setContentText("Tap the green bubble anywhere to split your saved pair")
            .setSmallIcon(android.R.drawable.ic_menu_crop)
            .setOngoing(true)
            .build()
    }

    @SuppressLint("ObsoleteSdkInt")
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Split Bubble", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "One-tap split screen anywhere" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    companion object {
        private const val CHANNEL_ID = "empire_split_bubble"
        private const val NOTIF_ID = 6421
        const val ACTION_STOP = "com.neverhide.empire.STOP_SPLIT_BUBBLE"
        const val DEFAULT_A = "com.android.settings"
        const val DEFAULT_B = "com.termux"

        fun start(context: Context) {
            val i = Intent(context, SplitBubbleService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SplitBubbleService::class.java))
        }

        fun isRunning(context: Context): Boolean =
            context.getSharedPreferences("empire_prefs", Context.MODE_PRIVATE)
                .getBoolean("split_bubble_enabled", false)
    }
}
