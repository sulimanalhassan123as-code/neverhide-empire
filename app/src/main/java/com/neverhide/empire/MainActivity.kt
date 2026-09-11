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
import com.neverhide.empire.core.EmpireBackgroundService
import com.neverhide.empire.core.PermissionManager
import com.neverhide.empire.guardian.GuardianAdminReceiver
import com.neverhide.empire.guardian.JumpscareActivity
import com.neverhide.empire.launcher.Launcher3DActivity
import com.neverhide.empire.screenshot.FloatingBubbleService
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
        val darkBg = Color(0xFF0A0A1A)
        val cyan = Color(0xFF00E5FF)
        val purple = Color(0xFF7C4DFF)
        val pink = Color(0xFFFF4081)
        val orange = Color(0xFFFF6D00)
        val green = Color(0xFF69F0AE)

        val scroll = rememberScrollState()
        var guardianTheme by remember {
            mutableStateOf(getSharedPreferences("guardian_prefs", MODE_PRIVATE).getInt(GuardianAdminReceiver.KEY_THEME, 0))
        }
        var selectedEffect by remember {
            mutableStateOf(getSharedPreferences("empire_prefs", MODE_PRIVATE).getInt("wallpaper_effect", 0))
        }
        val permProgress = PermissionManager.progress(this)

        MaterialTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(darkBg, Color(0xFF0D1B2A))))
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // ===== Header =====
                    Text("👑 Neverhide Empire", color = cyan, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
                    Text("v$version • Power Suite • ${permProgress.first}/${permProgress.second} permissions",
                        color = Color.Gray, fontSize = 12.sp)

                    Spacer(Modifier.height(4.dp))

                    // ===== Capture =====
                    SectionTitle("📸 Capture")
                    EmpireButton("Take Screenshot", cyan) { launchScreenshot() }
                    EmpireButton("Toggle Floating Bubble", purple) {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            startBubbleService()
                        } else {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")))
                        }
                    }

                    // ===== Guardian =====
                    SectionTitle("🛡️ Adrenaline Lock Guardian")
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    val adminStatus = if (GuardianAdminReceiver.isAdminActive(this@MainActivity))
                        "ACTIVE — wrong passwords will trigger the jumpscare" else "NOT ARMED — tap Enable"
                    Text(adminStatus, color = if (guardianArmed) green else Color.Gray, fontSize = 12.sp)
                    if (!guardianArmed) {
                        EmpireButton("🛡️ Enable Lock Guardian", orange) {
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
                        EmpireButton("⚔️ Test Jumpscare NOW", orange) {
                            JumpscareActivity.launch(this@MainActivity, guardianTheme, null)
                        }
                        EmpireButton("🔕 Disable Guardian", Color(0xFF37474F)) {
                            dpm.removeActiveAdmin(GuardianAdminReceiver.adminComponent(this@MainActivity))
                            guardianArmed = false
                        }
                    }
                    // Guardian theme picker
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
                                        if (selected) orange else Color(0xFF2A3A4A),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .background(
                                        if (selected) Color(0xFF332000) else Color(0xFF111827),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable {
                                        guardianTheme = i
                                        getSharedPreferences("guardian_prefs", MODE_PRIVATE)
                                            .edit().putInt(GuardianAdminReceiver.KEY_THEME, i).apply()
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(label, color = if (selected) orange else Color.Gray, fontSize = 12.sp)
                            }
                        }
                    }

                    // ===== Wallpaper =====
                    SectionTitle("🖼️ 3D Live Wallpapers — 20 effects")
                    LazyRowOfEffects(selectedEffect) { i ->
                        selectedEffect = i
                        getSharedPreferences("empire_prefs", MODE_PRIVATE)
                            .edit().putInt("wallpaper_effect", i).apply()
                    }
                    EmpireButton("Set Selected as Wallpaper", pink) { setWallpaper(selectedEffect) }

                    // ===== Toolkit =====
                    SectionTitle("🧰 Power Toolkit")
                    EmpireButton("Open 16 Power Tools", green) {
                        startActivity(Intent(this@MainActivity, ToolsActivity::class.java))
                    }

                    // ===== Launcher =====
                    SectionTitle("🚀 3D Launcher")
                    EmpireButton("Open 3D App Launcher", cyan) {
                        startActivity(Intent(this@MainActivity, Launcher3DActivity::class.java))
                    }

                    // ===== System =====
                    SectionTitle("⚙️ System")
                    EmpireButton("🔄 Check for Updates", Color(0xFF37474F)) {
                        AdrenalineUpdater(this@MainActivity).checkForUpdate()
                    }
                    EmpireButton("🔑 Grant Missing Permissions (${permProgress.second - permProgress.first} left)", Color(0xFF37474F)) {
                        permissionLauncher.launch(PermissionManager.missing(this@MainActivity).toTypedArray())
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
