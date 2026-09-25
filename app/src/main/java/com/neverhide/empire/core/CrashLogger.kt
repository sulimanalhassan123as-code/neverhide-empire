package com.neverhide.empire.core

import android.app.Application
import android.os.Build
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * EMPIRE CRASH LOGGER
 *
 * Decent has no PC and no ADB access to his phone, so a crash normally
 * means the trace is lost the moment the app closes. This catches every
 * uncaught crash, writes it to disk AND phones it home to the same
 * Supabase project Empire Talk uses — so the exact stack trace, device,
 * and Android version are readable remotely within seconds, no cable
 * required. Then it lets the system finish the crash as normal (no
 * masking of real failures).
 */
object CrashLogger {

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { handleCrash(app, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun handleCrash(app: Application, thread: Thread, throwable: Throwable) {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val trace = throwable.stackTraceToString()
        val versionName = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0).versionName
        }.getOrDefault("?")

        val report = buildString {
            appendLine("time: $stamp")
            appendLine("app_version: $versionName")
            appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread: ${thread.name}")
            appendLine("---")
            appendLine(trace)
        }

        // 1) local file — survives even if the network call below fails
        runCatching {
            val dir = File(app.filesDir, "crash_logs").apply { mkdirs() }
            File(dir, "crash_${System.currentTimeMillis()}.txt").writeText(report)
        }

        // 2) phone it home — best-effort, short timeout so it never blocks
        //    the crash from finishing. Same Supabase project + guard secret
        //    as Empire Talk.
        runCatching {
            val body = org.json.JSONObject()
                .put("app_key", "empire-talk-647a00bd4a571d2991bf591a4f18f101")
                .put("version", versionName)
                .put("android_release", Build.VERSION.RELEASE)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                .put("report", report.take(8000))
                .toString()
            val conn = (URL("https://hokqlvkowcrujppeliip.supabase.co/rest/v1/crash_reports")
                .openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000; readTimeout = 4000
                doOutput = true
                setRequestProperty("apikey", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhva3Fsdmtvd2NydWpwcGVsaWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3MTg1MDUsImV4cCI6MjA5NTI5NDUwNX0._iO6p71kJRiBWH-fJ1j7GWDNmMcjSMN5nseNU4VN8tE")
                setRequestProperty("Authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhva3Fsdmtvd2NydWpwcGVsaWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3MTg1MDUsImV4cCI6MjA5NTI5NDUwNX0._iO6p71kJRiBWH-fJ1j7GWDNmMcjSMN5nseNU4VN8tE")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-app-key", "empire-talk-647a00bd4a571d2991bf591a4f18f101")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }
            conn.responseCode
            conn.disconnect()
        }
    }
}
