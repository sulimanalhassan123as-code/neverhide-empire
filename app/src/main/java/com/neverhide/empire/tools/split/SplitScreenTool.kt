package com.neverhide.empire.tools.split

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SPLIT SCREEN LAUNCHER
 *
 * THE WHY (2026-10-08): the owner fights wireless-debugging pairing because the
 * 6-digit popup only broadcasts while Settings is in the FOREGROUND — switching
 * to Termux kills it. Split screen keeps Settings (popup open) and Termux
 * visible at the SAME TIME.
 *
 * THE HOW: Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT (API 24+) asks Android to open
 * the chosen app in the OTHER split-screen pane next to this one. Samsung
 * One UI supports it. If split screen isn't possible the launch falls back to
 * a normal full-screen open — never crashes.
 *
 * HONEST LIMIT: Android gives apps NO API to force two OTHER apps into split
 * screen. The native way (Recents → tap app icon → Open in split screen view)
 * is also documented in the tips card.
 */

@Composable
fun SplitScreenScreen() {
    val context = LocalContext.current
    var search by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF0A0A1A), Color(0xFF0D1B2A))))
            .padding(16.dp)
    ) {
        Text(".SplitContainer — two apps, one screen", color = Color(0xFF00E5FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        TipCard("Tap an app below and it opens in the OTHER pane, next to this one. Perfect for Termux + Settings at the same time — the ADB pairing popup stays alive while you type.", Color(0xFF00E5FF))
        Spacer(Modifier.height(10.dp))

        // Quick pairs for the ADB/Shizuku pairing fight
        Text("⚡ QUICK LAUNCH", color = Color(0xFF7C4DFF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickLaunch("⌨️ Termux", Color(0xFF00BFA5)) { launchAdjacent(context, "com.termux") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickLaunch("⚙️ Settings", Color(0xFF40C4FF)) { launchAdjacent(context, null) }
        }
        Spacer(Modifier.height(14.dp))

        TipCard("TERMUX PAIRING RECIPE: 1) Launch ⚙️ Settings in split (button above). 2) In the Settings pane open Developer options → Wireless debugging → Pair device. 3) Switch THIS pane to Termux (Recents → Termux). 4) adb pair IP:PAIR_PORT with the code shown. The popup never closes because Settings never leaves the screen.", Color(0xFFFFD600))
        Spacer(Modifier.height(14.dp))

        Text("📱 ALL APPS", color = Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = search, onValueChange = { search = it },
            placeholder = { Text("Search apps…", color = Color.Gray) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White, unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF00E5FF), cursorColor = Color(0xFF00E5FF)
            )
        )
        Spacer(Modifier.height(8.dp))

        val apps = remember(search) { loadLaunchableApps(context, search) }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(apps) { app ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0xFF1E2A3A), RoundedCornerShape(10.dp))
                        .clickable { launchAdjacent(context, app.packageName) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(app.label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text("open in split ↗", color = Color(0xFF00E5FF), fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun QuickLaunch(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.18f), contentColor = color),
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun TipCard(text: String, color: Color) {
    Text(
        text, color = color, fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
            .padding(10.dp)
    )
}

private data class AppEntry(val label: String, val packageName: String)

private fun loadLaunchableApps(context: Context, search: String): List<AppEntry> {
    val pm = context.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    val out = ArrayList<AppEntry>()
    try {
        val infos = pm.queryIntentActivities(main, 0)
        for (info in infos) {
            val label = try { info.loadLabel(pm).toString() } catch (e: Exception) { continue }
            out.add(AppEntry(label, info.activityInfo.packageName))
        }
    } catch (e: Exception) {}
    val q = search.trim().lowercase()
    return out
        .filter { if (q.isEmpty()) true else it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        .distinctBy { it.packageName }
        .sortedWith(compareByDescending<AppEntry> { it.label.length < 18 }.thenBy { it.label.lowercase() })
        .take(60)
}

private fun launchAdjacent(context: Context, packageName: String?) {
    try {
        val pm = context.packageManager
        val intent = if (packageName == null) {
            Intent(android.provider.Settings.ACTION_SETTINGS)
        } else {
            pm.getLaunchIntentForPackage(packageName)
                ?: run {
                    Toast.makeText(context, "$packageName is not installed", Toast.LENGTH_SHORT).show()
                    return
                }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The magic: "open me in the OTHER split-screen pane"
        intent.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
        context.startActivity(intent)
        Toast.makeText(context, "Launched in split screen ↗", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        // Some builds refuse ADJACENT outside split mode — open normally instead
        try {
            val fallback = if (packageName == null) Intent(android.provider.Settings.ACTION_SETTINGS)
            else context.packageManager.getLaunchIntentForPackage(packageName)
            fallback?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (fallback != null) { context.startActivity(fallback); Toast.makeText(context, "Opened full screen (device refused split)", Toast.LENGTH_SHORT).show() }
        } catch (e2: Exception) {
            Toast.makeText(context, "Could not launch: ${e2.message}", Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Could not launch: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
