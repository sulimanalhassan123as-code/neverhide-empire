package com.neverhide.empire.tools.peek

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.core.content.ContextCompat

/**
 * Peek Guard — anti-shoulder-surfing tool (v2.10.0).
 * Peek detect: 2nd face behind you -> instant blackout.
 * Dim shield: dark tint + dim that keeps the screen readable straight-on
 * but hard to read from the side.
 */
@Composable
fun PeekGuardScreen() {
    val context = LocalContext.current
    val P = PeekGuardService
    var peek by remember { mutableStateOf(P.prefs(context).getBoolean("peek", true)) }
    var dim by remember { mutableStateOf(P.prefs(context).getBoolean("dim", false)) }
    var running by remember { mutableStateOf(P.isRunning(context)) }
    var faces by remember { mutableStateOf(0) }
    var black by remember { mutableStateOf(false) }

    // live state from the service (v2.9.7 lesson: collect, don't poll plain vars)
    LaunchedEffect(Unit) {
        P.uiTick.collect {
            faces = P.facesSeen
            black = P.blackout
            running = P.isRunning(context)
        }
    }

    val camLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startGuard(context, peek, dim)
        else android.widget.Toast.makeText(
            context, "Camera permission is required for Peek Guard.",
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("🕵️ Peek Guard", color = Color(0xFF00E5FF), fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Protects your screen from people standing behind you.",
            color = Color.Gray, fontSize = 13.sp
        )

        // live status card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111926)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (running) "● GUARDING" else "○ idle",
                    color = if (running) Color(0xFF69F0AE) else Color.Gray,
                    fontWeight = FontWeight.Bold
                )
                if (running) {
                    Text("Faces visible: $faces ${if (black) "— BLACKED OUT 🕶️" else ""}",
                        color = if (black) Color(0xFFFF5252) else Color.White, fontSize = 14.sp)
                    Text("Sampling every 1.5s · camera capped at 480p", color = Color.Gray, fontSize = 11.sp)
                }
            }
        }

        // toggle: peek detect
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111926)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("👀 Peek detect", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("2nd face appears behind you → screen goes black instantly, clears when they leave",
                            color = Color.Gray, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = peek, onCheckedChange = { peek = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00E5FF)))
                }
            }
        }

        // toggle: dim shield
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111926)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🌗 Dim shield", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text("Dark tint stays on — readable straight-on, hard to read from an angle",
                            color = Color.Gray, fontSize = 11.sp)
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(checked = dim, onCheckedChange = { dim = it },
                        colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFFFFC400)))
                }
            }
        }

        // start / stop
        if (!running) {
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
                        camLauncher.launch(Manifest.permission.CAMERA)
                    } else startGuard(context, peek, dim)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("🛡️ START PEEK GUARD", color = Color(0xFF001018), fontWeight = FontWeight.Bold) }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        val i = android.content.Intent(context, PeekGuardService::class.java)
                            .setAction(PeekGuardService.ACTION_TEST)
                        context.startService(i)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF616161)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("Test blackout 4s", color = Color.White) }
                Button(
                    onClick = { stopGuard(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                    modifier = Modifier.weight(1f).height(48.dp)
                ) { Text("⏹ STOP", color = Color.White) }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF161616)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Honest limits", color = Color(0xFFFFC400), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(
                    "• Android forces a small notification while the guard runs — it can't be hidden without root\n" +
                    "• A face far in the background may be too small for the camera to count\n" +
                    "• Camera costs battery: capped at 480p, 1 frame analyzed per 1.5s\n" +
                    "• A hardware privacy-filter protector still beats software for side-angle blocking",
                    color = Color.Gray, fontSize = 11.sp, lineHeight = 16.sp
                )
            }
        }
    }
}

private fun startGuard(context: android.content.Context, peek: Boolean, dim: Boolean) {
    val i = android.content.Intent(context, PeekGuardService::class.java)
        .setAction(PeekGuardService.ACTION_START)
        .putExtra("peek", peek)
        .putExtra("dim", dim)
    context.startForegroundService(i)
}

private fun stopGuard(context: android.content.Context) {
    context.startService(
        android.content.Intent(context, PeekGuardService::class.java)
            .setAction(PeekGuardService.ACTION_STOP)
    )
}
