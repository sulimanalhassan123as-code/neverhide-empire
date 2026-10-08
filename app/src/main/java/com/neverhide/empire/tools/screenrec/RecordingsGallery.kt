package com.neverhide.empire.tools.screenrec

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.neverhide.empire.tools.screenrec.ScreenRecordService as SRS
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

/**
 * Screen Recorder UI — recording controls + recordings gallery.
 *
 * THE WHATSAPP QUALITY ANSWER (why three share buttons):
 *  "Send as Document" → WhatsApp sends the ORIGINAL file, byte for byte.
 *      No re-encode, no compression, zero quality loss. Use this to keep
 *      the recording's full quality for any friend or group.
 *  "Send as Video"  → WhatsApp re-encodes it (their servers always do).
 *      Record with the WHATSAPP preset (720p/6Mbps) so the re-encode has
 *      an easy job and the result still looks sharp.
 *  "Direct via Bridge" → the app uploads the file to the WhatsApp bridge
 *      and Baileys posts it STRAIGHT to your WhatsApp Status or to any
 *      number's chat — no WhatsApp app interaction, no recompression on
 *      OUR side (WhatsApp may still transcode statuses).
 */
@Composable
fun ScreenRecorderScreen() {
    val context = LocalContext.current
    var preset by remember { mutableStateOf(SRS.PRESET_HIGH) }
    var mic by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    val projectionManager =
        remember { context.getSystemService(MediaProjectionManager::class.java) }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            SRS.start(context, result.resultCode, data, preset, mic)
        }
    }

    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            mic = true
            projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    // v2.9.9 fix: SRS.isRecording is a plain var Compose cannot see.
    // Collect the service's uiTick so the screen ACTUALLY flips to the
    // recording panel the moment recording starts (v2.9.7 showed nothing).
    var recState by remember { mutableStateOf(SRS.isRecording) }
    LaunchedEffect(Unit) {
        SRS.uiTick.collect {
            recState = SRS.isRecording
            refresh = refresh + 1
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (recState) {
            RecordingPanel()
        } else {
            // ---- preset + start ----
            Text("🎬 Quality preset", color = Color(0xFF00E5FF), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PresetChip("⚡ MAX", SRS.PRESET_MAX, preset) { preset = it }
                PresetChip("💎 HIGH", SRS.PRESET_HIGH, preset) { preset = it }
                PresetChip("💬 WHATSAPP", SRS.PRESET_WHATSAPP, preset) { preset = it }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (preset) {
                    SRS.PRESET_MAX -> "Native resolution · 60fps · 24 Mbps — archival quality, big files"
                    SRS.PRESET_WHATSAPP -> "720p · 30fps · 6 Mbps — tuned to survive WhatsApp compression"
                    else -> "1080p · 30fps · 16 Mbps — crisp everyday default"
                },
                color = Color.Gray, fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🎙 Record microphone", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = mic,
                    onCheckedChange = { mic = it },
                    colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00E5FF))
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    if (mic &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED
                    ) {
                        micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    } else {
                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) { Text("● START RECORDING", color = Color.White, fontWeight = FontWeight.Bold) }
            SRS.lastError?.let {
                Spacer(Modifier.height(8.dp))
                Text("⚠ $it", color = Color(0xFFFF5252), fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        HorizontalDivider(color = Color(0xFF1E2A3A))
        Spacer(Modifier.height(12.dp))

        // ---- recordings list ----
        Text("📁 Recordings", color = Color(0xFF00E5FF), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            "Send as DOCUMENT to keep 100% quality on WhatsApp",
            color = Color.Gray, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        val files = remember(refresh) {
            SRS.recordingsDir(context).listFiles { f -> f.extension == "mp4" }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
        if (files.isEmpty()) {
            Text("No recordings yet.", color = Color.Gray, fontSize = 13.sp)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(files, key = { it.absolutePath }) { f ->
                    RecordingCard(f, onDelete = {
                        f.delete(); refresh++
                    }, onRenamed = { refresh++ })
                }
            }
        }
    }
}

@Composable
private fun PresetChip(label: String, key: String, current: String, pick: (String) -> Unit) {
    val active = key == current
    Text(
        label,
        color = if (active) Color(0xFF0A0A1A) else Color(0xFF00E5FF),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(
                if (active) Color(0xFF00E5FF) else Color.Transparent,
                RoundedCornerShape(20.dp)
            )
            .border(1.dp, Color(0xFF00E5FF), RoundedCornerShape(20.dp))
            .clickable { pick(key) }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
private fun RecordingPanel() {
    var elapsed by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (SRS.isRecording) {
            elapsed = (System.currentTimeMillis() - SRS.startedAt) / 1000
            kotlinx.coroutines.delay(500)
        }
    }
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0x22FF1744), RoundedCornerShape(14.dp))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("🔴 RECORDING", color = Color(0xFFFF5252), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            String.format(Locale.US, "%02d:%02d", elapsed / 60, elapsed % 60),
            color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { SRS.togglePause(context) }) {
                Text(if (SRS.isPaused) "▶ Resume" else "⏸ Pause", color = Color(0xFFFFC400))
            }
            Button(
                onClick = { SRS.stop(context) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744))
            ) { Text("⏹ STOP & SAVE") }
        }
    }
}

