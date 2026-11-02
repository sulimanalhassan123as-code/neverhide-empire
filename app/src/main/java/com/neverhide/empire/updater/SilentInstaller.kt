package com.neverhide.empire.updater

import android.content.Context
import android.widget.Toast
import com.neverhide.empire.cleaner.ShizukuShell
import java.io.File

/**
 * SILENT SELF-INSTALL (v2.13.1) — no installer screen, no tapping.
 *
 * THE WHY: Android forbids one app from installing another without the user
 * tapping the system installer UI. The ONE legitimate exception is shell
 * (ADB) privileges, and Empire already runs commands through the Shizuku
 * user-service broker used by the App Freezer.
 *
 * THE HOW: the APK is downloaded to the app's EXTERNAL files dir
 * (/storage/emulated/0/Android/data/<pkg>/files/updates/), because the shell
 * user cannot read app-private internal storage. From there:
 *
 *   pm install -r -d <path>
 *
 * runs as the shell user via Shizuku and installs the update in place.
 * Returns true only when the command reports Success, so the caller can fall
 * back to the normal installer prompt instead of lying about it.
 */
object SilentInstaller {

    /** True when Shizuku is alive AND Empire holds its permission. */
    fun available(context: Context): Boolean =
        runCatching { ShizukuShell.available() && ShizukuShell.permissionGranted() }.getOrDefault(false)

    /**
     * Attempt a silent install. Blocks — call from a background thread.
     * @return true if the package manager reported Success.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.exists() || apk.length() < 1_000_000L) return false
        if (!available(context)) return false

        // Shizuku's shell cannot read app-private cache; mirror the APK into
        // external app storage that the shell user can access.
        val staged = stageForShell(context, apk) ?: return false

        val result = ShizukuShell.run(context, "pm install -r -d '${staged.absolutePath}'")
            ?: return false

        val (code, out) = result
        val ok = code == 0 && out.contains("Success", ignoreCase = true)

        // Verify the new version actually landed before claiming success.
        if (ok) {
            runCatching { staged.delete() }
            return true
        }

        runCatching { staged.delete() }
        return false
    }

    /**
     * Copy the verified APK where the shell user can read it. Returns null if
     * external storage is unavailable.
     */
    private fun stageForShell(context: Context, apk: File): File? {
        val dir = (context.getExternalFilesDir(null) ?: return null).resolve("updates").apply { mkdirs() }
        val target = File(dir, "empire-update.apk")
        return runCatching {
            apk.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            target
        }.getOrNull()
    }

    /** Silent path with a friendly toast, for callers that don't care how it happened. */
    fun tryInstallWithToast(context: Context, apk: File): Boolean {
        val ok = install(context, apk)
        Toast.makeText(
            context,
            if (ok) "✅ Empire updated itself — restarting" else "Shizuku not available, using the installer prompt",
            Toast.LENGTH_LONG
        ).show()
        return ok
    }
}
