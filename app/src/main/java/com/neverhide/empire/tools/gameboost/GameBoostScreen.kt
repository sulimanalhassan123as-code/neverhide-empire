package com.neverhide.empire.tools.gameboost

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
 * DLS LIVE BOOST — all CPU/RAM/network to the live match.
 * Auto-freezes background apps when the game is in front, auto-restores
 * the moment the game is left. (The old gamepad overlay was removed.)
 */
@Composable
fun GameBoostScreen() {
    val context = LocalContext.current
    val S = GameBoostService
    val prefs = remember { S.prefs(context) }
    var running by remember { mutableStateOf(S.isRunning(context)) }
    var boosting by remember { mutableStateOf(S.isBoosting(context)) }
    var frozenCount by remember { mutableStateOf(S.boostedCount(context)) }
    var shizuku by remember { mutableStateOf(FreezerEngine.powerMode(context)) }
    var gamePkg by remember { mutableStateOf(S.targetGame(context)) }
    var pickMode by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            running = S.isRunning(context)
            boosting = S.isBoosting(context)
            frozenCount = S.boostedCount(context)
            shizuku = FreezerEngine.powerMode(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    fun usageGranted(): Boolean {
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(), context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) { false }
    }
    var usageOk by remember { mutableStateOf(usageGranted()) }
    LaunchedEffect(Unit) { while (true) { usageOk = usageGranted(); kotlinx.coroutines.delay(2000) } }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("📡 DLS Live Boost", color = Color(0xFF00E5FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("The gamepad is gone. This is pure match power: when DLS is on screen, every background app freezes so the live match gets all CPU, RAM and network. Open/leave the game anytime — restore is automatic.", color = Color.Gray, fontSize = 12.sp)

        // live status
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111926)),
            shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        boosting -> "🔴 LIVE — boosting match"
                        running -> "🟡 Watching for the game…"
                        else -> "⚪ Idle"
                    },
                    color = when {
                        boosting -> Color(0xFFFF5252)
                        running -> Color(0xFFFFD600)
                        else -> Color.Gray
                    }, fontWeight = FontWeight.Bold, fontSize = 16.sp
                )
                if (boosting) Text("❄ $frozenCount background apps frozen — full power to DLS", color = Color.White, fontSize = 12.sp)
                Text("Shizuku: $shizuku", color = if (shizuku == "OFF") Color(0xFFFF5252) else Color(0xFF69F0AE), fontSize = 11.sp)
            }
        }

        // permissions
        Card(colors = CardDefaults.cardColors(containerColor = if (usageOk) Color(0xFF0D2818) else Color(0xFF2A1119)),
            shape = RoundedCornerShape(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("1. Usage Access (detect the game)", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (usageOk) "✅ GRANTED" else "⚠️ Needed to know when the game opens/closes", color = if (usageOk) Color(0xFF69F0AE) else Color(0xFFFFC400), fontSize = 11.sp)
                }
                if (!usageOk) TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
                }) { Text("OPEN", color = Color(0xFF00E5FF)) }
            }
        }

        // game picker
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF111926)),
            shape = RoundedCornerShape(14.dp)) {
            Column(Modifier.padding(14.dp)) {
                Text("🎮 Target game", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val pm = remember { context.packageManager }
                val label = remember(gamePkg) {
                    runCatching { pm.getApplicationLabel(pm.getApplicationInfo(gamePkg, 0)).toString() }
                        .getOrElse { gamePkg }
                }
                Text("Selected: $label", color = Color(0xFF00E5FF), fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                if (!pickMode) {
                    TextButton(onClick = { pickMode = true }) { Text("Choose app →", color = Color(0xFF00E5FF)) }
                } else {
                    val apps = remember {
                        FreezerEngine.listFreezableApps(context)
                            .map { it.packageName to it.loadLabel(pm).toString() }
                            .sortedBy { it.second.lowercase() }
                    }
                    LazyColumn(Modifier.height(220.dp)) {
                        items(apps) { (pkg, name) ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .background(
                                        if (pkg == gamePkg) Color(0xFF0D2818) else Color.Transparent,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable {
                                        gamePkg = pkg
                                        prefs.edit().putString("game", pkg).apply()
                                        pickMode = false
                                    }
                                    .padding(10.dp)
                            ) {
                                Text(name, color = if (pkg == gamePkg) Color(0xFF69F0AE) else Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                    TextButton(onClick = { pickMode = false }) { Text("Close list", color = Color.Gray) }
                }
            }
        }

        // start / stop
        if (!running) {
            Button(
                onClick = {
                    context.startForegroundService(
                        Intent(context, GameBoostService::class.java)
                            .setAction(GameBoostService.ACTION_START)
                    )
                    running = true
                },
                enabled = usageOk,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text(if (usageOk) "▶ START LIVE BOOST" else "Grant Usage Access first", color = Color(0xFF001018), fontWeight = FontWeight.Bold) }
        } else {
            Button(
                onClick = {
                    context.startService(
                        Intent(context, GameBoostService::class.java)
                            .setAction(GameBoostService.ACTION_STOP)
                    )
                    running = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("⏹ STOP & RESTORE ALL APPS", color = Color.White, fontWeight = FontWeight.Bold) }
        }

        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("No-stuck guarantees", color = Color(0xFF69F0AE), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "• Only apps IT froze are restored — apps you froze yourself stay frozen\n" +
                    "• On start, any leftovers from a crashed session are released first\n" +
                    "• Game-left is double-checked before restoring (no flicker)\n" +
                    "• Keyboard, launcher, system UI, the game and Empire are never frozen\n" +
                    "• If Shizuku dies, everything restores and the boost stops",
                    color = Color.Gray, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }
    }
}
