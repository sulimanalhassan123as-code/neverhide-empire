package com.neverhide.empire.calls

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.dashboard.Palette

/**
 * CALLS SECTION — everything phone-call related lives in this one folder.
 * Fixing or extending calls never touches any other part of the app.
 */
class QuickDialActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE), 7)
        }
        setContent { CallsScreen() }
    }

    @Composable
    private fun CallsScreen() {
        val context = LocalContext.current
        var number by remember { mutableStateOf("") }
        var recent by remember { mutableStateOf(listOf<Pair<String, String>>()) }

        // Load last 12 call-log entries (number, type label)
        LaunchedEffect(Unit) {
            recent = runCatching {
                val list = mutableListOf<Pair<String, String>>()
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                    null, null, "${CallLog.Calls.DATE} DESC"
                )?.use { c ->
                    while (c.moveToNext() && list.size < 12) {
                        val type = when (c.getInt(1)) {
                            CallLog.Calls.INCOMING_TYPE -> "📥 Incoming"
                            CallLog.Calls.OUTGOING_TYPE -> "📤 Outgoing"
                            CallLog.Calls.MISSED_TYPE -> "❌ Missed"
                            else -> "📞"
                        }
                        list.add(c.getString(0) to type)
                    }
                }
                list
            }.getOrDefault(emptyList())
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.pageBg)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("📞 Quick Dial", color = Palette.CYAN, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("Calls section — dial, redial, call log", color = Palette.TEXT_DIM, fontSize = 12.sp)

            com.neverhide.empire.dashboard.FeatureCard(
                "🎙️", "Voice Studio", "Robot & alien voices with real DSP",
                Palette.PURPLE, badge = "NEW"
            ) {
                startActivity(Intent(this@QuickDialActivity, VoiceStudioActivity::class.java))
            }

            OutlinedTextField(
                value = number, onValueChange = { number = it },
                label = { Text("Phone number") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (number.isNotBlank()) runCatching {
                            context.startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Palette.CYAN)
                ) { Text("📞 Call", color = Color(0xFF0A0A1A), fontWeight = FontWeight.Bold) }
                OutlinedButton(
                    onClick = {
                        if (number.isNotBlank()) runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("🙂 Dial") }
            }

            Text("🕘 Recent calls", color = Palette.WHITE, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            if (recent.isEmpty()) {
                Text("No call log access yet — grant permission and reopen.",
                    color = Palette.TEXT_MUTE, fontSize = 12.sp)
            }
            recent.forEach { (num, type) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Palette.CARD, RoundedCornerShape(12.dp))
                        .clickable { number = num }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(num, color = Palette.WHITE, fontSize = 14.sp)
                        Text(type, color = Palette.TEXT_DIM, fontSize = 11.sp)
                    }
                    StatusChipText("Tap to fill")
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    @Composable
    private fun StatusChipText(text: String) {
        Text(text, color = Palette.TEXT_MUTE, fontSize = 10.sp)
    }
}