@Composable
private fun RecordingCard(f: File, onDelete: () -> Unit, onRenamed: () -> Unit) {
    val context = LocalContext.current
    var showBridge by remember { mutableStateOf(false) }
    val sizeMb = f.length() / 1024.0 / 1024.0
    val dur = remember(f) { videoDuration(f) }
    val name = runCatching {
        val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
            .parse(f.nameWithoutExtension.removePrefix("screen_"))
        SimpleDateFormat("d MMM, HH:mm:ss", Locale.US).format(d!!)
    }.getOrDefault(f.nameWithoutExtension)
    var renameDialog by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF111827), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Text(name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "⏱ $dur · 📦 ${"%.1f".format(sizeMb)} MB",
            color = Color.Gray, fontSize = 11.sp
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SmallBtn("▶ Play") { playVideo(context, f) }
            SmallBtn("📄 Document") { shareToWhatsapp(context, f, asDocument = true) }
            SmallBtn("🎥 Video") { shareToWhatsapp(context, f, asDocument = false) }
            SmallBtn("🌐 Direct") { showBridge = !showBridge }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            SmallBtn("✏️ Rename") { renameDialog = true }
            SmallBtn("🗑 Delete") { onDelete() }
        }
        if (showBridge) {
            Spacer(Modifier.height(10.dp))
            BridgeSender(file = f)
        }
    }

    if (renameDialog) {
        var txt by remember { mutableStateOf(f.nameWithoutExtension) }
        AlertDialog(
            onDismissRequest = { renameDialog = false },
            title = { Text("Rename recording", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = txt, onValueChange = { txt = it },
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = File(f.parentFile, txt.trim() + ".mp4")
                    if (txt.isNotBlank() && !target.exists()) { f.renameTo(target); onRenamed() }
                    renameDialog = false
                }) { Text("Save", color = Color(0xFF00E5FF)) }
            },
            dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel", color = Color.Gray) } }
        )
    }
}

/** The direct WhatsApp bridge sender: status or any number. */
@Composable
private fun BridgeSender(file: File) {
    val context = LocalContext.current
    var toStatus by remember { mutableStateOf(true) }
    var number by remember { mutableStateOf("") }
    var caption by remember { mutableStateOf("") }
    var state by remember { mutableStateOf("") } // "" | sending | ok | fail

    Column(
        Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1B2A), RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Text("Send DIRECT from the app (no WhatsApp UI):", color = Color(0xFF00E5FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("My WhatsApp Status", color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Switch(
                checked = toStatus, onCheckedChange = { toStatus = it },
                colors = SwitchDefaults.colors(checkedTrackColor = Color(0xFF00E5FF))
            )
        }
        if (!toStatus) {
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = number,
                onValueChange = { number = it },
                label = { Text("Friend's number (+233...)", color = Color.Gray) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = caption,
            onValueChange = { caption = it },
            label = { Text("Caption (optional)", color = Color.Gray) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                state = "sending"
                val target = if (toStatus) "status" else number.trim()
                Thread {
                    val res = BridgeVideoSend.send(
                        target, caption, file
                    ) { pct -> /* progress not shown per-file; too fast to matter */ }
                    state = if (res.first) "ok" else "fail:${res.second}"
                }.start()
            },
            enabled = state != "sending",
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (state == "sending") "⏳ Uploading…" else
                if (toStatus) "📤 POST TO MY STATUS" else "📤 SEND TO NUMBER",
                color = Color(0xFF0A0A1A), fontWeight = FontWeight.Bold, fontSize = 13.sp
            )
        }
        when {
            state == "ok" -> Text("✅ Sent! Check WhatsApp.", color = Color(0xFF69F0AE), fontSize = 12.sp)
            state.startsWith("fail:") -> Text("⚠ ${state.removePrefix("fail:")}", color = Color(0xFFFF5252), fontSize = 12.sp)
            state == "sending" -> Text("Uploading ${"%.1f".format(file.length() / 1024.0 / 1024.0)} MB — keep app open", color = Color.Gray, fontSize = 11.sp)
        }
    }
}

@Composable
private fun SmallBtn(label: String, onClick: () -> Unit) {
    Text(
        label,
        color = Color(0xFF00E5FF),
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .background(Color(0xFF00E5FF).copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    )
}

// ===================== helpers =====================

private fun videoDuration(f: File): String {
    val r = MediaMetadataRetriever()
    return try {
        r.setDataSource(f.absolutePath)
        val ms = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val s = ms / 1000
        String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    } catch (e: Exception) { "0:00" } finally { r.release() }
}

private fun playVideo(context: android.content.Context, f: File) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, context.packageName + ".fileprovider", f
    )
    context.startActivity(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    )
}

