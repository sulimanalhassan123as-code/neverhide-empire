package com.neverhide.empire.tools.scanner

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.neverhide.empire.guardian.GuardianAdminReceiver

/**
 * SECURITY SECTION — deep background security audit.
 *
 * Runs a real, honest security scan of the device:
 *  1. Dangerous permission combos per app (spyware pattern)
 *  2. Sideloaded apps (installed from unknown sources — the classic
 *     vector for trojans / data-stealing apps)
 *  3. Enabled accessibility services — the #1 keylogger vector
 *  4. Active device admins — ransomware / remote-wipe vector
 *  5. Apps allowed to draw overlays — tapjacking / silent-install UIs
 *  6. Apps that can silently send SMS / read your calls
 *
 * No fake "antivirus" claims — it surfaces what is actually dangerous
 * and explains WHY, so the user can decide.
 */
object SecurityScanner {

    data class Finding(
        val severity: Int,           // 3 = critical, 2 = warning, 1 = info
        val icon: String,
        val app: String,
        val reason: String
    )

    private val SPY_PERMS = mapOf(
        Manifest.permission.READ_SMS to "reads your SMS",
        Manifest.permission.SEND_SMS to "can send SMS (premium scams)",
        Manifest.permission.RECEIVE_SMS to "intercepts incoming SMS (OTP theft)",
        Manifest.permission.READ_CALL_LOG to "reads your call log",
        Manifest.permission.RECORD_AUDIO to "records your microphone",
        Manifest.permission.CAMERA to "uses your camera",
        Manifest.permission.READ_CONTACTS to "reads your contacts",
        Manifest.permission.ACCESS_FINE_LOCATION to "tracks your precise location",
        Manifest.permission.READ_PHONE_STATE to "reads phone state (IMEI)",
        Manifest.permission.SYSTEM_ALERT_WINDOW to "draws over other apps",
        Manifest.permission.REQUEST_INSTALL_PACKAGES to "can silently install APKs",
    )

    /** Trusted installers — anything else is a sideload. */
    private val TRUSTED_INSTALLERS = setOf(
        "com.android.vending",            // Google Play
        "com.sec.android.app.samsungapps" // Galaxy Store
    )

    fun audit(context: Context): List<Finding> {
        val pm = context.packageManager
        val findings = mutableListOf<Finding>()
        val me = context.packageName

        val packages = pm.getInstalledPackages(
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES
        )

        // 1) Permission combos + 2) sideloaded apps
        for (pi in packages) {
            val pkg = pi.packageName
            if (pkg == me) continue
            val label = runCatching {
                pi.applicationInfo.loadLabel(pm).toString()
            }.getOrDefault(pkg)

            val perms = pi.requestedPermissions ?: emptyArray()
            val flagged = perms.filter { SPY_PERMS.containsKey(it) }

            // Sideloaded?
            val installer = runCatching {
                pm.getInstallSourceInfo(pkg).installingPackageName
            }.getOrNull()
            val sideloaded = installer !in TRUSTED_INSTALLERS

            // Spyware combo: mic or SMS + location, all in a sideloaded app
            val hasMic = perms.contains(Manifest.permission.RECORD_AUDIO)
            val hasSms = perms.contains(Manifest.permission.READ_SMS) ||
                    perms.contains(Manifest.permission.RECEIVE_SMS)
            val hasLoc = perms.contains(Manifest.permission.ACCESS_FINE_LOCATION)

            if (sideloaded && (hasMic || hasSms) && hasLoc) {
                findings.add(Finding(3, "🚨", label,
                    "Sideloaded app with spyware pattern: microphone/SMS + location tracking. " +
                            "Installed by: ${installer ?: "unknown source"}"))
            } else if (sideloaded && flagged.size >= 3) {
                findings.add(Finding(2, "⚠️", label,
                    "Sideloaded (from ${installer ?: "unknown source"}) with ${flagged.size} sensitive permissions: " +
                            flagged.joinToString { SPY_PERMS[it] ?: it }))
            } else if (sideloaded && (
                        perms.contains(Manifest.permission.REQUEST_INSTALL_PACKAGES) ||
                        perms.contains(Manifest.permission.SYSTEM_ALERT_WINDOW))
            ) {
                findings.add(Finding(2, "⚠️", label,
                    "Sideloaded and can install APKs or draw over your screen — " +
                            "classic silent-installer behavior"))
            } else if (flagged.count { it == Manifest.permission.RECEIVE_SMS } > 0) {
                findings.add(Finding(1, "ℹ️", label, "Can receive/intercept SMS (OTP codes)"))
            }
        }

        // 3) Accessibility services — keylogger vector
        val enabledA11y = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        enabledA11y.split(':').filter { it.isNotBlank() }.forEach { comp ->
            val pkg = comp.substringBefore('/').substringAfterLast('.', comp.substringBefore('/'))
            val shortComp = comp.substringBefore('/')
            if (shortComp != me) {
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(shortComp, 0)).toString()
                }.getOrDefault(shortComp)
                findings.add(Finding(3, "🚨", label,
                    "Has an ACTIVE accessibility service — it can read everything on your screen " +
                            "and every key you type. Only your trusted accessibility apps should be here."))
            }
        }

        // 4) Device admins (ours excluded)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.activeAdmins?.forEach { comp: ComponentName ->
            if (comp.packageName != me) {
                findings.add(Finding(2, "⚠️",
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(comp.packageName, 0)).toString() }.getOrDefault(comp.packageName),
                    "Is an active device administrator — can lock/wipe your phone remotely."))
            }
        }

        return findings.sortedByDescending { it.severity }
    }

    /** True when the audit found anything worth alerting about. */
    fun hasCritical(findings: List<Finding>): Boolean =
        findings.any { it.severity >= 2 }
}
