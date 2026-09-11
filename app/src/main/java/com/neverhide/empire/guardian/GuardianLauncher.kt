package com.neverhide.empire.guardian

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * Helper that launches the jumpscare over the lock screen using a
 * full-screen-intent notification — the only sanctioned way to surface an
 * activity from the background on Android 10+ (USE_FULL_SCREEN_INTENT).
 */
object GuardianLauncher {

    private const val CHANNEL_ID = "guardian_jumpscare"
    private const val NOTIF_ID = 777

    fun launch(context: Context, theme: Int, photoPath: String?) {
        createChannel(context)

        val intent = Intent(context, JumpscareActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra("theme", theme)
            putExtra("photo", photoPath)
        }
        val fullScreen = PendingIntent.getActivity(
            context, NOTIF_ID, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= 26)
            android.app.Notification.Builder(context, CHANNEL_ID)
        else android.app.Notification.Builder(context)

        val notif = builder
            .setContentTitle("⚠️ INTRUDER ALERT")
            .setContentText("Wrong password detected — evidence captured")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(android.app.Notification.PRIORITY_MAX)
            .setCategory(android.app.Notification.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreen, true)
            .setAutoCancel(true)
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, notif)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(
                CHANNEL_ID, "Guardian Alerts", NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Wrong-password jumpscare alerts"
                enableVibration(true)
            }
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }
}
