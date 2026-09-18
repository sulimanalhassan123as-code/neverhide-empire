package com.neverhide.empire.cleaner

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.dashboard.GlassCard
import com.neverhide.empire.dashboard.GlowButton
import com.neverhide.empire.dashboard.Palette
import com.neverhide.empire.dashboard.SectionHeader
import com.neverhide.empire.dashboard.StatusChip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CYBER CLEANER SUITE — the deep-maintenance center of the Empire.
 *
 * Four weapons on one screen:
 *  1. Junk Cleaner   — sweeps cache, temp, logs, thumbnails, empty folders
 *  2. Memory Booster — RAM report + kills background processes, shows freed MB
 *  3. Antivirus      — permission-risk scanner that flags dangerous apps
 *                      (droppers, SMS spies, hidden installers)
 *  4. App Freezer    — suspends apps like Tecno/Infinix "Freezer":
 *                      frozen apps cannot start, run, or consume RAM/battery.
 *                      Requires Device Owner (one ADB command) or Shizuku.
 */
class CleanerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CleanerScreen() }
    }

    // ================= DATA =================

    data class JunkItem(val file: File, val size: Long, val reason: String)

    data class AppRisk(
        val pkg: String,
        val label: String,
        val score: Int,
        val reasons: List<String>,
        val sideloaded: Boolean
    ) {
        val level: String get() = if (score >= 7) "HIGH" else if (score >= 4) "MEDIUM" else "LOW"
    }

    // ================= JUNK CLEANER =================

    /** All files access granted? (needed for a real deep sweep on Android 11+) */
    private fun allFilesGranted(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun requestAllFiles(context: Context) {
        if (Build.VERSION.SDK_INT >= 30) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + context.packageName)
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    /** Recursively compute file/dir size, capped so a runaway scan can't hang. */
    private fun sizeOf(f: File, depth: Int): Long {
        if (depth > 6) return 0L
        return try {
            if (f.isDirectory) f.listFiles()?.sumOf { sizeOf(it, depth + 1) } ?: 0L
            else f.length()
        } catch (e: Exception) { 0L }
    }

    private fun scanJunk(context: Context): List<JunkItem> {
        val found = ArrayList<JunkItem>()
        val root = Environment.getExternalStorageDirectory()
        val now = System.currentTimeMillis()

        fun addDir(dir: File, reason: String) {
            if (dir.exists() && dir.isDirectory) {
                val s = sizeOf(dir, 0)
                if (s > 0) found.add(JunkItem(dir, s, reason))
            }
        }

        // 1. Thumbnail caches (camera roll previews — safe to wipe, rebuilt on demand)
        addDir(File(root, "DCIM/.thumbnails"), "Camera thumbnails")
        addDir(File(root, "Pictures/.thumbnails"), "Picture thumbnails")
        // 2. Own cache dirs (internal + external)
        addDir(context.cacheDir, "App cache")
        context.externalCacheDirs?.forEach { addDir(it, "External app cache") }
        // 3. Temp/log files in Download, Documents & storage root (older than 1 day only)
        val staleDirs = listOf(File(root, "Download"), File(root, "Documents"), root)
        for (d in staleDirs) {
            try {
                d.listFiles()?.forEach { f ->
                    if (f.isFile) {
                        val n = f.name.lowercase(Locale.ROOT)
                        val old = now - f.lastModified() > 24L * 3600 * 1000
                        if (old && (n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".log"))) {
                            found.add(JunkItem(f, f.length(), "Stale temp/log file"))
                        }
                    }
                }
            } catch (e: Exception) { /* skip */ }
        }
        // 4. Messenger cache-style folders
        listOf(
            File(root, "WhatsApp/Media/WhatsApp Sent/.trashed"),
            File(root, "Telegram/Telegram Documents/.thumb")
        ).forEach { addDir(it, "Messenger cache") }
        // 5. Other apps' external cache folders (Android/data/*/cache —
        //    silently skips whatever the system hides on Android 11+)
        try {
            File(root, "Android/data").listFiles()?.forEach { pkgDir ->
                val c = File(pkgDir, "cache")
                if (c.isDirectory) {
                    val s = sizeOf(c, 0)
                    if (s > 0) found.add(JunkItem(c, s, "App cache (Android/data)"))
                }
            }
        } catch (e: Exception) { /* restricted on newer Android */ }
        // 6. Empty folders on the storage root (depth 1 only — never touch Android/)
        try {
            root.listFiles()?.forEach { f ->
                if (f.isDirectory && f.name != "Android" && !f.name.startsWith(".")) {
                    f.listFiles()?.let { if (it.isEmpty()) found.add(JunkItem(f, 0L, "Empty folder")) }
                }
            }
        } catch (e: Exception) { /* skip */ }

        return found.filter { it.size > 0L || it.reason == "Empty folder" }
    }

    private fun deleteRecursively(f: File, depth: Int): Boolean {
        if (depth > 7) return false
        return try {
            if (f.isDirectory) {
                f.listFiles()?.forEach { deleteRecursively(it, depth + 1) }
                f.delete()
            } else f.delete()
        } catch (e: Exception) { false }
    }

    // ================= MEMORY BOOSTER =================

    data class RamSnapshot(val total: Long, val avail: Long) {
        val usedPct: Int get() = (((total - avail) * 100) / total.coerceAtLeast(1L)).toInt()
    }

    private fun ramNow(context: Context): RamSnapshot {
        val am = context.getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return RamSnapshot(mi.totalMem, mi.availMem)
    }

    private fun boost(context: Context): Pair<RamSnapshot, Long> {
        val am = context.getSystemService(ActivityManager::class.java)
        val before = ramNow(context)
        // Kill every background process we're allowed to touch. System-critical and
        // foreground UI can't be killed with this API, so it's safe by construction.
        try {
            val procs = am.runningAppProcesses ?: emptyList()
            procs.forEach { p ->
                if (p.processName != context.packageName) {
                    runCatching { am.killBackgroundProcesses(p.processName) }
                }
            }
        } catch (e: Exception) { /* best-effort */ }
        val after = ramNow(context)
        return Pair(after, (after.avail - before.avail).coerceAtLeast(0L))
    }

    // ================= ANTIVIRUS =================

    private fun scanApps(context: Context): List<AppRisk> {
        val pm = context.packageManager
        val risks = ArrayList<AppRisk>()
        val packages = try {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        } catch (e: Exception) { emptyList() }

        for (pi in packages) {
            val ai = pi.applicationInfo ?: continue
            val perms = pi.requestedPermissions ?: continue
            var score = 0
            val reasons = ArrayList<String>()
            val has = { p: String -> perms.any { it.equals(p, true) } }

            if (has("android.permission.SEND_SMS") || has("android.permission.RECEIVE_SMS") ||
                has("android.permission.READ_SMS")) { score += 4; reasons.add("SMS access") }
            if (has("android.permission.READ_CALL_LOG") || has("android.permission.PROCESS_OUTGOING_CALLS")) { score += 3; reasons.add("Call log") }
            if (has("android.permission.READ_CONTACTS")) { score += 2; reasons.add("Contacts") }
            if (has("android.permission.RECORD_AUDIO")) { score += 2; reasons.add("Microphone") }
            if (has("android.permission.CAMERA")) { score += 1; reasons.add("Camera") }
            if (has("android.permission.ACCESS_FINE_LOCATION")) { score += 1; reasons.add("Location") }
            if (has("android.permission.ACCESS_BACKGROUND_LOCATION")) { score += 2; reasons.add("Background location") }
            if (has("android.permission.REQUEST_INSTALL_PACKAGES")) { score += 3; reasons.add("Can silently install APKs") }
            if (has("android.permission.RECEIVE_BOOT_COMPLETED") && has("android.permission.INTERNET")) { score += 1; reasons.add("Auto-starts with phone") }
            if (has("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")) { score += 1; reasons.add("Dodges battery saver") }
            if (has("android.permission.BIND_DEVICE_ADMIN")) { score += 2; reasons.add("Requests device admin") }
            if (has("android.permission.SYSTEM_ALERT_WINDOW")) { score += 2; reasons.add("Overlay windows") }
            if (has("android.permission.PACKAGE_USAGE_STATS")) { score += 1; reasons.add("Tracks your app usage") }

            // Sideloaded = installed outside the Play Store (higher risk profile)
            val installer = try {
                if (Build.VERSION.SDK_INT >= 30)
                    pm.getInstallSourceInfo(ai.packageName).installingPackageName else null
            } catch (e: Exception) { null }
            val sideloaded = installer == null ||
                    installer == "com.android.packageinstaller" ||
                    installer == "com.google.android.packageinstaller"
            if (sideloaded) { score += 1 }

            if (score >= 4) {
                val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { ai.packageName }
                risks.add(AppRisk(ai.packageName, label, score, reasons.take(4), sideloaded))
            }
        }
        return risks.sortedByDescending { it.score }
    }

    // ================= APP FREEZER =================

    private fun isDeviceOwner(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java)
            .isDeviceOwnerApp(context.packageName)

    private fun shizukuReady(): Boolean = try { Shizuku.pingBinder() } catch (e: Exception) { false }
    private fun shizukuGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) { false }

    /** Freeze/suspend a package. Returns null on success or an error message. */
    private fun setSuspended(context: Context, pkg: String, suspend: Boolean): String? {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        // Path 1 — Device Owner (activated once via ADB): official, instant, reliable.
        // The setPackagesSuspended(String[], boolean) runtime method exists since API 28,
        // but SDK 34 removed it from the compile stubs (only the API-33 admin overload
        // survives there), and that new overload does not exist on Android 11/12 devices.
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
        // Path 2 — Shizuku (shell uid): pm disable-user, works in plain ADB mode.
        // pm suspend needs the SUSPEND_APPS permission or device-owner status —
        // the shell identity has NEITHER, so it fails silently with a permission
        // error (this was the real bug: taps looked like they did nothing).
        // pm disable-user / pm enable ARE shell-permitted (the standard
        // non-root freeze technique used by e.g. Shelter/Island/Hail) —
        // fully disables the app (removed from launcher, can't run at all).
        if (shizukuReady() && shizukuGranted()) {
            // pm exit codes LIE on some Samsung builds (prints "Error: ..." but
            // exits 0), so we never trust the code — we verify the package's
            // REAL state after every command, against BOTH blocking states:
            //   -d  = disabled  (what pm disable-user / pm enable manage)
            //   --suspended = suspended (what pm suspend / pm unsuspend manage,
            //                 also used by Knox Guard itself to freeze apps —
            //                 a stuck app can be in this state, which plain
            //                 pm enable can NEVER release)
            fun nowBlocked(): Boolean? {
                val disabled = ShizukuShell.run(context, "pm list packages -d --user 0")
                    ?: return null
                val suspended = ShizukuShell.run(context, "pm list packages --suspended --user 0")
                    ?: return null
                val inDisabled = disabled.second.lines().any { it.trim() == "package:$pkg" }
                val inSuspended = suspended.second.lines().any { it.trim() == "package:$pkg" }
                return inDisabled || inSuspended
            }
            if (suspend) {
                ShizukuShell.exec(context, "pm disable-user --user 0 $pkg")
                val still = nowBlocked()
                return when {
                    still == null -> "could not verify freeze (state check failed)"
                    still -> null
                    else -> "device refused the freeze"
                }
            } else {
                // UNFREEZE ARTILLERY — fires every release command Android has,
                // in order, stopping the moment the device confirms the app is
                // really free. Covers stuck apps in ANY blocking state, including
                // old freezes and Knox-enforced suspends.
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
     * Real suspended-state list. Via Shizuku shell when armed, else via the
     * reflective API-28 isPackageSuspended(String) when we are Device Owner.
     * Returns null when neither power path is available.
     */
    private fun suspendedPackages(context: Context): Set<String>? {
        if (shizukuReady() && shizukuGranted()) {
            val disabled = ShizukuShell.query(context, "pm list packages -d --user 0")
                ?: return null
            val result = disabled.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toMutableSet()
            // Merge the suspend list too — apps frozen in the OLD scheme (or by
            // Knox Guard) live there and must still show as FROZEN.
            ShizukuShell.query(context, "pm list packages --suspended --user 0")
                ?.lines()
                ?.filter { it.startsWith("package:") }
                ?.forEach { result.add(it.removePrefix("package:").trim()) }
            return result.toSet()
        }
        if (isDeviceOwner(context) && Build.VERSION.SDK_INT >= 28) {
            return try {
                val dpm = context.getSystemService(DevicePolicyManager::class.java)
                val m = DevicePolicyManager::class.java.getMethod(
                    "isPackageSuspended", String::class.java
                )
                listFreezableApps(context)
                    .map { it.packageName }
                    .filter { m.invoke(dpm, it) as Boolean }
                    .toSet()
            } catch (e: Exception) { null }
        }
        return null
    }

    /**
     * FREEZER DOCTOR — for stuck freezes. Fires the full release artillery at
     * every frozen package while capturing each command's REAL output, then
     * gathers device state. The report answers three questions:
     *   1. is there shell power right now (Shizuku alive + granted)?
     *   2. what EXACTLY does pm answer to each release command?
     *   3. what does the OS say the package's real state is?
     * Report is returned for display + clipboard so the owner can send it
     * to Lyra for remote root-cause analysis.
     */
    private fun runDoctor(context: Context, pkgs: Set<String>): String {
        val sb = StringBuilder()
        sb.append("=== FREEZER DOCTOR v2.6.5 ===\n")
        sb.append("deviceOwner=" + isDeviceOwner(context) + "\n")
        val ready = shizukuReady(); val granted = shizukuGranted()
        sb.append("shizukuReady=" + ready + " granted=" + granted + "\n")
        if (!ready || !granted) {
            sb.append("NO SHELL POWER: start Shizuku (wireless debugging), then grant.\n")
            return sb.toString()
        }
        val dis = ShizukuShell.run(context, "pm list packages -d --user 0")
        sb.append("disabledList exit=" + (dis?.first) + ": " + (dis?.second ?: "").trim().take(400) + "\n")
        val sus = ShizukuShell.run(context, "pm list packages --suspended --user 0")
        sb.append("suspendedList exit=" + (sus?.first) + ": " + (sus?.second ?: "").trim().take(400) + "\n")
        val dp = ShizukuShell.run(context, "dumpsys device_policy")
        val dpLines = dp?.second?.lines()
            ?.filter { it.contains("Owner", ignoreCase = true) }
            ?.take(3)?.joinToString(" | ") ?: "unavailable"
        sb.append("device_policy: " + dpLines + "\n")
        for (pkg in pkgs) {
            sb.append("\n--- " + pkg + " ---\n")
            for (cmd in listOf(
                "pm enable --user 0 " + pkg,
                "pm enable " + pkg,
                "pm unsuspend --user 0 " + pkg,
                "pm unsuspend " + pkg
            )) {
                val r = ShizukuShell.run(context, cmd)
                if (r == null) { sb.append(cmd + " -> SHELL UNAVAILABLE\n"); break }
                sb.append(cmd + " -> exit=" + r.first + " out=" + r.second.trim().take(150) + "\n")
            }
            val dmp = ShizukuShell.run(context, "dumpsys package " + pkg)
            val state = dmp?.second?.lines()
                ?.filter { it.contains("enabled") || it.contains("suspended") || it.contains("stopped") || it.contains("hidden") }
                ?.map { it.trim() }?.take(6)?.joinToString(" | ") ?: "dumpsys unavailable"
            sb.append("real state: " + state + "\n")
        }
        sb.append("\n=== END (copied to clipboard - send to Lyra) ===\n")
        return sb.toString()
    }

    private fun listFreezableApps(context: Context): List<ApplicationInfo> {
        val pm = context.packageManager
        return pm.getInstalledApplications(0)
            .filter {
                (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 &&
                        it.packageName != context.packageName
            }
            .sortedBy { it.loadLabel(pm).toString().lowercase(Locale.ROOT) }
    }

    // ================= UI =================

    @Composable
    private fun CleanerScreen() {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val scroll = rememberScrollState()

        // Junk state
        var junk by remember { mutableStateOf<List<JunkItem>>(emptyList()) }
        var junkScanning by remember { mutableStateOf(false) }
        var junkFreed by remember { mutableStateOf(0L) }
        var lastScan by remember { mutableStateOf("--") }

        // RAM state
        var ram by remember { mutableStateOf(ramNow(context)) }
        var boosting by remember { mutableStateOf(false) }
        var freedMb by remember { mutableStateOf(0f) }

        // Antivirus state
        var risks by remember { mutableStateOf<List<AppRisk>>(emptyList()) }
        var scanning by remember { mutableStateOf(false) }
        var scanCount by remember { mutableStateOf(0) }

        // Freezer state
        var frozen by remember { mutableStateOf<Set<String>>(emptySet()) }
        var liveFrozen by remember { mutableStateOf<Set<String>?>(null) }
        var freezeError by remember { mutableStateOf<String?>(null) }
        var doctorReport by remember { mutableStateOf<String?>(null) }
        var freezerMode by remember {
            mutableStateOf(
                if (isDeviceOwner(context)) "DEVICE OWNER"
                else if (shizukuReady()) "SHIZUKU" else "OFF"
            )
        }

        fun fmtSize(bytes: Long): String = when {
            bytes >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
            bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.pageBg)
                .verticalScroll(scroll)
                .padding(16.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🛡️ Cyber Cleaner", color = Palette.WHITE, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                StatusChip(if (allFilesGranted()) "DEEP ACCESS" else "LIMITED", if (allFilesGranted()) Palette.GREEN else Palette.AMBER)
            }
            Text("Deep-clean • Boost • Antivirus • Freeze apps", color = Palette.TEXT_MUTE, fontSize = 11.sp)
            Spacer(Modifier.height(14.dp))

            // ===== 1. JUNK CLEANER =====
            SectionHeader("🧹", "Junk Cleaner", Palette.GREEN)
            Spacer(Modifier.height(8.dp))
            GlassCard(glow = Palette.GREEN) {
                Text(
                    if (junk.isEmpty())
                        "Cache, thumbnails, logs, temp files, empty folders — all swept in one deep scan."
                    else
                        "Found " + junk.size + " junk locations — " + fmtSize(junk.sumOf { it.size }) + " reclaimable.",
                    color = Palette.TEXT_DIM, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            if (junkFreed > 0) fmtSize(junkFreed)
                            else (if (junkScanning) "…" else fmtSize(junk.sumOf { it.size })),
                            color = Palette.GREEN, fontSize = 30.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (junkFreed > 0) "FREED 🎉" else if (junkScanning) "SCANNING…" else "RECLAIMABLE",
                            color = Palette.TEXT_MUTE, fontSize = 10.sp
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text("last scan: $lastScan", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton("🔍 Deep Scan", listOf(Palette.GREEN, Color(0xFF00E676)), Modifier.weight(1f)) {
                        junkScanning = true
                        scope.launch {
                            val found = withContext(Dispatchers.IO) { scanJunk(context) }
                            junk = found
                            junkFreed = 0
                            lastScan = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                            junkScanning = false
                        }
                    }
                    GlowButton("🧹 Clean All", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f)) {
                        if (junk.isEmpty()) return@GlowButton
                        scope.launch {
                            var freedTotal = 0L
                            withContext(Dispatchers.IO) {
                                junk.forEach { item ->
                                    freedTotal += item.size
                                    deleteRecursively(item.file, 0)
                                }
                            }
                            junkFreed = freedTotal
                            junk = emptyList()
                        }
                    }
                }
                if (!allFilesGranted()) {
                    Spacer(Modifier.height(8.dp))
                    Text("⚠ Grant All-Files access for a full sweep:", color = Palette.AMBER, fontSize = 11.sp)
                    Spacer(Modifier.height(6.dp))
                    GlowButton("🔓 Grant All Files Access", listOf(Palette.AMBER, Color(0xFFFF8F00)), Modifier.fillMaxWidth()) {
                        requestAllFiles(context)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            // ===== 2. MEMORY BOOSTER =====
            SectionHeader("⚡", "Memory Booster", Palette.PURPLE)
            Spacer(Modifier.height(8.dp))
            GlassCard(glow = Palette.PURPLE) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text(
                            ram.usedPct.toString() + "%",
                            color = if (ram.usedPct > 80) Palette.PINK else Palette.PURPLE,
                            fontSize = 30.sp, fontWeight = FontWeight.Bold
                        )
                        Text("RAM in use", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text(fmtSize(ram.avail), color = Palette.GREEN, fontSize = 14.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("free of " + fmtSize(ram.total), color = Palette.TEXT_MUTE, fontSize = 10.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                // RAM bar
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .background(Color(0xFF263238), RoundedCornerShape(99.dp))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(ram.usedPct / 100f)
                            .fillMaxHeight()
                            .background(
                                Brush.linearGradient(listOf(Palette.PURPLE, Palette.PINK)),
                                RoundedCornerShape(99.dp)
                            )
                    )
                }
                Spacer(Modifier.height(12.dp))
                if (freedMb > 0) {
                    Text("✅ Boosted — freed " + String.format(Locale.US, "%.0f", freedMb) + " MB",
                        color = Palette.GREEN, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                }
                GlowButton(
                    if (boosting) "⚡ BOOSTING…" else "⚡ Boost Now",
                    listOf(Palette.PURPLE, Color(0xFF651FFF)),
                    Modifier.fillMaxWidth()
                ) {
                    boosting = true
                    freedMb = 0f
                    scope.launch {
                        val (after, freed) = withContext(Dispatchers.Default) { boost(context) }
                        ram = after
                        freedMb = freed / 1048576f
                        boosting = false
                    }
                }
            }
            Spacer(Modifier.height(18.dp))

            // ===== 3. ANTIVIRUS =====
            SectionHeader("🦠", "Antivirus", Palette.CYAN)
            Spacer(Modifier.height(8.dp))
            GlassCard(glow = Palette.CYAN) {
                Text(
                    "Scans every installed app for dangerous permission combinations: " +
                            "SMS spies, call-log thieves, hidden APK installers, overlay attacks.",
                    color = Palette.TEXT_DIM, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                if (risks.isNotEmpty()) {
                    val high = risks.count { it.level == "HIGH" }
                    val med = risks.count { it.level == "MEDIUM" }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip("$high HIGH", Palette.PINK, filled = true)
                        StatusChip("$med MEDIUM", Palette.AMBER, filled = true)
                        StatusChip("$scanCount apps scanned", Palette.TEXT_DIM)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                GlowButton(
                    if (scanning) "🦠 SCANNING…" else "🛡️ Scan for Threats",
                    listOf(Palette.CYAN, Color(0xFF00838F)), Modifier.fillMaxWidth()
                ) {
                    scanning = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { scanApps(context) }
                        risks = result
                        scanCount = try {
                            context.packageManager.getInstalledApplications(0).size
                        } catch (e: Exception) { 0 }
                        scanning = false
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (risks.isNotEmpty()) {
                    risks.take(12).forEach { r ->
                        val c = if (r.level == "HIGH") Palette.PINK else Palette.AMBER
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.label, color = Palette.WHITE, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(
                                    r.reasons.joinToString(" • ") + if (r.sideloaded) " • sideloaded" else "",
                                    color = Palette.TEXT_MUTE, fontSize = 10.sp, maxLines = 2
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            StatusChip(r.level, c, filled = true)
                            Text("🗑", Modifier
                                .clickable {
                                    context.startActivity(
                                        Intent(Intent.ACTION_DELETE, Uri.parse("package:" + r.pkg))
                                    )
                                }
                                .padding(horizontal = 6.dp), fontSize = 16.sp)
                        }
                    }
                    if (risks.size > 12)
                        Text("…and " + (risks.size - 12) + " more", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                } else if (!scanning) {
                    Text("Last verdict: no threats flagged yet.", color = Palette.TEXT_MUTE, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(18.dp))

            // ===== 4. APP FREEZER =====
            SectionHeader("❄️", "App Freezer", Palette.PINK)
            Spacer(Modifier.height(8.dp))
            GlassCard(glow = Palette.PINK) {
                Text(
                    "Suspend apps like Tecno/Infinix Freezer — frozen apps can't start, " +
                            "run in background, drain RAM or battery. Nothing is uninstalled.",
                    color = Palette.TEXT_DIM, fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                when (freezerMode) {
                    "DEVICE OWNER" -> StatusChip("DEVICE OWNER ACTIVE — FULL POWER", Palette.GREEN, filled = true)
                    "SHIZUKU" -> if (shizukuGranted())
                        StatusChip("SHIZUKU GRANTED", Palette.GREEN, filled = true)
                    else
                        StatusChip("SHIZUKU RUNNING — GRANT PERMISSION", Palette.AMBER, filled = true)
                    else -> StatusChip("NOT ARMED — SEE SETUP BELOW", Palette.AMBER, filled = true)
                }
                Spacer(Modifier.height(10.dp))

                if (freezerMode == "OFF") {
                    Text("Activate the freezer (one-time, pick ONE):",
                        color = Palette.WHITE, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "1. ADB once (permanent, works offline):\n" +
                                "   adb shell dpm set-device-owner com.neverhide.empire/.guardian.GuardianAdminReceiver",
                        color = Palette.TEXT_DIM, fontSize = 11.sp, fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "2. Or install Shizuku, start it (wireless debugging), grant permission when prompted.",
                        color = Palette.TEXT_DIM, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlowButton("🔄 Re-check", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f)) {
                            freezerMode = if (isDeviceOwner(context)) "DEVICE OWNER"
                            else if (shizukuReady()) "SHIZUKU" else "OFF"
                        }
                        if (shizukuReady() && !shizukuGranted()) {
                            GlowButton("🔑 Grant Shizuku", listOf(Palette.AMBER, Color(0xFFFF8F00)), Modifier.weight(1f)) {
                                ShizukuShell.requestPermission()
                            }
                        }
                    }
                } else {
                    val apps = remember { listFreezableApps(context) }
                    // Read the real suspended state off the UI thread —
                    // the old code ran a blocking shell query during
                    // composition (ANR risk on slow devices).
                    LaunchedEffect(freezerMode) {
                        liveFrozen = withContext(Dispatchers.IO) { suspendedPackages(context) }
                    }
                    val frozenSet = liveFrozen ?: frozen
                    Text(
                        frozenSet.size.toString() + " frozen • " + apps.size + " apps manageable",
                        color = Palette.TEXT_MUTE, fontSize = 11.sp
                    )
                    if (freezeError != null) {
                        Spacer(Modifier.height(4.dp))
                        Text("⚠ $freezeError", color = Palette.PINK, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    // Split into two clear groups instead of one mixed list —
                    // frozen apps up top so it's obvious at a glance which
                    // apps are frozen vs still active.
                    val labelOf = remember(apps) {
                        apps.associateWith { it.loadLabel(context.packageManager).toString() }
                    }
                    val frozenApps = apps.filter { frozenSet.contains(it.packageName) }
                        .sortedBy { labelOf[it] ?: "" }
                    val activeApps = apps.filter { !frozenSet.contains(it.packageName) }
                        .sortedBy { labelOf[it] ?: "" }

                    fun toggle(app: android.content.pm.ApplicationInfo, isFrozen: Boolean) {
                        scope.launch {
                            val err = withContext(Dispatchers.IO) {
                                setSuspended(context, app.packageName, !isFrozen)
                            }
                            if (err == null) {
                                freezeError = null
                                frozen = if (isFrozen) frozenSet - app.packageName
                                else frozenSet + app.packageName
                                // Re-read the REAL device state (source of truth)
                                liveFrozen = withContext(Dispatchers.IO) {
                                    suspendedPackages(context)
                                }
                            } else {
                                freezeError = "${labelOf[app]}: $err"
                            }
                        }
                    }

                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (frozenApps.isNotEmpty()) {
                            item {
                                Text(
                                    "❄ FROZEN — ${frozenApps.size}",
                                    color = Palette.PINK, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                                )
                            }
                            items(frozenApps, key = { "frozen_" + it.packageName }) { app ->
                                val isFrozen = true
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .background(Palette.PINK.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
                                        .clickable { toggle(app, isFrozen) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        labelOf[app] ?: app.packageName,
                                        color = Palette.TEXT_MUTE, fontSize = 12.sp,
                                        modifier = Modifier.weight(1f), maxLines = 1
                                    )
                                    StatusChip("❄ FROZEN", Palette.PINK, filled = true)
                                }
                            }
                            item { Spacer(Modifier.height(6.dp)) }
                        }
                        item {
                            Text(
                                "✅ ACTIVE — ${activeApps.size}",
                                color = Palette.GREEN, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                            )
                        }
                        items(activeApps, key = { "active_" + it.packageName }) { app ->
                            val isFrozen = false
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Color(0x0DFFFFFF), RoundedCornerShape(12.dp))
                                    .clickable { toggle(app, isFrozen) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    labelOf[app] ?: app.packageName,
                                    color = Palette.WHITE, fontSize = 12.sp,
                                    modifier = Modifier.weight(1f), maxLines = 1
                                )
                                StatusChip("ACTIVE", Palette.GREEN, filled = false)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    GlowButton("☀️ Unfreeze All (safety)",
                        listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.fillMaxWidth()) {
                        scope.launch {
                            frozenSet.forEach { pkg ->
                                withContext(Dispatchers.IO) { setSuspended(context, pkg, false) }
                            }
                            frozen = emptySet()
                            liveFrozen = withContext(Dispatchers.IO) { suspendedPackages(context) }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    GlowButton("🩺 Freezer Doctor — fix stuck apps + report",
                        listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.fillMaxWidth()) {
                        scope.launch {
                            doctorReport = "Running Doctor…"
                            val rep = withContext(Dispatchers.IO) { runDoctor(context, frozenSet) }
                            doctorReport = rep
                            // Refresh the list after the Doctor's own unfreeze attempts
                            liveFrozen = withContext(Dispatchers.IO) { suspendedPackages(context) }
                            try {
                                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                cm?.setPrimaryClip(android.content.ClipData.newPlainText("freezer-doctor", rep))
                            } catch (e: Exception) { }
                        }
                    }
                    if (doctorReport != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            doctorReport!!,
                            color = Palette.TEXT_DIM, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF0A0E14), RoundedCornerShape(12.dp))
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp)
                                .height(180.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
