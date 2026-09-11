package com.neverhide.empire.quran

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.dashboard.GlassCard
import com.neverhide.empire.dashboard.Palette
import com.neverhide.empire.dashboard.SectionHeader
import com.neverhide.empire.dashboard.StatusChip

/**
 * QURAN SECTION — reserved for the full Quran audio player.
 * Everything Quran will live in this folder: recitation streaming,
 * offline downloads, playlist, prayer reminders. Sealed from the rest
 * of the app so it can grow without ever breaking anything else.
 */
class QuranActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { QuranScreen() }
    }

    @Composable
    private fun QuranScreen() {
        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.pageBg)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("📖 Quran Audio", color = Palette.GREEN, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            StatusChip("IN DEVELOPMENT — NEXT RELEASE", Palette.AMBER)

            GlassCard(glow = Palette.GREEN) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(60.dp)
                            .background(Brush.linearGradient(listOf(Color(0xFF1B5E20), Palette.GREEN)), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) { Text("🕌", fontSize = 30.sp) }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("The full Quran, with audio", color = Palette.WHITE, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("Streaming + offline recitation, right here in the Empire.", color = Palette.TEXT_DIM, fontSize = 12.sp)
                    }
                }
            }

            SectionHeader("🧩", "What's planned", Palette.GREEN)
            GlassCard {
                val plans = listOf(
                    "🎧" to "All 114 Surahs with world-famous reciters",
                    "⬇️" to "Offline downloads — listen without data",
                    "🌙" to "Prayer-time reminders",
                    "🔖" to "Bookmarks + last-played position",
                    "🎛️" to "Background playback with the Empire engine"
                )
                plans.forEach { (icon, text) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(icon, fontSize = 16.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(text, color = Palette.WHITE.copy(alpha = 0.9f), fontSize = 13.sp)
                    }
                }
            }

            GlassCard {
                Text(
                    "This screen is the foundation — the player plugs straight in here when it ships.",
                    color = Palette.TEXT_MUTE, fontSize = 11.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
