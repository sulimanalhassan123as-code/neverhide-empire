package com.neverhide.empire.calls

import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.dashboard.GlassCard
import com.neverhide.empire.dashboard.GlowButton
import com.neverhide.empire.dashboard.Palette
import com.neverhide.empire.dashboard.SectionHeader
import com.neverhide.empire.dashboard.StatusChip
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * CALLS SECTION — Call Recorder hub.
 *
 * Start recording any time (ideally during an active call — it auto
 * detects the call state), browse saved recordings, play them back,
 * share, or delete. Records via speakerphone mic capture — the method
 * that works on Android 10+ without root.
 */
class CallRecordingsActivity : ComponentActivity() {

    private var player: MediaPlayer? = null
    private var playingFile by mutableStateOf<File?>(null)

    private data class Rec(val file: File, val number: String, val stamp: String, val sizeKb: Long)

    private fun parseName(f: File): Rec {
        // format: <number>_<yyyyMMdd_HHmmss>.wav
        val base = f.nameWithoutExtension   // format: <number>_<yyyyMMdd_HHmmss>
        val first = base.indexOf('_')
        val number = if (first > 0) base.substring(0, first) else "unknown"
        val stamp = if (first > 0) base.substring(first + 1) else "0"
        return Rec(f, number, stamp, f.length() / 1024)
    }

    private fun listRecs(): List<Rec> =
        CallRecorderService.dir(this).listFiles { f -> f.name.endsWith(".wav") }
            ?.map { parseName(it) }?.sortedByDescending { it.file.name } ?: emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { RecScreen() }
    }

    override fun onDestroy() {
        player?.release(); player = null
        super.onDestroy()
    }

    private fun togglePlay(f: File) {
        if (playingFile == f && player?.isPlaying == true) {
            player?.pause()
            playingFile = null
            return
        }
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            prepare()
            start()
            setOnCompletionListener { playingFile = null }
        }
        playingFile = f
    }

    @Composable
    private fun RecScreen() {
        var recs by remember { mutableStateOf(listRecs()) }
        var recording by remember { mutableStateOf(CallRecorderService.isRecording) }
        var refresh by remember { mutableStateOf(0) }

        // Observe recorder state changes
        LaunchedEffect(Unit) {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    recording = CallRecorderService.isRecording
                    recs = listRecs()
                    refresh++
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this@CallRecordingsActivity, receiver,
                android.content.IntentFilter(CallRecorderService.STATE_CHANGED),
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }

        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.pageBg)
                .padding(20.dp)
        ) {
            Text("⏺ Call Recorder", color = Palette.PINK, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            StatusChip("Works on Android 10+ — no root needed", Palette.GREEN)
            Spacer(Modifier.height(12.dp))

            GlassCard(glow = Palette.PINK) {
                Text(
                    "HOW IT WORKS: Android blocks apps from the raw call stream — so we do what pro recorders do: " +
                            "speaker goes ON, mic records both sides. Start it during a call and it auto-saves when you hang up.\n\n" +
                            "⚖️ Know your local law: in many countries you must tell the other party the call is recorded.",
                    color = Palette.TEXT_DIM, fontSize = 12.sp
                )
                Spacer(Modifier.height(12.dp))
                if (recording) {
                    Text("● RECORDING — hang up to auto-save", color = Palette.PINK, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    GlowButton("⏹ Stop & save now", listOf(Palette.PINK, Color(0xFFC2185B)), Modifier.fillMaxWidth()) {
                        CallRecorderService.stop(this@CallRecordingsActivity)
                    }
                } else {
                    val inCall = CallRecorderService.lastPhoneState != android.telephony.TelephonyManager.CALL_STATE_IDLE
                    GlowButton(
                        if (inCall) "⏺ START RECORDING THIS CALL" else "⏺ Start recording (before or during a call)",
                        listOf(Palette.GREEN, Color(0xFF00E676)), Modifier.fillMaxWidth()
                    ) {
                        CallRecorderService.start(this@CallRecordingsActivity)
                    }
                    if (!inCall) {
                        Text("Call state: idle — recorder will switch on when the call begins", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                    }
                }
            }

            // Shizuku TRUE-capture status + permission button
            if (com.neverhide.empire.calls.ShizukuCallCapture.available()) {
                val granted = com.neverhide.empire.calls.ShizukuCallCapture.permissionGranted()
                if (!granted) {
                    GlowButton("📡 Enable TRUE call capture (Shizuku)", listOf(Palette.PURPLE, Color(0xFF4A148C)), Modifier.fillMaxWidth()) {
                        com.neverhide.empire.calls.ShizukuCallCapture.requestPermission()
                    }
                    Text("Crystal-clear both-sides recording via Shizuku — one-time permission", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                } else {
                    StatusChip("📡 TRUE CAPTURE ACTIVE — direct call-stream recording", Palette.PURPLE)
                }
            } else {
                Text("📡 Shizuku not running — using speaker method (still works)", color = Palette.TEXT_MUTE, fontSize = 10.sp)
            }

            Spacer(Modifier.height(14.dp))
            SectionHeader("📁", "Recordings (${recs.size})", Palette.CYAN)
            Spacer(Modifier.height(6.dp))
            val tick = refresh

            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(recs, key = { it.file.absolutePath }) { r ->
                    val isPlaying = playingFile == r.file && player?.isPlaying == true
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(Palette.CARD, RoundedCornerShape(12.dp))
                            .border(1.dp, if (isPlaying) Palette.GREEN else Palette.BORDER, RoundedCornerShape(12.dp))
                            .clickable { togglePlay(r.file) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(if (isPlaying) "⏸" else "▶", color = if (isPlaying) Palette.GREEN else Palette.CYAN, fontSize = 16.sp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.number, color = Palette.WHITE, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            val pretty = runCatching {
                                SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.US)
                                    .format(SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(r.stamp))
                            }.getOrDefault("")
                            Text("$pretty • ${r.sizeKb} KB", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                        }
                        Text("📤", color = Palette.TEXT_DIM, fontSize = 16.sp, modifier = Modifier.clickable {
                            runCatching {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    this@CallRecordingsActivity, "$packageName.fileprovider", r.file
                                )
                                startActivity(Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "audio/wav"
                                        putExtra(Intent.EXTRA_STREAM, uri)
                                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }, "Share recording"
                                ))
                            }
                        })
                        Spacer(Modifier.width(12.dp))
                        Text("🗑", color = Palette.TEXT_DIM, fontSize = 16.sp, modifier = Modifier.clickable {
                            if (playingFile == r.file) { player?.stop(); player?.release(); player = null; playingFile = null }
                            r.file.delete()
                            recs = listRecs()
                        })
                    }
                }
            }
        }
    }
}
