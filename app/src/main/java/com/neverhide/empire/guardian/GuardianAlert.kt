package com.neverhide.empire.guardian

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.telephony.SmsManager
import android.util.Base64
import java.io.File
import java.io.OutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * GUARDIAN ALERT — when a wrong password is detected:
 *
 *  1. 📍 Grabs the phone's location (best-effort: last-known fix first,
 *     then a short 5s network-fix attempt)
 *  2. 📱 Sends an SMS to the configured alert number — works OFFLINE
 *     via the carrier, with a live Google Maps link
 *  3. 💬 When the phone is ONLINE, posts the intruder selfie + caption
 *     to the WhatsApp relay bridge, which DMs it to the same number
 *
 * The WhatsApp leg rides the bridge service (Baileys session) so the
 * photo arrives fully automatically — no tapping, no WhatsApp Business
 * API. SMS is the offline safety net; WhatsApp is the online upgrade.
 */
object GuardianAlert {

    private const val BRIDGE_URL =
        "https://whatsapp-bridge-6bdj.onrender.com/guardian/alert"
    private const val BRIDGE_SECRET = "1d15d106f20d08fd901d974b7812e2285f183e5a6ad8aaba"

    fun fire(context: Context, photoPath: String?) {
        val prefs = context.getSharedPreferences(
            GuardianAdminReceiver.GUARDIAN_PREFS, Context.MODE_PRIVATE
        )
        val number = prefs.getString(GuardianAdminReceiver.KEY_ALERT_NUMBER, "")
            ?.replace(Regex("[^0-9+]"), "") ?: ""
        if (number.isBlank()) return

        Thread {
            // 1. Location (best-effort, ~5s max)
            val loc = getLocation(context)

            @Suppress("SpellCheckingInspection")
            val maps = loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" }
            val time = SimpleDateFormat("dd MMM yyyy, HH:mm:ss", Locale.US).format(Date())
            val caption = buildString {
                append("🚨 NEVERHIDE GUARDIAN ALERT\n")
                append("Someone entered a WRONG PASSWORD on your phone!\n\n")
                append("🕒 Time: $time\n")
                append("📍 Location: ${maps ?: "unavailable — no GPS/network fix (try again outdoors or with mobile data on)"}\n")
                if (loc != null) {
                    append("   accuracy: ~${loc.accuracy.toInt()}m\n")
                }
                if (photoPath != null) append("📷 Intruder selfie attached below.")
                else append("📷 Selfie not captured (camera was blocked).")
            }

            // 2. SMS — offline safety net, includes maps link
            sendSms(context, number, if (maps != null)
                "$caption\n\n🗺️ $maps" else caption)

            // 3. WhatsApp — online leg with selfie photo + caption
            sendWhatsApp(number, caption, photoPath)
        }.start()
    }

    @SuppressLint("MissingPermission")
    private fun getLocation(context: Context): Location? {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            // Last-known fix: instant, zero battery
            val last = listOf(
                LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            ).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }

            if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) {
                return last // fresh enough
            }
            // Try BOTH GPS and network providers in parallel — whichever
            // answers first wins. The old code only tried NETWORK_PROVIDER,
            // which is useless indoors with weak cell signal; GPS_PROVIDER
            // often resolves faster outdoors. This now runs inside a real
            // foreground service (GuardianCaptureService), so the OS won't
            // throttle it the way it throttled the old background-thread call.
            var fix: Location? = null
            val done = java.util.concurrent.CountDownLatch(1)
            val listener = object : LocationListener {
                override fun onLocationChanged(l: Location) {
                    fix = l
                    done.countDown()
                }
                override fun onProviderDisabled(p: String) { done.countDown() }
                override fun onProviderEnabled(p: String) {}
            }
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .filter { lm.allProviders.contains(it) }
            if (providers.isNotEmpty()) {
                providers.forEach { p ->
                    runCatching {
                        lm.requestLocationUpdates(p, 0L, 0f, listener, Looper.getMainLooper())
                    }
                }
                done.await(8, java.util.concurrent.TimeUnit.SECONDS)
                runCatching { lm.removeUpdates(listener) }
            }
            fix ?: last
        }.getOrNull()
    }

    private fun sendSms(context: Context, number: String, message: String) {
        runCatching {
            if (context.checkSelfPermission(Manifest.permission.SEND_SMS)
                != PackageManager.PERMISSION_GRANTED) return
            val sms = SmsManager.getDefault()
            val parts = sms.divideMessage(message)
            if (parts.size == 1) sms.sendTextMessage(number, null, message, null, null)
            else sms.sendMultipartTextMessage(number, null, parts, null, null)
        }
    }

    private fun sendWhatsApp(number: String, caption: String, photoPath: String?) {
        runCatching {
            val photo: String? = if (photoPath != null && File(photoPath).exists()) {
                Base64.encodeToString(File(photoPath).readBytes(), Base64.NO_WRAP)
            } else null

            val json = buildString {
                append("{\"number\":\"").append(number).append('"')
                append(',').append("\"caption\":")
                append(jsonQuote(caption))
                if (photo != null) append(',').append("\"photoBase64\":\"").append(photo).append('"')
                append('}')
            }

            val conn = URL(BRIDGE_URL).openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("x-bridge-secret", BRIDGE_SECRET)
            val os: OutputStream = conn.outputStream
            os.write(json.toByteArray(Charsets.UTF_8))
            os.close()
            conn.responseCode  // fire and forget
            conn.disconnect()
        }
    }

    private fun jsonQuote(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n") + "\""
}
