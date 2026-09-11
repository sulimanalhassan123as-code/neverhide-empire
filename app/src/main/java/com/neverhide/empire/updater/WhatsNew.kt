package com.neverhide.empire.updater

import android.app.AlertDialog
import android.content.Context
import android.os.AsyncTask
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * MINOR UPDATE CHANNEL — no APK reinstall ever needed.
 *
 * Reads updater/whatsnew.json straight from the GitHub repo. Anything
 * published there (news, tips, announcements, demo features) appears
 * in-app immediately. MAJOR updates (new APK) stay on the
 * AdrenalineUpdater path — that's the two-tier system:
 *
 *   major  -> versionCode bump  -> APK download + install
 *   minor  -> whatsnew.json     -> content refresh, zero reinstall
 */
object WhatsNew {

    private const val FEED_URL =
        "https://raw.githubusercontent.com/sulimanalhassan123as-code/neverhide-empire/main/updater/whatsnew.json"

    fun check(context: Context) {
        AlertDialog.Builder(context)
            .setTitle("🌙 What's New")
            .setMessage("Fetching the latest news…")
            .setPositiveButton("Close", null)
            .show()
        AsyncTask.execute {
            val body = runCatching { fetch() }.getOrNull()
            android.os.Handler(context.mainLooper).post {
                show(context, body)
            }
        }
    }

    private fun fetch(): String {
        val conn = (URL(FEED_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    private fun show(context: Context, body: String?) {
        val text = if (body.isNullOrBlank()) {
            "⚠️ Could not reach the update feed. Check your connection."
        } else {
            runCatching {
                val json = JSONObject(body)
                val sb = StringBuilder()
                val items = json.optJSONArray("items") ?: return@runCatching "No updates."
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    sb.append("• ${item.optString("title")}\n")
                        .append("  ${item.optString("note")}\n\n")
                }
                "Feed updated: ${json.optString("updatedAt", "?")}\n\n$sb"
            }.getOrElse { body }
        }
        AlertDialog.Builder(context)
            .setTitle("🌙 What's New (minor updates — no reinstall)")
            .setMessage(text)
            .setPositiveButton("Close", null)
            .show()
    }
}
