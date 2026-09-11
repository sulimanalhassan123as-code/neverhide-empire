package com.neverhide.empire.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
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
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * CALLS SECTION — Voice Studio.
 *
 * Records your voice and morphs it with REAL digital signal processing
 * (no gimmicks): ring modulation for the robot voice, resampling for
 * pitch shifts, delay lines for echo.
 *
 * HONEST LIMIT: Android does not let any app modify your voice inside
 * a live phone call — the uplink audio belongs to the system. What you
 * CAN do: play your morphed voice through the speaker during a
 * hands-free call (the mic picks it up), or send morphed voice notes.
 * True in-call voice modulation requires root — no honest app can
 * promise otherwise.
 */
class VoiceStudioActivity : ComponentActivity() {

    private val RATE = 16000

    private var recording = false
    private var recordedPcm: ShortArray = ShortArray(0)
    private var track: AudioTrack? = null
    private var record: AudioRecord? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 11)
        }
        setContent { VoiceStudioScreen() }
    }

    // ============== DSP ENGINE ==============

    /** Ring modulation — THE classic robot voice. */
    private fun robot(pcm: ShortArray, freqHz: Double = 55.0): ShortArray {
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val t = i.toDouble() / RATE
            val v = pcm[i] * sin(2 * PI * freqHz * t)
            out[i] = v.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return out
    }

    /** Resample — pitch up (chipmunk) or down (deep). factor > 1 = higher pitch. */
    private fun resample(pcm: ShortArray, factor: Double): ShortArray {
        if (factor <= 0) return pcm
        val newLen = (pcm.size / factor).toInt().coerceAtLeast(1)
        val out = ShortArray(newLen)
        for (i in 0 until newLen) {
            val src = i * factor
            val i0 = src.toInt().coerceAtMost(pcm.size - 1)
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val frac = src - i0
            val v = pcm[i0] * (1 - frac) + pcm[i1] * frac
            out[i] = v.toInt().toShort()
        }
        return out
    }

    /** Echo / reverb via delay line. */
    private fun echo(pcm: ShortArray, delayMs: Int = 180, decay: Double = 0.45): ShortArray {
        val d = (RATE * delayMs / 1000)
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            var v = pcm[i].toDouble()
            if (i >= d) v += pcm[i - d] * decay
            if (i >= d * 2) v += pcm[i - d * 2] * decay * decay
            out[i] = v.coerceIn(-32767.0, 32767.0).toInt().toShort()
        }
        return out
    }

    private fun applyEffect(raw: ShortArray, effect: Int): ShortArray = when (effect) {
        1 -> resample(robot(raw, 60.0), 1.1)          // Robot
        2 -> resample(raw, 1.55)                     // Chipmunk
        3 -> resample(raw, 0.68)                     // Deep voice
        4 -> echo(raw)                                // Cave echo
        5 -> robot(resample(raw, 1.3), 170.0)        // Alien
        else -> raw                                   // Original
    }

    // ============== RECORD / PLAY ==============

    private fun startRecording(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        record = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, RATE * 2)
        )
        if (record?.state != AudioRecord.STATE_INITIALIZED) return false
        recording = true
        Thread {
            val buf = ShortArray(RATE / 2)
            val acc = mutableListOf<Short>()
            record?.startRecording()
            while (recording) {
                val n = record?.read(buf, 0, buf.size) ?: 0
                for (i in 0 until n) acc.add(buf[i])
            }
            record?.stop(); record?.release(); record = null
            recordedPcm = acc.toShortArray()
        }.start()
        return true
    }

    private fun stopRecording() { recording = false }

    private fun play(pcm: ShortArray) {
        track?.stop(); track?.release()
        if (pcm.isEmpty()) return
        track = AudioTrack(
            AudioManager.STREAM_MUSIC, RATE,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(pcm.size * 2, RATE), AudioTrack.MODE_STATIC
        )
        track?.write(pcm, 0, pcm.size)
        track?.play()
    }

    /** Export morphed voice as a shareable WAV file. */
    private fun exportWav(pcm: ShortArray, name: String): File? {
        return runCatching {
            val dir = File(getExternalFilesDir(null), "voice-studio").apply { mkdirs() }
            val f = File(dir, name)
            val byteCount = pcm.size * 2
            val out = java.io.ByteArrayOutputStream()
            out.write("RIFF".toByteArray()); writeLE(out, 36 + byteCount)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); writeLE(out, 16)
            writeLE(out, 1); writeLE(out, 1)      // PCM, mono
            writeLE(out, RATE); writeLE(out, RATE * 2)
            writeLE(out, 2); writeLE(out, 16)
            out.write("data".toByteArray()); writeLE(out, byteCount)
            val bytes = ByteArray(pcm.size * 2)
            for (i in pcm.indices) {
                val s = pcm[i].toInt()
                bytes[i * 2] = (s and 0xFF).toByte()
                bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
            }
            out.write(bytes)
            FileOutputStream(f).use { it.write(out.toByteArray()) }
            f
        }.getOrNull()
    }

    private fun writeLE(out: java.io.ByteArrayOutputStream, v: Int) {
        out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
    }

    // ============== UI ==============

    @Composable
    private fun VoiceStudioScreen() {
        var isRec by remember { mutableStateOf(false) }
        var hasClip by remember { mutableStateOf(false) }
        var effect by remember { mutableStateOf(1) }
        var playing by remember { mutableStateOf(false) }
        var exported by remember { mutableStateOf<File?>(null) }

        val effects = listOf(
            "🤖 Robot", "🐿️ Chipmunk", " 🎤 Deep", "🏔️ Echo", "👽 Alien", "🎧 Original"
        )

        Column(
            Modifier
                .fillMaxSize()
                .background(Palette.pageBg)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("🎙️ Voice Studio", color = Palette.CYAN, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            StatusChip("REAL DSP — not a gimmick", Palette.GREEN)

            GlassCard(glow = Palette.PURPLE) {
                Text(
                    "Honest note: no app can change your voice inside a live call — Android locks the uplink audio. " +
                            "BUT: call on speakerphone, play your morphed voice here, and the caller hears the robot. " +
                            "Or export a WAV and send it as a voice note.",
                    color = Palette.TEXT_DIM, fontSize = 12.sp
                )
            }

            SectionHeader("🎚️", "Record", Palette.CYAN)
            GlassCard {
                GlowButton(
                    if (isRec) "⏹ Stop recording" else "🎙️ Record your voice",
                    if (isRec) listOf(Palette.PINK, Color(0xFFC2185B)) else listOf(Palette.CYAN, Color(0xFF00838F)),
                    Modifier.fillMaxWidth()
                ) {
                    if (isRec) {
                        stopRecording()
                        isRec = false
                        // give the thread a moment to flush
                        Thread.sleep(250)
                        hasClip = recordedPcm.isNotEmpty()
                    } else {
                        val ok = startRecording()
                        isRec = ok
                        if (!ok) hasClip = false
                    }
                }
                if (isRec) {
                    Text("● REC — speak now…", color = Palette.PINK, fontSize = 12.sp)
                }
                if (hasClip && !isRec) StatusChip("✓ Clip captured — ${recordedPcm.size / RATE}s", Palette.GREEN)
            }

            SectionHeader("🎭", "Choose voice", Palette.PURPLE)
            GlassCard {
                effects.forEachIndexed { i, label ->
                    val selected = effect == i
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(
                                if (selected) Palette.PURPLE.copy(alpha = 0.18f) else Palette.CARD,
                                RoundedCornerShape(10.dp)
                            )
                            .border(1.dp, if (selected) Palette.PURPLE else Palette.BORDER, RoundedCornerShape(10.dp))
                            .clickable { effect = i }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(label, color = if (selected) Palette.PURPLE else Palette.WHITE, fontSize = 14.sp)
                    }
                }
            }

            if (hasClip) {
                SectionHeader("▶️", "Play & share", Palette.GREEN)
                GlassCard {
                    GlowButton(
                        if (playing) "▶ Playing…" else "▶ Play morphed voice",
                        listOf(Palette.GREEN, Color(0xFF00E676)), Modifier.fillMaxWidth()
                    ) {
                        playing = true
                        Thread {
                            val out = applyEffect(recordedPcm, effect)
                            play(out)
                            Thread.sleep((out.size * 1000L / RATE) + 300)
                            playing = false
                        }.start()
                    }
                    Spacer(Modifier.height(8.dp))
                    GlowButton("💾 Export WAV (share as voice note)", listOf(Palette.ORANGE, Color(0xFFDD2C00)), Modifier.fillMaxWidth()) {
                        val names = listOf("robot", "chipmunk", "deep", "echo", "alien", "original")
                        exported = exportWav(
                            applyEffect(recordedPcm, effect),
                            "neverhide-${names[effect]}.wav"
                        )
                    }
                    exported?.let { f ->
                        Spacer(Modifier.height(8.dp))
                        GlowButton("📤 Send now", listOf(Palette.CYAN, Color(0xFF00838F)), Modifier.fillMaxWidth()) {
                            runCatching {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    this@VoiceStudioActivity, "$packageName.fileprovider", f
                                )
                                startActivity(
                                    Intent.createChooser(
                                        Intent(Intent.ACTION_SEND).apply {
                                            type = "audio/wav"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }, "Send your morphed voice"
                                    )
                                )
                            }
                        }
                        Text("Saved: ${f.name}", color = Palette.TEXT_MUTE, fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
