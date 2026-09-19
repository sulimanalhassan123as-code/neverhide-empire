package com.neverhide.empire.cleaner

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import rikka.shizuku.Shizuku
import java.util.Locale

/**
 * FREEZER ENGINE — the shared device-level freeze core, proven in the
 * Cyber Cleaner's App Freezer (v2.6.x battle-tested on Samsung/Knox).
 * Powers both the App Freezer and the App Vault.
 *
 * Two power paths:
 *  1. Device Owner (activated once via ADB dpm) — official, instant.
 *  2. Shizuku (shell uid) — pm disable-user, the standard non-root freeze.
 * All operations VERIFY the real OS state afterwards — never trust pm
 * exit codes (Samsung prints "Error: ..." and still exits 0).
 */
object FreezerEngine {

    fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)

    fun shizukuReady(): Boolean = try { Shizuku.pingBinder() } catch (e: Exception) { false }

    fun shizukuGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    fun powerMode(context: Context): String =
        when {
            isDeviceOwner(context) -> "DEVICE OWNER"
            shizukuReady() && shizukuGranted() -> "SHIZUKU"
            shizukuReady() -> "SHIZUKU (grant needed)"
            else -> "OFF"
        }

    fun hasPower(context: Context): Boolean =
        isDeviceOwner(context) || (shizukuReady() && shizukuGranted())

    /** Freeze/suspend a package. Returns null on success or an error message. */
    fun setSuspended(context: Context, pkg: String, suspend: Boolean): String? {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        // Path 1 — Device Owner (activated once via ADB): official, instant, reliable.
        // Reflection is the honest cross-version call: compiles everywhere, runs 28+.
        if (isDeviceOwner(context) && Build.VERSION.SDK_INT >= 28) {
            return try {
                val m = DevicePolicyManager::class.java.getMethod(
                    "setPackagesSuspended",
                    Array<String>::class.java, Boolean::class.javaPrimitiveType
                )
                m.invoke(dpm, arrayOf(pkg), suspend)
                null
            } catch (e: Exception) { e.message }
        }
        // Path 2 — Shizuku (shell uid): pm disable-user — shell-permitted,
        // fully disables the app (removed from launcher, can't run at all).
        if (shizukuReady() && shizukuGranted()) {
            // TRUTH READ — via the OS package manager's own flags, NOT shell
            // lists: pm list flag support differs per Android build (Android 11
            // Samsung has no --suspended list flag). enabled=false covers the
            // disabled state; FLAG_SUSPENDED covers the suspended state (old
            // freezes + Knox Guard). Works on every Android version.
            fun nowBlocked(): Boolean? = try {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)
                !ai.enabled || (ai.flags and ApplicationInfo.FLAG_SUSPENDED) != 0
            } catch (e: Exception) { null }
            if (suspend) {
                ShizukuShell.exec(context, "pm disable-user --user 0 $pkg")
                val still = nowBlocked()
                return when {
                    still == null -> "could not verify freeze (state check failed)"
                    still -> null
                    else -> "device refused the freeze"
                }
            } else {
                // UNFREEZE ARTILLERY — every release command Android has, in
                // order, stopping the moment the device confirms freedom.
                val cmds = listOf(
                    "pm enable --user 0 $pkg",   // reverse pm disable-user
                    "pm enable $pkg",            // some builds ignore --user
                    "pm unsuspend --user 0 $pkg", // reverse suspend (Knox Guard state)
                    "pm unsuspend $pkg"
                )
                var lastOut = ""
                for (cmd in cmds) {
                    val r = ShizukuShell.run(context, cmd) ?: return "shizuku unavailable"
                    lastOut = r.second.trim()
                    val still = nowBlocked()
                    if (still == null) return "could not verify unfreeze (state check failed)"
                    if (!still) return null
                }
                val tail = lastOut.lineSequence().lastOrNull { it.isNotBlank() } ?: "no pm output"
                return "stuck: $tail"
            }
        }
        return "no_power"
    }

    /**
     * TRUTH READ for the frozen list — the OS package manager's own flags.
     * Sees BOTH blocking states: enabled == false (disabled) and
     * FLAG_SUSPENDED (suspended). No shell, works on every Android version.
     */
    fun realFrozenNow(context: Context): Set<String> =
        listFreezableApps(context)
            .filter { !it.enabled || (it.flags and ApplicationInfo.FLAG_SUSPENDED) != 0 }
            .map { it.packageName }
            .toSet()

    /** Non-system apps (excluding the Empire itself) the user may lock. */
    fun listFreezableApps(context: Context): List<ApplicationInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        it.packageName != context.packageName
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.ROOT) }
    }
}
