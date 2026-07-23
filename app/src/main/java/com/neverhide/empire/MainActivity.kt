package com.neverhide.empire

import android.Manifest
import android.app.WallpaperManager
import android.content.ComponentName
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.core.PermissionManager
import com.neverhide.empire.core.EmpireBackgroundService
import com.neverhide.empire.launcher.Launcher3DActivity
import com.neverhide.empire.screenshot.ScreenshotService
import com.neverhide.empire.screenshot.FloatingBubbleService
import com.neverhide.empire.updater.AdrenalineUpdater
import com.neverhide.empire.wallpaper.LiveWallpaperEngine

class MainActivity : ComponentActivity() {

    private val runtimePermissions: Array<String> by lazy {
        buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.READ_MEDIA_IMAGES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= 28) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { EmpireHub() }

        // Start background service immediately
        EmpireBackgroundService.start(this)

        // Request battery optimization exemption — keeps service alive during Doze
        requestBatteryOptimizationExemption()

        if (!PermissionManager.hasAskedOnce(this)) {
            permissionLauncher.launch(runtimePermissions)
            PermissionManager.markAsked(this)
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
        if (Build.VERSION.SDK_INT >= 23) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
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
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                putExtra(
                    WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                    ComponentName(packageName, LiveWallpaperEngine::class.java.name)
                )
            }
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
        }
    }

    @Composable
    private fun EmpireHub() {
        val darkBg = Color(0xFF0A0A1A)
        val cyan = Color(0xFF00E5FF)
        val purple = Color(0xFF7C4DFF)
        val pink = Color(0xFFFF4081)
        val orange = Color(0xFFFF6D00)

        var selectedEffect by remember { mutableIntStateOf(0) }
        val effects = listOf("🔥 Fire", "🌊 Water", "⚡ Thunder", "🌌 Galaxy")

        MaterialTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(darkBg, Color(0xFF0D1B2A))))
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("👑 Neverhide Empire", color = cyan, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("v1.2.0 • All-in-one power suite", color = Color.Gray, fontSize = 12.sp)

                    Spacer(Modifier.height(8.dp))

                    EmpireButton("📸 Take Screenshot", cyan) { launchScreenshot() }

                    EmpireButton("🫧 Toggle Floating Bubble", purple) {
                        if (Settings.canDrawOverlays(this@MainActivity)) {
                            startBubbleService()
                        } else {
                            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        }
                    }

                    EmpireButton("🚀 Open 3D App Launcher", cyan) {
                        startActivity(Intent(this@MainActivity, Launcher3DActivity::class.java))
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = Color(0xFF1E2A3A))
                    Spacer(Modifier.height(4.dp))

                    Text("🎨 Live Wallpaper", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        effects.forEachIndexed { i, label ->
                            val selected = selectedEffect == i
                            Box(
                                Modifier
                                    .weight(1f)
                                    .border(
                                        width = if (selected) 2.dp else 1.dp,
                                        color = if (selected) cyan else Color(0xFF2A3A4A),
                                        shape = RoundedCornerShape(10.dp)
                                    )
                                    .background(
                                        if (selected) Color(0xFF002233) else Color(0xFF111827),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .clickable { selectedEffect = i }
                                    .padding(vertical = 10.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    label,
                                    color = if (selected) cyan else Color.Gray,
                                    fontSize = 11.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }

                    EmpireButton("🖼️ Set as Live Wallpaper", pink) {
                        setWallpaper(selectedEffect)
                    }

                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider(color = Color(0xFF1E2A3A))
                    Spacer(Modifier.height(4.dp))

                    // Guardian teaser
                    EmpireButton("🛡️ Adrenaline Lock Guardian (Coming Soon)", orange) {
                        // Guardian feature — will be implemented next phase
                    }

                    EmpireButton("🔄 Check for Updates", Color(0xFF37474F)) {
                        AdrenalineUpdater(this@MainActivity).checkForUpdate()
                    }
                }
            }
        }
    }

    @Composable
    private fun EmpireButton(label: String, color: Color, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.15f)),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.6f))
        ) {
            Text(label, color = color, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}