/**
 * WhatsApp share intent. asDocument=true shares the raw .mp4 as a
 * document — WhatsApp does NOT re-encode documents, so the recipient
 * gets the exact original quality.
 */
private fun shareToWhatsapp(context: android.content.Context, f: File, asDocument: Boolean) {
    val uri = androidx.core.content.FileProvider.getUriForFile(
        context, context.packageName + ".fileprovider", f
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = if (asDocument) "*/*" else "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (asDocument) putExtra(Intent.EXTRA_TEXT, f.name) // filename hint
        setPackage("com.whatsapp")
    }
    try {
        context.startActivity(Intent.createChooser(intent, if (asDocument) "Send as document (original quality)" else "Send as video"))
    } catch (e: Exception) {
        // WhatsApp not installed → fall back to generic share
        intent.setPackage(null)
        context.startActivity(Intent.createChooser(intent, "Share recording"))
    }
}

/** Bridge uploader — multipart POST, no base64, no JSON size limits. */
object BridgeVideoSend {
    private const val URL_STR =
        "https://whatsapp-bridge-6bdj.onrender.com/video/send"
    private const val SECRET = "1d15d106f20d08fd901d974b7812e2285f183e5a6ad8aaba"

    /**
     * target = "status" (post to WhatsApp status) or a phone number.
     * Returns (true, "") on success or (false, reason).
     */
    fun send(target: String, caption: String, file: File, onProgress: (Int) -> Unit): Pair<Boolean, String> {
        return try {
            val boundary = "EmpireBoundary" + System.currentTimeMillis()
            val conn = URL(URL_STR).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 30_000
            conn.readTimeout = 600_000 // big uploads on mobile data
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            conn.setRequestProperty("x-bridge-secret", SECRET)
            conn.setFixedLengthStreamingMode(
                file.length() + estimateFormSize(target, caption, boundary)
            )

            val out = conn.outputStream
            fun field(name: String, value: String) {
                out.write("--$boundary\r\n".toByteArray())
                out.write("Content-Disposition: form-data; name=\"$name\"\r\n\r\n".toByteArray())
                out.write(value.toByteArray())
                out.write("\r\n".toByteArray())
            }
            field("secret", SECRET)
            field("target", target)
            field("caption", caption.ifEmpty { "Screen recording" })
            out.write("--$boundary\r\n".toByteArray())
            out.write(
                "Content-Disposition: form-data; name=\"video\"; filename=\"${file.name}\"\r\n".toByteArray()
            )
            out.write("Content-Type: video/mp4\r\n\r\n".toByteArray())
            val buf = ByteArray(64 * 1024)
            var sent = 0L
            file.inputStream().use { ins ->
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    sent += n
                    onProgress((sent * 100 / file.length()).toInt())
                }
            }
            out.write("\r\n--$boundary--\r\n".toByteArray())
            out.flush()
            out.close()

            val code = conn.responseCode
            val resp = conn.inputStream.bufferedReader().readText()
            val ok = code == 200 && resp.contains("\"ok\":true")
            if (ok) true to "" else false to "bridge error $code"
        } catch (e: Exception) {
            // Render free tier sleeps — one wake retry
            try { Thread.sleep(45_000); send(target, caption, file, onProgress) }
            catch (e2: Exception) { false to (e.message ?: "network error") }
        }
    }

    private fun estimateFormSize(target: String, caption: String, boundary: String): Long {
        var size = 0L
        fun part(name: String, value: String): Long {
            val s = "--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$value\r\n"
            return s.toByteArray().size.toLong()
        }
        size += part("secret", SECRET)
        size += part("target", target)
        size += part("caption", caption.ifEmpty { "Screen recording" })
        size += ("--$boundary\r\nContent-Disposition: form-data; name=\"video\"; filename=\"x.mp4\"\r\nContent-Type: video/mp4\r\n\r\n").toByteArray().size
        size += ("\r\n--$boundary--\r\n").toByteArray().size
        return size
    }
}
