package com.neverhide.empire.tools.gamepad

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.cleaner.FreezerEngine

/**
 * DLS Pad settings — start the floating pad, calibrate where the GAME's own
 * controls sit, toggle Game Mode (freeze background apps for stable play).
 */
@Composable
fun GamePadScreen() {
    val context = LocalContext.current
    val P = GamePadOverlayService
    val prefs = remember { P.prefs(context) }
    var running by remember { mutableStateOf(P.isRunning(context)) }
    var gameMode by remember { mutableStateOf(prefs.getBoolean("gameMode", false)) }
    var overlayOk by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var accOk by remember { mutableStateOf(GamePadAccessibilityService.ready()) }
    var shizuku by remember { mutableStateOf(FreezerEngine.powerMode(context)) }

    // live refresh of the two permission states
    LaunchedEffect(Unit) {
        while (true) {
            overlayOk = Settings.canDrawOverlays(context)
            accOk = GamePadAccessibilityService.ready()
            running = P.isRunning(context)
            shizuku = FreezerEngine.powerMode(context)
            kotlinx.coroutines.delay(1500)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🎮 DLS Pad", color = Color(0xFF00E5FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Football controller overlay + live-match network stabilizer", color = Color.Gray, fontSize = 13.sp)

        // permission cards
        PermCard(
            "1. Display over other apps",
            overlayOk,
            if (overlayOk) "GRANTED" else "Tap to grant"
        ) { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + context.packageName))) }
        PermCard(
            "2. Accessibility (touch injection)",
            accOk,
            if (accOk) "GRANTED" else "Tap → find Neverhide GamePad → ON"
        ) {
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        // game mode
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111926)),
            shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("📡 Game Mode — stable live matches", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Freezes all background apps during play so nothing competes for CPU/RAM/network. They're restored when you stop.",
                            color = Color.Gray, fontSize = 11.sp)
                    }
                    Switch(checked = gameMode, onCheckedChange = {
                        gameMode = it
                        prefs.edit().putBoolean("gameMode", it).apply()
                    }, colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFFFD600)))
                }
                Text("Shizuku power: $shizuku", color = if (shizuku == "OFF") Color(0xFFFF5252) else Color(0xFF69F0AE), fontSize = 11.sp)
                if (shizuku == "OFF") Text("Game Mode needs Shizuku running — start it from its app.", color = Color(0xFFFFC400), fontSize = 11.sp)
            }
        }

        // calibration
        Text("🎯 Calibrate — where the GAME's own controls sit", color = Color(0xFF00E5FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text("Defaults fit DLS on most phones. Slide to match YOUR screen if the pad presses the wrong spots.", color = Color.Gray, fontSize = 11.sp)
        val items = listOf(
            "Run circle (joy)" to listOf("gameJoyX" to 0.13f, "gameJoyY" to 0.86f),
            "A (shoot)" to listOf("gameAX" to 0.80f, "gameAY" to 0.66f),
            "B (sprint)" to listOf("gameBX" to 0.88f, "gameBY" to 0.78f),
            "C (pass)" to listOf("gameCX" to 0.71f, "gameCY" to 0.76f)
        )
        for ((label, keys) in items) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111926)),
                shape = RoundedCornerShape(10.dp)) {
                Column(Modifier.padding(10.dp)) {
                    Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    for ((key, def) in keys) {
                        val axis = if (key.endsWith("X")) "↔ X" else "↕ Y"
                        Text(axis, color = Color.Gray, fontSize = 10.sp)
                        val v = prefs.getFloat(key, def)
                        Slider(
                            value = v, onValueChange = {
                                prefs.edit().putFloat(key, it).apply()
                            }, valueRange = 0.05f..0.95f,
                            colors = SliderDefaults.colors(thumbColor = Color(0xFF00E5FF), activeTrackColor = Color(0xFF00E5FF))
                        )
                    }
                }
            }
        }

        // start / stop
        if (!running) {
            Button(
                onClick = {
                    val i = Intent(context, GamePadOverlayService::class.java)
                        .setAction(GamePadOverlayService.ACTION_START)
                    context.startForegroundService(i)
                    running = true
                },
                enabled = overlayOk,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (overlayOk) "▶ START PAD" else "Grant overlay permission first", color = Color(0xFF001018), fontWeight = FontWeight.Bold) }
        } else {
            Button(
                onClick = {
                    context.startService(
                        Intent(context, GamePadOverlayService::class.java)
                            .setAction(GamePadOverlayService.ACTION_STOP)
                    )
                    running = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("⏹ STOP PAD (+ unfreeze apps)", color = Color.White, fontWeight = FontWeight.Bold) }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Honest limits", color = Color(0xFFFFC400), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "• Touch injection uses Android Accessibility — enable it in Settings, that's the only non-root way\n" +
                    "• Game Mode reduces lag by removing background load; it cannot prioritize packets like root QoS\n" +
                    "• If DLS lags on mobile data, Wi-Fi still beats everything\n" +
                    "• The pad floats over the game; hide it from the notification's Stop button",
                    color = Color.Gray, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }
    }
}

@Composable
private fun PermCard(title: String, ok: Boolean, cta: String, onClick: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = if (ok) Color(0xFF0D2818) else Color(0xFF2A1119)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(if (ok) "✅ $cta" else "⚠️ $cta", color = if (ok) Color(0xFF69F0AE) else Color(0xFFFFC400), fontSize = 11.sp)
            }
            if (!ok) TextButton(onClick = onClick) { Text("OPEN", color = Color(0xFF00E5FF)) }
        }
    }
}
