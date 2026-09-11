package com.neverhide.empire.quran

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.dashboard.GlassCard
import com.neverhide.empire.dashboard.GlowButton
import com.neverhide.empire.dashboard.Palette
import com.neverhide.empire.dashboard.SectionHeader
import com.neverhide.empire.dashboard.StatusChip

/**
 * QURAN SECTION — the complete Quran audio player UI.
 * Download once with data, then listen fully offline — even in deep sleep.
 */
class QuranActivity : ComponentActivity() {

    private var downloadProgress by mutableStateOf(-1 to -1)  // done to total
    private var playback by mutableStateOf(0 to false)          // surah to isPlaying
    private var refresh by mutableStateOf(0)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                QuranDownloadService.PROGRESS -> {
                    downloadProgress =
                        intent.getIntExtra("done", 0) to intent.getIntExtra("total", 114)
                    refresh++
                }
                QuranPlaybackService.STATE -> {
                    playback = intent.getIntExtra("surah", 0) to intent.getBooleanExtra("playing", false)
                    refresh++
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        registerReceiver(receiver, IntentFilter(QuranDownloadService.PROGRESS))
        registerReceiver(receiver, IntentFilter(QuranPlaybackService.STATE))
        if (QuranDownloadService.isRunning) downloadProgress =
            QuranDownloadService.lastDone to QuranDownloadService.lastTotal

        setContent {
            MaterialTheme {
                QuranScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh++
    }

    override fun onDestroy() {
        unregisterReceiver(receiver)
        super.onDestroy()
    }

    @Composable
    private fun QuranScreen() {
        val reciter = QuranLibrary.reciter(this)
        val tick = refresh  // observe to reload counts
        val done = QuranLibrary.downloadedCount(this, reciter)
        val sizeMb = QuranLibrary.downloadedSizeBytes(this, reciter) / (1024.0 * 1024.0)
        val prefs = getSharedPreferences(QuranLibrary.PREFS, MODE_PRIVATE)
        val lastSurah = prefs.getInt(QuranLibrary.KEY_LAST_SURAH, 0)

        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.pageBg)
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("📖 Quran Audio", color = Palette.GREEN, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (done == 114) StatusChip("FULLY OFFLINE", Palette.GREEN)
                    else StatusChip("$done / 114 surahs offline", Palette.AMBER)
                    Spacer(Modifier.width(6.dp))
                    StatusChip("%.0f MB".format(sizeMb), Palette.TEXT_DIM)
                }
            }

            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    GlassCard(glow = Palette.GREEN) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(50.dp)
                                    .background(
                                        Brush.linearGradient(listOf(Color(0xFF1B5E20), Palette.GREEN)),
                                        RoundedCornerShape(14.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) { Text("🕌", fontSize = 24.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(reciter.name, color = Palette.WHITE, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                Text(
                                    if (QuranDownloadService.isRunning) "Downloading… ${downloadProgress.first}/${downloadProgress.second}"
                                    else "Download once — play offline forever",
                                    color = Palette.TEXT_DIM, fontSize = 11.sp
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        // Reciter picker
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuranLibrary.reciters.forEach { r ->
                                val selected = r.id == reciter.id
                                Box(
                                    Modifier
                                        .background(
                                            if (selected) Palette.GREEN.copy(alpha = 0.2f) else Palette.CARD,
                                            RoundedCornerShape(10.dp)
                                        )
                                        .border(1.dp, if (selected) Palette.GREEN else Palette.BORDER, RoundedCornerShape(10.dp))
                                        .clickable {
                                            getSharedPreferences(QuranLibrary.PREFS, MODE_PRIVATE).edit()
                                                .putString(QuranLibrary.KEY_RECITER, r.id).apply()
                                            refresh++
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(r.name.substringBefore(" "), color = if (selected) Palette.GREEN else Palette.TEXT_DIM, fontSize = 11.sp)
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        // Download all + progress
                        if (QuranDownloadService.isRunning) {
                            val p = downloadProgress
                            if (p.first >= 0 && p.second > 0) {
                                LinearProgressIndicator(
                                    progress = { p.first.toFloat() / p.second },
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Palette.GREEN
                                )
                                Spacer(Modifier.height(8.dp))
                            }
                            GlowButton("⏹ Stop download", listOf(Color(0xFF37474F), Color(0xFF263238)), Modifier.fillMaxWidth()) {
                                QuranDownloadService.stop(this@QuranActivity)
                            }
                        } else {
                            GlowButton(
                                if (done == 114) "✅ Complete — all surahs offline" else "⬇️ Download complete Quran (${if (done == 0) "all missing" else "resume ${114 - done} left"})",
                                listOf(Palette.GREEN, Color(0xFF00E676)),
                                Modifier.fillMaxWidth()
                            ) {
                                QuranDownloadService.start(this@QuranActivity)
                            }
                        }
                        if (done > 0 && !QuranDownloadService.isRunning && lastSurah > 0) {
                            Spacer(Modifier.height(8.dp))
                            GlowButton(
                                "▶️ Resume last: ${Surahs.byNumber(lastSurah).englishName}",
                                listOf(Palette.CYAN, Color(0xFF00838F)),
                                Modifier.fillMaxWidth()
                            ) {
                                QuranPlaybackService.play(this@QuranActivity, lastSurah)
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SectionHeader("🎼", "Surahs", Palette.GREEN)
                    Spacer(Modifier.height(6.dp))
                }

                itemsIndexed(Surahs.all, key = { _, s -> s.number }) { _, surah ->
                    val downloaded = QuranLibrary.isDownloaded(this@QuranActivity, reciter, surah.number)
                    val playing = playback.first == surah.number && playback.second
                    val pausedHere = playback.first == surah.number && !playback.second
                    val totalTick = tick
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .shadow(4.dp, RoundedCornerShape(12.dp), spotColor = if (playing) Palette.GREEN.copy(alpha = 0.4f) else Color(0x00000000))
                            .background(Palette.CARD, RoundedCornerShape(12.dp))
                            .border(
                                1.dp,
                                if (playing) Palette.GREEN.copy(alpha = 0.6f) else Palette.BORDER,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable {
                                if (downloaded) {
                                    if (playing) QuranPlaybackService.send(this@QuranActivity, QuranPlaybackService.ACTION_PAUSE)
                                    else QuranPlaybackService.play(this@QuranActivity, surah.number)
                                } else if (!QuranDownloadService.isRunning) {
                                    QuranDownloadService.start(this@QuranActivity)
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "%d".format(surah.number),
                            color = Palette.TEXT_MUTE, fontSize = 11.sp,
                            modifier = Modifier.width(24.dp)
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                surah.englishName,
                                color = if (playing) Palette.GREEN else Palette.WHITE,
                                fontSize = 14.sp,
                                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal
                            )
                            Text("${surah.arabicName} • ${surah.ayahs} ayahs", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                        }
                        if (playing) Text("▶", color = Palette.GREEN, fontSize = 14.sp)
                        else if (pausedHere) Text("⏸", color = Palette.AMBER, fontSize = 14.sp)
                        else if (downloaded) Text("✓", color = Palette.GREEN, fontSize = 13.sp)
                        else Text("⬇", color = Palette.TEXT_MUTE, fontSize = 13.sp)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}
