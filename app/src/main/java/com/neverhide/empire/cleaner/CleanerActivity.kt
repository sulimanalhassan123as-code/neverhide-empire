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
        // Path 1 — Device Owner (activated once via ADB): official, instant, reliable
        if (isDeviceOwner(context) && Build.VERSION.SDK_INT >= 28) {
            return try {
                dpm.setPackagesSuspended(arrayOf(pkg), suspend)
                null
            } catch (e: Exception) { e.message }
        }
        // Path 2 — Shizuku (shell uid): pm suspend, works in ADB mode
        if (shizukuReady() && shizukuGranted()) {
            return try {
                val cmd = "pm suspend --user 0 " + pkg + if (!suspend) " --unsuspend" else ""
                val proc = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
                val code = proc.waitFor()
                proc.destroy()
                if (code == 0) null else "pm exit " + code
            } catch (e: Exception) { e.message }
        }
        return "no_power"
    }

    /** Real suspended-state list (via Shizuku shell; null if unavailable). */
    private fun suspendedPackages(): Set<String>? {
        if (!(shizukuReady() && shizukuGranted())) return null
        return try {
            val proc = Shizuku.newProcess(
                arrayOf("sh", "-c", "pm list packages --user 0 --suspended"), null, null
            )
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor(); proc.destroy()
            out.lines()
                .filter { it.startsWith("package:") }
                .map { it.removePrefix("package:").trim() }
                .toSet()
        } catch (e: Exception) { null }
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
                                runCatching { Shizuku.requestPermission(21) }
                            }
                        }
                    }
                } else {
                    val apps = remember { listFreezableApps(context) }
                    val liveFrozen = remember(freezerMode) { suspendedPackages() }
                    val frozenSet = liveFrozen ?: frozen
                    Text(
                        frozenSet.size.toString() + " frozen • " + apps.size + " apps manageable",
                        color = Palette.TEXT_MUTE, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(
                        Modifier
                            .fillMaxWidth()
                            .height(320.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(apps) { app ->
                            val isFrozen = frozenSet.contains(app.packageName)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isFrozen) Palette.PINK.copy(alpha = 0.10f) else Color(0x0DFFFFFF),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        scope.launch {
                                            val err = withContext(Dispatchers.IO) {
                                                setSuspended(context, app.packageName, !isFrozen)
                                            }
                                            if (err == null) {
                                                frozen = if (isFrozen) frozenSet - app.packageName
                                                else frozenSet + app.packageName
                                            }
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    app.loadLabel(context.packageManager).toString(),
                                    color = if (isFrozen) Palette.TEXT_MUTE else Palette.WHITE,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                StatusChip(if (isFrozen) "❄ FROZEN" else "ACTIVE",
                                    if (isFrozen) Palette.PINK else Palette.GREEN, filled = isFrozen)
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
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
