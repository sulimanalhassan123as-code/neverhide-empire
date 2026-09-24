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
import android.widget.Toast
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
import com.neverhide.empire.ghost.GhostMode
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
        // EMPIRE PUSH CHANNEL — every installed phone joins the "empire" topic
        // so the server can reach it (incoming Empire Talk calls, Guardian and
        // group alerts) even when the app is fully closed.
        runCatching {
            com.google.firebase.messaging.FirebaseMessaging.getInstance()
                .subscribeToTopic("empire")
        }
        guardianArmed = GuardianAdminReceiver.isAdminActive(this)
        // EMPIRE HEARTBEAT — one privacy-safe ping per app open so the owner
        // can see active users on the admin dashboard (no personal data).
        com.neverhide.empire.core.EmpireTelemetry.ping(this, "app_open")
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
        var ghostOn by remember { mutableStateOf(GhostMode.isEnabled(this)) }
        var guardianTheme by remember {
            mutableStateOf(getSharedPreferences("guardian_prefs", MODE_PRIVATE).getInt(GuardianAdminReceiver.KEY_THEME, 0))
        }
        var selectedEffect by remember {
            mutableStateOf(getSharedPreferences("empire_prefs", MODE_PRIVATE).getInt("wallpaper_effect", 0))
        }
        val permProgress = PermissionManager.progress(this)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val version = packageManager.getPackageInfo(packageName, 0).versionName ?: "?"

        var uiThemeKey by remember { mutableStateOf(getSharedPreferences("empire_prefs", MODE_PRIVATE).getString("empire_theme", "black") ?: "black") }
        val T = NeuThemes.from(uiThemeKey)
        var showThemePopup by remember { mutableStateOf(false) }

        MaterialTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(T.bg)
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(scroll)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)
                ) {
                    // ===== HEADER (neu-flat) =====
                    NeuCard(T, corner = 20.dp, padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Neverhide ", color = T.textDark, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                            Text("Empire", color = T.accent, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.weight(1f))
                            NeuChip(T, "v$version")
                            Spacer(Modifier.width(10.dp))
                            NeuIconButton(T, "🎨") { showThemePopup = !showThemePopup }
                            Spacer(Modifier.width(10.dp))
                            NeuIconButton(T, "🌙") { WhatsNew.check(this@MainActivity) }
                        }
                    }

                    // ===== EMPIRE RING (extruded outer, carved inner) =====
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        NeuRing(
                            T, "EMPIRE",
                            if (guardianArmed) "SECURE" else "SET UP",
                            if (guardianArmed) "Guardian ARMED • everything running" else "Enable the Guardian below"
                        )
                    }

                    // ===== ACTION BAR (accent pill) =====
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(T.accent, RoundedCornerShape(50.dp))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            Modifier
                                .weight(1f)
                                .background(Color.White, RoundedCornerShape(40.dp))
                                .clickable { AdrenalineUpdater(this@MainActivity).checkForUpdate() }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("🔄 Updates", color = T.accent, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                        Box(
                            Modifier
                                .weight(1f)
                                .background(Color.White, RoundedCornerShape(40.dp))
                                .clickable { permissionLauncher.launch(PermissionManager.missing(this@MainActivity).toTypedArray()) }
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center
                        ) { Text("🔑 Permissions", color = T.accent, fontWeight = FontWeight.ExtraBold, fontSize = 13.sp) }
                    }

                    // ===== QUICK ACCESS (grid-4) =====
                    NeuSectionLabel("QUICK ACCESS", T)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        NeuTile(T, "🔐", "Vault", Color(0xFF10B981)) { startActivity(Intent(this@MainActivity, AppVaultActivity::class.java)) }
                        NeuTile(T, "🧹", "Cleaner", Color(0xFFF97316)) { startActivity(Intent(this@MainActivity, CleanerActivity::class.java)) }
                        NeuTile(T, "📡", "Talk", Color(0xFFA855F7)) { startActivity(Intent(this@MainActivity, com.neverhide.empire.talk.EmpireTalkActivity::class.java)) }
                        NeuTile(T, "📞", "Dial", Color(0xFF38BDF8)) { startActivity(Intent(this@MainActivity, QuickDialActivity::class.java)) }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        NeuTile(T, "📸", "Capture", Color(0xFFEF4444)) { launchScreenshot() }
                        NeuTile(T, "🖼️", "Walls", Color(0xFFEC4899)) { setWallpaper(selectedEffect) }
                        NeuTile(T, "🧰", "Tools", Color(0xFF10B981)) { startActivity(Intent(this@MainActivity, ToolsActivity::class.java)) }
                        NeuTile(T, "📖", "Quran", Color(0xFFF97316)) { startActivity(Intent(this@MainActivity, QuranActivity::class.java)) }
                    }

                    // ===== GUARDIAN HERO =====
                    NeuCard(T) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(46.dp)
                                    .neuInset(T, 14.dp)
                                    .background(T.bg, RoundedCornerShape(14.dp)),
                                contentAlignment = Alignment.Center
                            ) { Text("🛡️", fontSize = 21.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Adrenaline Lock Guardian", color = T.textDark, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                                Text(
                                    if (guardianArmed) "ARMED — wrong passwords trigger the jumpscare" else "NOT ARMED — enable below to protect",
                                    color = if (guardianArmed) Color(0xFF10B981) else T.textMuted,
                                    fontSize = 11.sp, fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("🌊 Water", "🔥 Fire", "⚡ Thunder", "🌑 Void").forEachIndexed { i, label ->
                                NeuChip(T, label, active = guardianTheme == i) {
                                    guardianTheme = i
                                    getSharedPreferences("guardian_prefs", MODE_PRIVATE)
                                        .edit().putInt(GuardianAdminReceiver.KEY_THEME, i).apply()
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        var fxGunshot by remember {
                            mutableStateOf(getSharedPreferences("guardian_prefs", MODE_PRIVATE).getInt("guardian_fx_mode", 1) == 1)
                        }
                        var alertNum by remember {
                            mutableStateOf(getSharedPreferences("guardian_prefs", MODE_PRIVATE).getString("guardian_alert_number", "") ?: "")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("💥 Gunshot + cracked screen", color = T.textDark, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Switch(
                                checked = fxGunshot,
                                onCheckedChange = { on ->
                                    fxGunshot = on
                                    getSharedPreferences("guardian_prefs", MODE_PRIVATE).edit()
                                        .putInt("guardian_fx_mode", if (on) 1 else 0).apply()
                                },
                                colors = SwitchDefaults.colors(checkedTrackColor = T.accent)
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = alertNum, onValueChange = { alertNum = it },
                                label = { Text("SMS alert number (optional)", color = T.textMuted) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = T.textDark, unfocusedTextColor = T.textDark,
                                    cursorColor = T.accent, focusedBorderColor = T.accent,
                                    unfocusedBorderColor = T.textMuted,
                                    focusedLabelColor = T.textMuted, unfocusedLabelColor = T.textMuted
                                )
                            )
                            NeuButton(T, "💾", accentText = T.accent) {
                                getSharedPreferences("guardian_prefs", MODE_PRIVATE).edit()
                                    .putString("guardian_alert_number", alertNum.trim()).apply()
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        if (!guardianArmed) {
                            NeuButton(T, "🛡️ Enable Lock Guardian", accentText = T.accent, modifier = Modifier.fillMaxWidth(), corner = 40.dp) {
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
                                NeuButton(T, "⚔️ Test", accentText = T.accent, modifier = Modifier.weight(1f)) {
                                    JumpscareActivity.launch(this@MainActivity, guardianTheme, null)
                                }
                                NeuButton(T, "🔕 Disable", modifier = Modifier.weight(1f)) {
                                    dpm.removeActiveAdmin(GuardianAdminReceiver.adminComponent(this@MainActivity))
                                    guardianArmed = false
                                }
                            }
                        }
                    }

                    // ===== CAPTURE =====
                    NeuSectionLabel("CAPTURE", T)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeuButton(T, "📸 Screenshot", accentText = T.accent, modifier = Modifier.weight(1f)) { launchScreenshot() }
                        NeuButton(T, "🫧 Bubble", accentText = T.accent, modifier = Modifier.weight(1f)) {
                            if (Settings.canDrawOverlays(this@MainActivity)) {
                                startBubbleService()
                            } else {
                                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:$packageName")))
                            }
                        }
                    }

                    // ===== LIVE WALLPAPERS =====
                    NeuSectionLabel("LIVE WALLPAPERS — 20 GPU EFFECTS", T)
                    NeuCard(T) {
                        Text("Water • Fire • Galaxy • Cyber — real OpenGL renders", color = T.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(12.dp))
                        LazyRowOfEffects(selectedEffect, T) { i ->
                            selectedEffect = i
                            getSharedPreferences("empire_prefs", MODE_PRIVATE)
                                .edit().putInt("wallpaper_effect", i).apply()
                        }
                        Spacer(Modifier.height(12.dp))
                        NeuButton(T, "✨ Set Selected as Wallpaper", accentText = T.accent, modifier = Modifier.fillMaxWidth(), corner = 40.dp) {
                            setWallpaper(selectedEffect)
                        }
                    }

                    // ===== EMPIRE STATUS (stats + progress) =====
                    NeuSectionLabel("EMPIRE STATUS", T)
                    NeuCard(T) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (guardianArmed) "ON" else "OFF", color = Color(0xFFEF4444), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Guardian", color = T.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${permProgress.first}/${permProgress.second}", color = Color(0xFF10B981), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Permissions", color = T.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(if (ghostOn) "ON" else "OFF", color = Color(0xFFF97316), fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Ghost", color = T.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .neuInset(T, 10.dp)
                                .height(10.dp)
                                .background(T.bg, RoundedCornerShape(10.dp))
                        ) {
                            val frac = if (permProgress.second == 0) 0f
                                else permProgress.first.toFloat() / permProgress.second
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(frac.coerceIn(0.02f, 1f))
                                    .background(T.accent, RoundedCornerShape(10.dp))
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("MAJOR upgrades = APK • MINOR upgrades = news feed, no reinstall", color = T.textMuted, fontSize = 9.sp)
                    }

                    // ===== GHOST MODE =====
                    NeuSectionLabel("GHOST MODE — CALCULATOR DISGUISE", T)
                    NeuCard(T) {
                        Text(
                            "Disguises the launcher icon as a plain, working Calculator. Everything keeps running — Guardian, Vault, alerts, updates. Return: open the Calculator, type ${GhostMode.SECRET_HOST}, tap =.",
                            color = T.textMuted, fontSize = 11.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        NeuButton(
                            T,
                            if (ghostOn) "👁 Restore Empire icon" else "🧮 Disguise as Calculator",
                            accentText = T.accent,
                            modifier = Modifier.fillMaxWidth(),
                            corner = 40.dp
                        ) {
                            if (ghostOn) {
                                GhostMode.reveal(this@MainActivity)
                                ghostOn = false
                            } else {
                                GhostMode.hide(this@MainActivity)
                                ghostOn = true
                                Toast.makeText(this@MainActivity, "Home screen may take a few seconds to refresh the icon", Toast.LENGTH_LONG).show()
                            }
                        }
                        if (ghostOn) {
                            Spacer(Modifier.height(8.dp))
                            Text("GHOST ACTIVE — icon looks like Calculator. Return: open it, type ${GhostMode.SECRET_HOST}, tap =.", color = T.accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Text("Honest limits: the app still shows in Settings → Apps by its real name (Android rule), and the watchdog notification stays in the shade.", color = T.textMuted, fontSize = 9.sp)
                        }
                    }

                    // ===== MORE EMPIRE =====
                    NeuSectionLabel("MORE EMPIRE", T)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NeuButton(T, "🚀 3D Launcher", accentText = T.accent, modifier = Modifier.weight(1f)) {
                            startActivity(Intent(this@MainActivity, Launcher3DActivity::class.java))
                        }
                        NeuButton(T, "🌙 What's New", accentText = T.accent, modifier = Modifier.weight(1f)) {
                            WhatsNew.check(this@MainActivity)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                }

                // ===== THEME POPUP OVERLAY =====
                if (showThemePopup) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .clickable { showThemePopup = false }
                    )
                    Box(Modifier.align(Alignment.TopEnd).padding(top = 74.dp, end = 16.dp)) {
                        NeuThemePopup(T, uiThemeKey) { key ->
                            uiThemeKey = key
                            getSharedPreferences("empire_prefs", MODE_PRIVATE).edit()
                                .putString("empire_theme", key).apply()
                            showThemePopup = false
                        }
                    }
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
    private fun LazyRowOfEffects(selected: Int, T: NeuTheme, onSelect: (Int) -> Unit) {
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
                        .neuFlat(T, corner = 12.dp, offset = 3.dp, blur = 8.dp)
                        .background(T.bg, RoundedCornerShape(12.dp))
                        .border(
                            if (isSelected) 2.dp else 0.dp,
                            if (isSelected) T.accent else Color.Transparent,
                            RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelect(entry.id) }
                        .padding(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(entry.emoji, fontSize = 22.sp)
                        Text(
                            entry.name,
                            color = if (isSelected) T.accent else T.textMuted,
                            fontSize = 9.sp,
                            maxLines = 1,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold
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
