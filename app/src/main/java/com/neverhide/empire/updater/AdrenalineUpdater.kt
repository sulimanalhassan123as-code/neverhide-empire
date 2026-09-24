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
 * Adrenaline Bundle Updater v2 — self-updating over the air.
 *
 * The manifest lives with the project source so that every fresh build is
 * instantly available: push a new APK + bump latest.json and every installed
 * copy of the Empire finds the update on its next check.
 *
 * Manifest shape (updater/latest.json):
 * {
 *   "versionCode": 4,
 *   "versionName": "2.0.0",
 *   "apkUrl": "https://raw.githubusercontent.com/.../neverhide-empire-v2.0.0.apk",
 *   "changelog": "What is new"
 * }
 */
class AdrenalineUpdater(private val context: Context) {

    companion object {
        // Primary: GitHub Contents API — always reflects the true HEAD of
        // main with NO CDN caching layer in front of it (verified: raw
        // .githubusercontent.com's Fastly cache ignores query strings
        // entirely, so query-string cache-busting cannot fix staleness
        // there — ~5 min after a push it can still serve the OLD manifest
        // and wrongly tell the user they're up to date). The Contents API
        // has no such cache, so it is now the primary source of truth.
        private const val MANIFEST_URL =
            "https://api.github.com/repos/sulimanalhassan123as-code/neverhide-empire/contents/updater/latest.json?ref=main"
        // Fallback 1: jsdelivr's GitHub CDN — independent cache, usually
        // fresh within seconds of a push (different infra than raw.githubusercontent).
        private const val FALLBACK_MANIFEST_URL =
            "https://cdn.jsdelivr.net/gh/sulimanalhassan123as-code/neverhide-empire@main/updater/latest.json"
        // Fallback 2: the old raw path — eventually consistent (~5 min), last resort.
        private const val FALLBACK2_MANIFEST_URL =
            "https://raw.githubusercontent.com/sulimanalhassan123as-code/neverhide-empire/main/updater/latest.json"
    }

    private val scope = CoroutineScope(Dispatchers.Main)

    fun checkForUpdate(onResult: (String) -> Unit = {}) {
        context.let { com.neverhide.empire.core.EmpireTelemetry.ping(it, "update_check") }
        scope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { fetchManifest(MANIFEST_URL, isGitHubApi = true) }
                compareAndPrompt(manifest, onResult)
            } catch (e: Exception) {
                try {
                    val manifest = withContext(Dispatchers.IO) { fetchManifest(FALLBACK_MANIFEST_URL) }
                    compareAndPrompt(manifest, onResult)
                } catch (e2: Exception) {
                    try {
                        val manifest = withContext(Dispatchers.IO) { fetchManifest(FALLBACK2_MANIFEST_URL) }
                        compareAndPrompt(manifest, onResult)
                    } catch (e3: Exception) {
                        Toast.makeText(context, "Update check failed: ${e3.message}", Toast.LENGTH_LONG).show()
                        onResult("error")
                    }
                }
            }
        }
    }

    private suspend fun compareAndPrompt(manifest: JSONObject, onResult: (String) -> Unit) {
        val current = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val remote = manifest.optLong("versionCode", current)

        if (remote > current) {
            promptInstall(manifest)
            onResult("update")
        } else {
            Toast.makeText(context, "✅ You are up to date (${manifest.optString("versionName", "")})", Toast.LENGTH_SHORT).show()
            onResult("latest")
        }
    }

    private fun fetchManifest(url: String, isGitHubApi: Boolean = false): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache, no-store")
            setRequestProperty("Pragma", "no-cache")
            // The Contents API returns base64-encoded file content by default;
            // this Accept header makes it return the raw file body instead.
            if (isGitHubApi) setRequestProperty("Accept", "application/vnd.github.raw")
        }
        conn.inputStream.bufferedReader().use { return JSONObject(it.readText()) }
    }

    private fun promptInstall(manifest: JSONObject) {
        val name = manifest.optString("versionName", "new")
        val log = manifest.optString("changelog", "")
        val apkUrl = manifest.optString("apkUrl", "") ?: ""

        if (apkUrl.isBlank()) return

        AlertDialog.Builder(context)
            .setTitle("🚀 Update available: v$name")
            .setMessage(log.ifBlank { "A new version of Neverhide Empire is ready." })
            .setPositiveButton("Download & Install") { _, _ -> download(apkUrl) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun download(apkUrl: String, attempt: Int = 1) {
        Toast.makeText(context, if (attempt == 1) "Downloading update…" else "Retrying download…", Toast.LENGTH_SHORT).show()
        scope.launch {
            try {
                var expectedLen = -1L
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "apk").apply { mkdirs() }
                    val out = File(dir, "empire-update.apk")
                    val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15_000; readTimeout = 60_000
                        instanceFollowRedirects = true
                    }
                    expectedLen = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        java.io.FileOutputStream(out).use { input.copyTo(it) }
                    }
                    out
                }

                // INTEGRITY: a flaky mobile connection can silently truncate a
                // big download without throwing — that produces a corrupted
                // file that looks like tampering to the signature check below.
                // Catch it here with a friendly retry instead of a scary
                // security message.
                if (expectedLen > 0 && file.length() != expectedLen) {
                    file.delete()
                    if (attempt < 3) {
                        Toast.makeText(context,
                            "Download was interrupted (weak connection) — retrying…",
                            Toast.LENGTH_SHORT).show()
                        download(apkUrl, attempt + 1)
                    } else {
                        Toast.makeText(context,
                            "Download kept getting interrupted — try again on stronger wifi/data.",
                            Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }

                // SECURITY: verify the update is genuinely ours before install
                val authentic = ApkVerifier.signatureMatchesInstalled(context, file) &&
                        ApkVerifier.packageMatchesInstalled(context, file)
                if (!authentic) {
                    file.delete()
                    if (attempt < 3) {
                        // Could still be a bad download even with matching
                        // Content-Length (rare, but retry before alarming).
                        download(apkUrl, attempt + 1)
                    } else {
                        Toast.makeText(context,
                            "🚫 SECURITY: update signature failed verification — blocked.",
                            Toast.LENGTH_LONG).show()
                    }
                    return@launch
                }
                // fleet metric: a real download completed
                com.neverhide.empire.core.EmpireTelemetry.ping(context, "update_download")
                ApkInstaller.install(context, file)
            } catch (e: Exception) {
                Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
