package com.neverhide.empire

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.dashboard.*
import com.neverhide.empire.calls.QuickDialActivity
import com.neverhide.empire.quran.QuranActivity
import com.neverhide.empire.updater.WhatsNew
import com.neverhide.empire.core.EmpireBackgroundService
import com.neverhide.empire.core.PermissionManager
import com.neverhide.empire.guardian.GuardianAdminReceiver
import com.neverhide.empire.guardian.JumpscareActivity
import com.neverhide.empire.launcher.Launcher3DActivity
import com.neverhide.empire.screenshot.FloatingBubbleService
import com.neverhide.empire.cleaner.CleanerActivity
import com.neverhide.empire.vault.AppVaultActivity
import com.neverhide.empire.screenshot.ScreenshotService
import com.neverhide.empire.tools.ToolsActivity
import com.neverhide.empire.updater.AdrenalineUpdater
import com.neverhide.empire.wallpaper.LiveWallpaperEngine
import com.neverhide.empire.wallpaper.effects.EffectCatalog

class MainActivity : ComponentActivity() {

    // Activity-level so the admin result callback can flip it
    private var guardianArmed by androidx.compose.runtime.mutableStateOf(false)

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            requestSpecialPermissions()
        }

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            if (r.resultCode == RESULT_OK && r.data != null) {
                ScreenshotService.start(this, r.resultCode, r.data!!)
            }
        }

    private val adminLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            guardianArmed = GuardianAdminReceiver.isAdminActive(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        guardianArmed = GuardianAdminReceiver.isAdminActive(this)
        setContent { EmpireHub() }

        // 1. Bulletproof watchdog starts immediately
        EmpireBackgroundService.start(this)

        // 2. Deep-sleep survival: battery optimization exemption
        requestBatteryOptimizationExemption()

        // 3. First-run runtime permissions
        if (!PermissionManager.hasAskedOnce(this)) {
            permissionLauncher.launch(PermissionManager.missing(this).toTypedArray())
            PermissionManager.markAsked(this)
        } else if (PermissionManager.missing(this).isNotEmpty()) {
            // Gentle re-ask on later opens — new v2 tools may need more perms
            permissionLauncher.launch(PermissionManager.missing(this).toTypedArray())
        }

        if (intent.getBooleanExtra("auto_capture", false)) {
            launchScreenshot()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra("auto_capture", false)) {
            launchScreenshot()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            runCatching { startActivity(intent) }
        }
    }

    private fun requestSpecialPermissions() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
        }
    }

    private fun launchScreenshot() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startBubbleService() {
        startForegroundService(Intent(this, FloatingBubbleService::class.java))
    }

    private fun setWallpaper(effectId: Int) {
        getSharedPreferences("empire_prefs", MODE_PRIVATE)
            .edit().putInt("wallpaper_effect", effectId).apply()
        try {
            val intent = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(packageName, LiveWallpaperEngine::class.java.name)
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    // ================= HUB UI =================

    @Composable
    private fun EmpireHub() {
        val scroll = rememberScrollState()
        // VAULT PATROL — every time the Empire opens, re-freeze any vault app
        // that was unlocked (by the owner or by a snooper via Settings → Enable).
        // Needs freeze power; silently skips when Shizuku/DO is down. Locks stay
        // active regardless — this only re-arms ones that were opened.
        LaunchedEffect(Unit) {
            val vault = com.neverhide.empire.vault.VaultStore.vaultApps(this@MainActivity)
            if (vault.isNotEmpty()) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    if (com.neverhide.empire.cleaner.FreezerEngine.hasPower(this@MainActivity)) {
                        val frozen = com.neverhide.empire.cleaner.FreezerEngine.realFrozenNow(this@MainActivity)
                        vault.filter { !frozen.contains(it) }.forEach {
                            com.neverhide.empire.cleaner.FreezerEngine.setSuspended(this@MainActivity, it, true)
                        }
                    }
                }
            }
        }
        var guardianTheme by remember {
            mutableStateOf(getSharedPreferences("guardian_prefs", MODE_PRIVATE).getInt(GuardianAdminReceiver.KEY_THEME, 0))
        }
        var selectedEffect by remember {
            mutableStateOf(getSharedPreferences("empire_prefs", MODE_PRIVATE).getInt("wallpaper_effect", 0))
        }
        val permProgress = PermissionManager.progress(this)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

        MaterialTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.pageBg)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // ===== Header =====
                    Text("👑 Neverhide Empire", color = Palette.CYAN, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusChip("v$version", Palette.CYAN)
                        Spacer(Modifier.width(6.dp))
                        StatusChip("${permProgress.first}/${permProgress.second} permissions", Palette.TEXT_DIM)
                        Spacer(Modifier.width(6.dp))
                        StatusChip("SECURE BUILD", Palette.GREEN)
                    }

                    // ===== HERO: Lock Guardian =====
                    GlassCard(glow = Palette.PURPLE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(54.dp)
                                    .background(Palette.heroGradient, RoundedCornerShape(16.dp))
                                    .border(1.dp, Palette.PURPLE.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                                contentAlignment = Alignment.Center
                            ) { Text("🛡️", fontSize = 26.sp) }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Adrenaline Lock Guardian", color = Palette.WHITE, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                                Text(
                                    if (guardianArmed) "ARMED — wrong passwords trigger the jumpscare"
                                    else "NOT ARMED — tap Enable to protect",
                                    color = if (guardianArmed) Palette.GREEN else Palette.TEXT_DIM, fontSize = 12.sp
                                )
                            }
                            if (guardianArmed) StatusChip("ACTIVE", Palette.GREEN)
                        }
                        Spacer(Modifier.height(12.dp))
                        // Theme picker
                        val themes = listOf("🌊 Water", "🔥 Fire", "⚡ Thunder", "🌑 Void")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            themes.forEachIndexed { i, label ->
                                val selected = guardianTheme == i
                                Box(
                                    Modifier
                                        .border(
                                            if (selected) 2.dp else 1.dp,
                                            if (selected) Palette.ORANGE else Color(0xFF2A3A4A),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .background(
                                            if (selected) Color(0xFF332000) else Palette.CARD,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .clickable {
                                            guardianTheme = i
                                            getSharedPreferences("guardian_prefs", MODE_PRIVATE)
                                                .edit().putInt(GuardianAdminReceiver.KEY_THEME, i).apply()
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(label, color = if (selected) Palette.ORANGE else Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // GUARDIAN 2.0 — FX mode + SMS alert config
                        var fxGunshot by remember {
                            mutableStateOf(getSharedPreferences("guardian_prefs", MODE_PRIVATE).getInt("guardian_fx_mode", 1) == 1)
                        }
                        var alertNum by remember {
                            mutableStateOf(getSharedPreferences("guardian_prefs", MODE_PRIVATE).getString("guardian_alert_number", "") ?: "")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("💥 Gunshot + cracked screen", color = Palette.WHITE, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            androidx.compose.material3.Switch(
                                checked = fxGunshot,
                                onCheckedChange = { on ->
                                    fxGunshot = on
                                    getSharedPreferences("guardian_prefs", MODE_PRIVATE).edit()
                                        .putInt("guardian_fx_mode", if (on) 1 else 0).apply()
                                },
                                colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Palette.ORANGE)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.OutlinedTextField(
                                value = alertNum, onValueChange = { alertNum = it },
                                label = { androidx.compose.material3.Text("SMS alert number (optional)", color = Palette.TEXT_DIM) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            GlowButton("💾", listOf(Palette.ORANGE, Color(0xFFDD2C00)), Modifier.height(52.dp)) {
                                getSharedPreferences("guardian_prefs", MODE_PRIVATE).edit()
                                    .putString("guardian_alert_number", alertNum.trim()).apply()
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        if (!guardianArmed) {
                            GlowButton("🛡️ Enable Lock Guardian", listOf(Palette.ORANGE, Color(0xFFDD2C00)), Modifier.fillMaxWidth()) {
                                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                        GuardianAdminReceiver.adminComponent(this@MainActivity))
                                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "Neverhide Empire uses this ONLY to detect wrong password attempts " +
                                                "and trigger the Adrenaline jumpscare. No data leaves your phone.")
                                }
                                adminLauncher.launch(intent)
                            }
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlowButton("⚔️ Test Jumpscare", listOf(Palette.ORANGE, Color(0xFFDD2C00)), Modifier.weight(1f)) {
                                    JumpscareActivity.launch(this@MainActivity, guardianTheme, null)
                                }
                                GlowButton("🔕 Disable", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f)) {
                                    dpm.removeActiveAdmin(GuardianAdminReceiver.adminComponent(this@MainActivity))
                                    guardianArmed = false
                                }
                            }
                        }
                    }

                    // ===== CAPTURE =====
                    SectionHeader("📸", "Capture")
                    GlassCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlowButton("📸 Screenshot", listOf(Palette.CYAN, Color(0xFF00838F)), Modifier.weight(1f)) { launchScreenshot() }
                            GlowButton("🫧 Bubble", listOf(Palette.PURPLE, Color(0xFF4A148C)), Modifier.weight(1f)) {
                                if (Settings.canDrawOverlays(this@MainActivity)) {
                                    startBubbleService()
                                } else {
                                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:$packageName")))
                                }
                            }
                        }
                    }

                    // ===== WALLPAPERS =====
                    SectionHeader("🖼️", "Live Wallpapers", Palette.PINK)
                    GlassCard(glow = Palette.PINK) {
                        Text("20 GPU effects — Water • Fire • Galaxy • Cyber", color = Palette.TEXT_DIM, fontSize = 12.sp)
                        Spacer(Modifier.height(10.dp))
                        LazyRowOfEffects(selectedEffect) { i ->
                            selectedEffect = i
                            getSharedPreferences("empire_prefs", MODE_PRIVATE)
                                .edit().putInt("wallpaper_effect", i).apply()
                        }
                        Spacer(Modifier.height(10.dp))
                        GlowButton("✨ Set Selected as Wallpaper", listOf(Palette.PINK, Color(0xFFC2185B)), Modifier.fillMaxWidth()) {
                            setWallpaper(selectedEffect)
                        }
                    }

                    // ===== POWER TOOLS =====
                    SectionHeader("🧰", "Power Tools", Palette.GREEN)
                    GlassCard {
                        Text("16 utilities for everyday power use", color = Palette.TEXT_DIM, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToolTile("🔦", "Light", Palette.CYAN) { startActivity(Intent(this@MainActivity, ToolsActivity::class.java)) }
                            ToolTile("🧭", "Compass", Palette.GREEN) { startActivity(Intent(this@MainActivity, ToolsActivity::class.java)) }
                            ToolTile("🔋", "Battery", Palette.ORANGE) { startActivity(Intent(this@MainActivity, ToolsActivity::class.java)) }
                            ToolTile("🧹", "RAM", Palette.PURPLE) { startActivity(Intent(this@MainActivity, ToolsActivity::class.java)) }
                        }
                        Spacer(Modifier.height(12.dp))
                        GlowButton("Open all 16 Power Tools", listOf(Palette.GREEN, Color(0xFF00E676)), Modifier.fillMaxWidth()) {
                            startActivity(Intent(this@MainActivity, ToolsActivity::class.java))
                        }
                    }

                    // ===== CYBER CLEANER =====
                    SectionHeader("🛡️", "Cyber Cleaner", Palette.CYAN)
                    FeatureCard("🛡️", "Cyber Cleaner & Antivirus", "Junk • Boost • Virus scan • App Freezer", Palette.CYAN,
                        badge = "NEW") { startActivity(Intent(this@MainActivity, CleanerActivity::class.java)) }
                    FeatureCard("🔐", "App Vault — Device Locks", "PIN-gated device-level app locks • auto re-lock", Palette.PINK,
                        badge = "NEW") { startActivity(Intent(this@MainActivity, AppVaultActivity::class.java)) }

                    // ===== COMMUNICATION =====
                    SectionHeader("📞", "Communication", Palette.CYAN)
                    FeatureCard("📞", "Calls — Quick Dial", "Instant dialer with call-log access", Palette.CYAN,
                        badge = "NEW") { startActivity(Intent(this@MainActivity, QuickDialActivity::class.java)) }

                    // ===== MORE =====
                    SectionHeader("🚀", "More", Palette.PURPLE)
                    FeatureCard("🚀", "3D App Launcher", "Sphere • Cube • Circle layouts in OpenGL", Palette.PURPLE) {
                        startActivity(Intent(this@MainActivity, Launcher3DActivity::class.java))
                    }
                    FeatureCard("📖", "Quran Audio", "Full Quran recitation — in development", Palette.GREEN,
                        badge = "SOON", badgeColor = Palette.AMBER) {
                        startActivity(Intent(this@MainActivity, QuranActivity::class.java))
                    }

                    // ===== SYSTEM =====
                    SectionHeader("⚙️", "System", Palette.TEXT_DIM)
                    GlassCard {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GlowButton("🔄 Check Updates", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f)) {
                                AdrenalineUpdater(this@MainActivity).checkForUpdate()
                            }
                            GlowButton("🔑 Grant Permissions", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.weight(1f)) {
                                permissionLauncher.launch(PermissionManager.missing(this@MainActivity).toTypedArray())
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("MAJOR upgrades = APK • MINOR upgrades = news feed, no reinstall", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                        Spacer(Modifier.height(8.dp))
                        GlowButton("🌙 What's New (minor — no reinstall)", listOf(Palette.AMBER, Color(0xFFFF8F00)), Modifier.fillMaxWidth()) {
                            WhatsNew.check(this@MainActivity)
                        }
                    }

                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(
            text, color = Color.White,
            fontWeight = FontWeight.SemiBold, fontSize = 15.sp
        )
    }

    @Composable
    private fun LazyRowOfEffects(selected: Int, onSelect: (Int) -> Unit) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            EffectCatalog.ALL.forEach { entry ->
                val isSelected = entry.id == selected
                Box(
                    Modifier
                        .border(
                            if (isSelected) 2.dp else 1.dp,
                            if (isSelected) Color(0xFF00E5FF) else Color(0xFF2A3A4A),
                            RoundedCornerShape(10.dp)
                        )
                        .background(
                            if (isSelected) Color(0xFF002233) else Color(0xFF111827),
                            RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelect(entry.id) }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(entry.emoji, fontSize = 22.sp)
                        Text(
                            entry.name,
                            color = if (isSelected) Color(0xFF00E5FF) else Color.Gray,
                            fontSize = 9.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun EmpireButton(label: String, color: Color, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f))
        ) {
            Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}
