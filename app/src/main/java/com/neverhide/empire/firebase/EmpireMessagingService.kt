package com.neverhide.empire.firebase

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * EMPIRE PUSH ENGINE — the Firebase wake-up bell (FCM).
 *
 * This is the piece that lets the Empire reach the phone even when the app is
 * fully closed — the same mechanism WhatsApp/Telegram use for incoming-call
 * ringing. Payload types:
 *   type=call  → Empire Talk incoming call (rings full screen — Phase 2)
 *   type=alert → Guardian / group / system alerts (high-priority notification)
 *   other      → generic notification with title/body from the payload
 *
 * All pushes are DATA messages (silent wake-ups we render ourselves), so we
 * control exactly what the phone shows and nothing leaks through Google
 * display paths.
 */
class EmpireMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // Keep the token on-device. In Empire Talk phase this syncs to the
        // buddy profile so people can ring THIS phone.
        getSharedPreferences("empire_firebase", MODE_PRIVATE)
            .edit().putString("fcm_token", token).apply()
    }

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        when (d["type"]) {
            "call" -> showCallRing(d)       // Empire Talk — full-screen ring (Phase 2 UI)
            "alert" -> showNotification(
                d["title"] ?: "Empire Alert", d["body"] ?: "", high = true
            )
            else -> showNotification(
                d["title"] ?: "Neverhide Empire",
                d["body"] ?: "", high = false
            )
        }
    }

    private fun showCallRing(d: Map<String, String>) {
        // Phase 1: ring as a heads-up notification with the caller handle.
        // Phase 2 upgrades this to a full-screen incoming-call activity.
        showNotification(
            "📞 " + (d["caller"] ?: "Incoming Empire call"),
            "Open the Empire to answer", high = true
        )
    }

    private fun showNotification(title: String, body: String, high: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = "empire_push"
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channel, "Empire Push", NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
        val tap = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify((title + body).hashCode(), NotificationCompat.Builder(this, channel)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(if (high) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build())
    }
}
