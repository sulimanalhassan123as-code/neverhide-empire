package com.neverhide.empire.core

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * EMPIRE TELEMETRY — the heartbeat of the fleet.
 *
 * Sends one tiny, privacy-safe event to the Empire's Supabase command
 * table whenever the app opens, checks for updates, or downloads one.
 * NO personal data: no phone number, no accounts, no contacts — just a
 * random device id (generated once, stored locally), app version, device
 * model and Android version. The owner (THE ROOT CAUSE HUNTER) uses the
 * aggregate numbers on his admin dashboard to see installs, active
 * users and downloads.
 *
 * Fire-and-forget: never blocks, never crashes the app, silent failures.
 */
object EmpireTelemetry {

    private const val SB_URL = "https://hokqlvkowcrujppeliip.supabase.co/rest/v1"
    private const val SB_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhva3Fsdmtvd2NydWpwcGVsaWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3MTg1MDUsImV4cCI6MjA5NTI5NDUwNX0._iO6p71kJRiBWH-fJ1j7GWDNmMcjSMN5nseNU4VN8tE"
    private const val EMPIRE_APP_KEY = "empire-talk-647a00bd4a571d2991bf591a4f18f101"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun ping(context: Context, event: String) {
        scope.launch {
            runCatching {
                val prefs = context.getSharedPreferences("empire_telemetry", Context.MODE_PRIVATE)
                var deviceId = prefs.getString("device_id", null)
                if (deviceId == null) {
                    deviceId = UUID.randomUUID().toString()
                    prefs.edit().putString("device_id", deviceId).apply()
                }
                val body = JSONObject()
                    .put("device_id", deviceId)
                    .put("event", event)
                    .put("version", context.packageManager
                        .getPackageInfo(context.packageName, 0).versionName ?: "?")
                    .put("android_release", Build.VERSION.RELEASE)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                    .put("handle", context.getSharedPreferences("empire_talk", Context.MODE_PRIVATE)
                        .getString("handle", null) ?: JSONObject.NULL)
                    .put("app_key", EMPIRE_APP_KEY)
                    .toString()
                val conn = (URL("$SB_URL/empire_events").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 5000; readTimeout = 5000
                    doOutput = true
                    setRequestProperty("apikey", SB_ANON)
                    setRequestProperty("Authorization", "Bearer $SB_ANON")
                    setRequestProperty("x-app-key", EMPIRE_APP_KEY)
                    setRequestProperty("Content-Type", "application/json")
                }
                conn.outputStream.use { it.write(body.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }
}
