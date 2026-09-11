package com.neverhide.empire.tools.scanner

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * SECURITY SECTION — the always-on patrol.
 *
 * Runs the deep security audit automatically every 3 hours (and after
 * reboot). If it finds critical threats, it posts a notification so
 * you know even when the app is closed. Uses JobScheduler so the OS
 * batches it efficiently with other background work — no battery drain.
 */
class SecurityPatrolJob : JobService() {

    companion object {
        const val CHANNEL = "security_patrol"
        const val NOTIFICATION_ID = 4310
        private const val JOB_ID = 4310

        fun schedule(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            val job = JobInfo.Builder(
                JOB_ID,
                ComponentName(context, SecurityPatrolJob::class.java)
            )
                .setPeriodic(3 * 60 * 60 * 1000L)  // every 3 hours
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE) // fully offline
                .setPersisted(true) // survives reboot
                .build()
            js.schedule(job)
        }

        fun cancel(context: Context) {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            js.cancel(JOB_ID)
        }

        fun isScheduled(context: Context): Boolean {
            val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            return js.allPendingJobs.any { it.id == JOB_ID }
        }
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        val context = applicationContext
        Thread {
            val findings = SecurityScanner.audit(context)
            val critical = findings.filter { it.severity >= 2 }
            if (critical.isNotEmpty()) notifyUser(context, critical)
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    private fun notifyUser(context: Context, critical: List<SecurityScanner.Finding>) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL, "Security Patrol", NotificationManager.IMPORTANCE_HIGH
            )
        )
        val pi = PendingIntent.getActivity(
            context, 0, Intent("com.neverhide.empire.OPEN_SECURITY").setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("🚨 Empire Security Patrol: ${critical.size} suspicious finding(s)")
                .setContentText(critical.first().let { "${it.icon} ${it.app}: ${it.reason.take(60)}…" })
                .setStyle(
                    Notification.BigTextStyle()
                        .bigText(critical.take(5).joinToString("\n") { "${it.icon} ${it.app} — ${it.reason}" })
                )
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
        )
    }
}

/**
 * Opens the Security Patrol notification into the app scanner.
 */
class SecurityPatrolOpenReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Toast-less silent handoff: launching the main activity is enough;
        // the dashboard highlights the scanner.
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch != null) context.startActivity(launch)
    }
}
