package com.neverhide.empire.tools.split

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
    val prefs = remember { context.getSharedPreferences("empire_prefs", android.content.Context.MODE_PRIVATE) }

    // ---- ONE-TAP SYSTEM-WIDE BUBBLE STATE ----
    var bubbleOn by remember { mutableStateOf(SplitBubbleService.isRunning(context)) }
    var pkgA by remember { mutableStateOf(prefs.getString("split_pkg_a", SplitBubbleService.DEFAULT_A) ?: SplitBubbleService.DEFAULT_A) }
    var pkgB by remember { mutableStateOf(prefs.getString("split_pkg_b", SplitBubbleService.DEFAULT_B) ?: SplitBubbleService.DEFAULT_B) }
    // null = tapping an app launches it in split; "a"/"b" = tapping an app assigns that slot
    var assign by remember { mutableStateOf<String?>(null) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) bubbleOn = SplitBubbleService.isRunning(context)
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

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

        // ================= ONE-TAP SYSTEM-WIDE BUBBLE =================
        Text("⬒ ONE-TAP BUBBLE (WORKS IN ANY APP)", color = Color(0xFF00BFA5), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth()
                .background(Color(0xFF111827), RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Floating split bubble", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (bubbleOn) "Tap it anywhere to split A + B" else "Off — turn on to split from any app",
                    color = Color.Gray, fontSize = 11.sp
                )
            }
            Switch(
                checked = bubbleOn,
                onCheckedChange = { on ->
                    if (on && !Settings.canDrawOverlays(context)) {
                        Toast.makeText(context, "Allow display over other apps first", Toast.LENGTH_LONG).show()
                        bubbleOn = false
                    } else {
                        bubbleOn = on
                        prefs.edit().putBoolean("split_bubble_enabled", on).apply()
                        if (on) SplitBubbleService.start(context) else SplitBubbleService.stop(context)
                    }
                },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00BFA5))
            )
        }
        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PairSlot("A", appLabel(context, pkgA), Color(0xFF40C4FF), assign == "a", Modifier.weight(1f)) { assign = "a" }
            PairSlot("B", appLabel(context, pkgB), Color(0xFFFFD600), assign == "b", Modifier.weight(1f)) { assign = "b" }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when (assign) {
                "a" -> "Now tap any app below to make it pane A"
                "b" -> "Now tap any app below to make it pane B"
                else -> "Tap Set A or Set B, then pick the app. Bubble splits them in one tap."
            },
            color = if (assign != null) Color(0xFF00E5FF) else Color.Gray, fontSize = 11.sp
        )
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
                        .clickable {
                            when (assign) {
                                "a" -> { pkgA = app.packageName; prefs.edit().putString("split_pkg_a", app.packageName).apply(); assign = null }
                                "b" -> { pkgB = app.packageName; prefs.edit().putString("split_pkg_b", app.packageName).apply(); assign = null }
                                else -> launchAdjacent(context, app.packageName)
                            }
                        }
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


@Composable
private fun PairSlot(tag: String, label: String, color: Color, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .background(color.copy(alpha = if (active) 0.25f else 0.10f), RoundedCornerShape(10.dp))
            .border(1.dp, if (active) color else color.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(tag, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, fontSize = 12.sp, maxLines = 1)
    }
}

private fun appLabel(context: Context, pkg: String): String = try {
    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
} catch (e: Exception) { pkg }
