package com.neverhide.empire.updater

import android.app.AlertDialog
import android.content.Context
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Adrenaline Bundle Updater".
 *
 * 1. Fetches a version manifest JSON from your server.
 *    Expected shape:
 *    {
 *      "versionCode": 2,
 *      "versionName": "1.1.0",
 *      "apkUrl": "https://your.server/neverhide-empire-1.1.0.apk",
 *      "changelog": "What is new"
 *    }
 * 2. Compares versionCode against the installed one.
 * 3. If newer, downloads the APK to cache and launches the install prompt.
 *
 * Change [MANIFEST_URL] to point at your own server.
 */
class AdrenalineUpdater(private val context: Context) {

    companion object {
        // TODO: replace with your own hosted manifest URL.
        private const val MANIFEST_URL = "https://neverhide.example.com/empire/latest.json"
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    fun checkForUpdate() {
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { fetchManifest() }
                val current = context.packageManager
                    .getPackageInfo(context.packageName, 0).longVersionCode
                val remote = manifest.getInt("versionCode").toLong()

                if (remote > current) {
                    promptInstall(manifest)
                } else {
                    Toast.makeText(context, "You are up to date", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Update check failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun fetchManifest(): JSONObject {
        val conn = (URL(MANIFEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
        }
        conn.inputStream.bufferedReader().use { return JSONObject(it.readText()) }
    }

    private fun promptInstall(manifest: JSONObject) {
        val name = manifest.optString("versionName", "new")
        val log = manifest.optString("changelog", "")
        val apkUrl = manifest.getString("apkUrl")

        AlertDialog.Builder(context)
            .setTitle("Update available: $name")
            .setMessage(log.ifBlank { "A new version is ready to install." })
            .setPositiveButton("Download & Install") { _, _ -> download(apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(apkUrl: String) {
        Toast.makeText(context, "Downloading update...", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    val out = File(context.cacheDir, "apk").apply { mkdirs() }
                        .let { File(it, "empire-update.apk") }
                    val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 30_000
                    }
                    conn.inputStream.use { input ->
                        out.outputStream.use { input.copyTo(it) }
                    }
                    out
                }
                ApkInstaller.install(context, file)
            } catch (e: Exception) {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val File.outputStream get() = java.io.FileOutputStream(this)
}
