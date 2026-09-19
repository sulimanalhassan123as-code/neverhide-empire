package com.neverhide.empire.talk

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neverhide.empire.dashboard.GlassCard
import com.neverhide.empire.dashboard.GlowButton
import com.neverhide.empire.dashboard.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * EMPIRE TALK — internet calls + chat between Empire handles.
 * Your handle is your identity: zero phone numbers, zero personal data.
 */
class EmpireTalkActivity : ComponentActivity() {

    private var pendingBuddy: String? = null
    private var pendingMode: String? = null
    private val ioScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { TalkApp() }
    }

    override fun onDestroy() {
        ioScope.cancel()
        super.onDestroy()
    }

    private fun withMicPermission(buddy: String, mode: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            pendingBuddy?.let { } // no-op
            startAction(buddy, mode)
        } else {
            pendingBuddy = buddy
            pendingMode = mode
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 77)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 77 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            val b = pendingBuddy; val m = pendingMode
            if (b != null && m != null) startAction(b, m)
        }
        pendingBuddy = null; pendingMode = null
    }

    private fun rememberBuddy(handle: String) {
        if (handle.isBlank()) return
        val p = getSharedPreferences("empire_talk", MODE_PRIVATE)
        val cur = p.getStringSet("recents", emptySet()) ?: emptySet()
        p.edit().putStringSet("recents", cur + handle).apply()
    }

    private var actionSink: ((String, String) -> Unit)? = null
    private fun startAction(buddy: String, mode: String) {
        runOnUiThread { actionSink?.invoke(buddy, mode) }
    }

    @Composable
    private fun TalkApp() {
        val prefs = remember { getSharedPreferences("empire_talk", MODE_PRIVATE) }
        var myHandle by remember { mutableStateOf(prefs.getString("handle", "") ?: "") }
        var handleInput by remember { mutableStateOf(prefs.getString("handle", "") ?: "") }
        var handleSaved by remember { mutableStateOf(myHandle.isNotBlank()) }
        var incoming by remember { mutableStateOf(listOf<Pair<String, String>>()) } // callId, caller
        var buddyInput by remember { mutableStateOf("") }
        var view by remember { mutableStateOf("home") }
        var chatWith by remember { mutableStateOf<String?>(null) }
        var activeCall by remember { mutableStateOf<CallRef?>(null) }
        var recents by remember { mutableStateOf(listOf<Pair<String, String>>()) }

        // RECENT CHATS — WhatsApp-style hub: every conversation and call with
        // your people, newest first, tap to open.
        LaunchedEffect(myHandle, view) {
            while (isActive && view == "home" && myHandle.isNotBlank()) {
                val seen = LinkedHashMap<String, Pair<Long, String>>()
                val msgs = TalkEngine.recentThreads(myHandle)
                if (msgs != null) {
                    for (i in 0 until msgs.length()) {
                        val m = msgs.getJSONObject(i)
                        val other = if (m.optString("sender") == myHandle)
                            m.optString("receiver") else m.optString("sender")
                        if (other.isNotBlank() && !seen.containsKey(other))
                            seen[other] = m.getLong("id") to ("💬 " + m.optString("body").take(40))
                    }
                }
                val calls = TalkEngine.recentCalls(myHandle)
                if (calls != null) {
                    for (i in 0 until calls.length()) {
                        val c = calls.getJSONObject(i)
                        val other = if (c.optString("caller") == myHandle)
                            c.optString("callee") else c.optString("caller")
                        if (other.isNotBlank() && !seen.containsKey(other))
                            seen[other] = Long.MAX_VALUE to "📞 last: call"
                    }
                }
                recents = seen.entries.sortedByDescending { it.value.first }
                    .map { it.key to it.value.second }
                delay(6000)
            }
        }

        MaterialTheme {
            Box(Modifier.fillMaxSize().background(Palette.pageBg)) {
                when {
                    activeCall != null -> CallScreen(
                        call = activeCall!!, myHandle = myHandle,
                        onEnd = { activeCall = null }
                    )
                    view == "chat" && chatWith != null -> ChatScreen(
                        myHandle = myHandle, other = chatWith!!,
                        onBack = { view = "home" }
                    )
                    else -> Column(
                        Modifier.fillMaxSize().padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📡 EMPIRE TALK", color = Palette.CYAN, fontSize = 20.sp,
                            fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Text("Internet calls + chat between Empire handles",
                            color = Palette.TEXT_MUTE, fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.height(12.dp))

                        // ---- YOUR HANDLE ----
                        GlassCard(Modifier.fillMaxWidth(), glow = Palette.CYAN) {
                            Text(if (handleSaved) "IDENTITY: @$myHandle" else "PICK YOUR HANDLE",
                                color = if (handleSaved) Palette.GREEN else Palette.CYAN,
                                fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Text("No phone numbers. Your handle is your identity.",
                                color = Palette.TEXT_MUTE, fontSize = 10.sp)
                            Spacer(Modifier.height(6.dp))
                            if (!handleSaved) {
                                TextField(value = handleInput, onValueChange = {
                                    handleInput = it.trim().lowercase().replace(Regex("[^a-z0-9_]"), "").take(20)
                                }, singleLine = true, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(6.dp))
                                GlowButton("⚔ Claim Handle", listOf(Palette.CYAN, Palette.PURPLE), Modifier.fillMaxWidth(),
                                    enabled = handleInput.length >= 3) {
                                    myHandle = handleInput
                                    prefs.edit().putString("handle", handleInput).apply()
                                    handleSaved = true
                                    val ctx = this@EmpireTalkActivity
                                    ioScope.launch {
                                        val token = ctx.getSharedPreferences("empire_firebase", MODE_PRIVATE)
                                            .getString("fcm_token", null)
                                        TalkEngine.upsertProfile(handleInput, token)
                                    }
                                }
                            } else {
                                Text("On the network. Others reach you as @$myHandle",
                                    color = Palette.TEXT_MUTE, fontSize = 10.sp)
                            }
                        }
                        Spacer(Modifier.height(10.dp))

                        // ---- INCOMING CALLS (poll) ----
                        LaunchedEffect(myHandle) {
                            while (isActive && myHandle.isNotBlank()) {
                                val arr = TalkEngine.incomingCalls(myHandle)
                                if (arr != null) {
                                    val list = mutableListOf<Pair<String, String>>()
                                    for (i in 0 until arr.length()) {
                                        val c = arr.getJSONObject(i)
                                        list.add(c.getString("call_id") to c.getString("caller"))
                                    }
                                    incoming = list
                                }
                                delay(4000)
                            }
                        }
                        if (incoming.isNotEmpty()) {
                            incoming.forEach { (callId, caller) ->
                                GlassCard(Modifier.fillMaxWidth(), glow = Palette.GREEN) {
                                    Text("📞 INCOMING CALL", color = Palette.GREEN, fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold)
                                    Text("@$caller is calling you right now",
                                        color = Palette.TEXT_DIM, fontSize = 11.sp)
                                    Spacer(Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GlowButton("✅ Answer", listOf(Palette.GREEN, Palette.CYAN), Modifier.weight(1f)) {
                                            ioScope.launch {
                                                val row = TalkEngine.getCall(callId)
                                                if (row != null) withContext(Dispatchers.Main) {
                                                    rememberBuddy(caller)
                                                    activeCall = CallRef(callId, caller, "callee", row.optJSONObject("offer"))
                                                }
                                            }
                                        }
                                        GlowButton("✖ Reject", listOf(Color(0xFFD32F2F), Color(0xFF7A1F1F)), Modifier.weight(1f)) {
                                            incoming = incoming.filter { it.first != callId }
                                            ioScope.launch { TalkEngine.endCall(callId) }
                                        }
                                    }
                                }
                                Spacer(Modifier.height(10.dp))
                            }
                        }

                        // ---- RECENT CHATS (WhatsApp-style hub) ----
                        if (recents.isNotEmpty()) {
                            GlassCard(Modifier.fillMaxWidth(), glow = Palette.CYAN) {
                                Text("RECENT CHATS", color = Palette.CYAN, fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold)
                                recents.forEach { (h, preview) ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable {
                                                rememberBuddy(h)
                                                chatWith = h; view = "chat"
                                            }
                                            .padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("👤", fontSize = 18.sp)
                                        Spacer(Modifier.width(8.dp))
                                        Column {
                                            Text("@$h", color = Palette.WHITE, fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold)
                                            Text(preview, color = Palette.TEXT_MUTE,
                                                fontSize = 10.sp, maxLines = 1)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                        }

                        // ---- REACH SOMEONE ----
                        GlassCard(Modifier.fillMaxWidth(), glow = Palette.PURPLE) {
                            Text("REACH SOMEONE", color = Palette.PURPLE, fontSize = 12.sp,
                                fontWeight = FontWeight.Bold)
                            Text("Type their handle — they need the Empire app too.",
                                color = Palette.TEXT_MUTE, fontSize = 10.sp)
                            Spacer(Modifier.height(6.dp))
                            TextField(value = buddyInput, onValueChange = {
                                buddyInput = it.trim().lowercase().replace(Regex("[^a-z0-9_]"), "").take(20)
                            }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                GlowButton("📞 Call", listOf(Palette.GREEN, Palette.CYAN), Modifier.weight(1f),
                                    enabled = handleSaved && buddyInput.length >= 3) {
                                    withMicPermission(buddyInput, "call")
                                }
                                GlowButton("💬 Chat", listOf(Palette.PURPLE, Palette.PINK), Modifier.weight(1f),
                                    enabled = handleSaved && buddyInput.length >= 3) {
                                    rememberBuddy(buddyInput)
                                    chatWith = buddyInput; view = "chat"
                                }
                            }
                        }

                        // permission bridge -> start call/chat
                        LaunchedEffect(Unit) {
                            actionSink = { buddy, mode ->
                                if (mode == "call") {
                                    val callId = "c" + System.currentTimeMillis() + "_" + myHandle
                                    rememberBuddy(buddy)
                                    activeCall = CallRef(callId, buddy, "caller", null)
                                } else {
                                    rememberBuddy(buddy)
                                    chatWith = buddy; view = "chat"
                                }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "Honest limits: in Phase 1, incoming calls ring while the Empire app is open " +
                                "(push-ringing with the app closed lands next). Both sides need internet. " +
                                "Recording captures both sides via the mic with speaker on — the call " +
                                "stream belongs to us, so quality is far above normal call recording.",
                            color = Palette.TEXT_MUTE, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }

    data class CallRef(val callId: String, val handle: String, val role: String, val offer: JSONObject?)

    // ---------------- CALL SCREEN ----------------

    @Composable
    private fun CallScreen(call: CallRef, myHandle: String, onEnd: () -> Unit) {
        var status by remember { mutableStateOf(if (call.role == "caller") "Calling @${call.handle}…" else "Answering…") }
        var connectedAt by remember { mutableStateOf(0L) }
        var tick by remember { mutableStateOf(0) }
        var recording by remember { mutableStateOf(false) }
        var muted by remember { mutableStateOf(false) }
        var ended by remember { mutableStateOf(false) }

        val peer = remember {
            TalkEngine.CallPeer(
                context = this@EmpireTalkActivity,
                onState = { s -> runOnUiThread {
                    when (s) {
                        "connected" -> { status = "🎙 LIVE"; connectedAt = System.currentTimeMillis() }
                        "failed" -> status = "Connection failed (network)"
                        "disconnected" -> status = "Connection lost…"
                    }
                } },
                onCandidate = { cand -> ioScope.launch {
                    TalkEngine.pushCandidate(call.callId, call.role, cand)
                } }
            )
        }
        var recorder by remember { mutableStateOf<TalkEngine.WavRecorder?>(null) }

        // connect: caller creates the offer; callee answers it
        LaunchedEffect(Unit) {
            if (call.role == "caller") {
                peer.createOffer { sdp ->
                    ioScope.launch {
                        val ok = TalkEngine.startCall(
                            call.callId, myHandle, call.handle,
                            JSONObject().put("type", "offer").put("sdp", sdp.description)
                        )
                        withContext(Dispatchers.Main) { if (!ok) status = "Could not reach @${call.handle} (offline?)" }
                    }
                }
            } else {
                val offer = call.offer
                if (offer == null) { status = "Call data missing"; return@LaunchedEffect }
                peer.setRemote("offer", offer.optString("sdp")) {
                    peer.createAnswer { sdp ->
                        ioScope.launch {
                            TalkEngine.answerCall(call.callId,
                                JSONObject().put("type", "answer").put("sdp", sdp.description))
                        }
                    }
                }
            }
        }

        // signal poll loop: remote candidates + hangup detection
        LaunchedEffect(Unit) {
            var lastCand = 0L
            while (isActive && !ended) {
                val row = TalkEngine.getCall(call.callId)
                if (row?.optString("state") == "ended") {
                    withContext(Dispatchers.Main) { status = "Call ended"; ended = true }
                    break
                }
                val remoteRole = if (call.role == "caller") "callee" else "caller"
                val cands = TalkEngine.getCandidates(call.callId, remoteRole, lastCand)
                if (cands != null) {
                    for (i in 0 until cands.length()) {
                        val c = cands.getJSONObject(i)
                        val cd = c.getJSONObject("cand")
                        peer.addCandidate(cd.optString("sdpMid"), cd.optInt("sdpMLineIndex"), cd.optString("sdp"))
                        lastCand = c.getLong("id")
                    }
                }
                delay(2000)
            }
        }

        // call timer
        LaunchedEffect(connectedAt) {
            while (isActive && connectedAt > 0L && !ended) {
                tick++
                delay(1000)
            }
        }

        fun hangUp() {
            ended = true
            recorder?.stop()
            ioScope.launch { TalkEngine.endCall(call.callId) }
            peer.close()
            onEnd()
        }

        Box(Modifier.fillMaxSize().background(Palette.pageBg).padding(14.dp)) {
            Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("🎙", fontSize = 56.sp)
                Text("@${call.handle}", color = Palette.TEXT_DIM, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(status, color = if (connectedAt > 0) Palette.GREEN else Palette.AMBER, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
                if (connectedAt > 0L) {
                    val mm = tick / 60; val ss = tick % 60
                    Text("%02d:%02d".format(mm, ss), color = Palette.CYAN, fontSize = 30.sp,
                        fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.height(24.dp))
                if (recording) Text("● RECORDING", color = Color(0xFFFF5252), fontSize = 12.sp,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    GlowButton(if (recording) "⏹ Stop Rec" else "🔴 Record",
                        listOf(Color(0xFFD32F2F), Color(0xFF7A1F1F)), Modifier.weight(1f),
                        enabled = connectedAt > 0 && !ended) {
                        if (recording) { recorder?.stop(); recording = false }
                        else {
                            val f = java.io.File(filesDir,
                                "call-recordings/TALK_${call.handle}_${System.currentTimeMillis()}.wav")
                            recorder = TalkEngine.WavRecorder(f)
                            recorder?.start()
                            recording = true
                        }
                    }
                    GlowButton(if (muted) "🎙 Unmute" else "🔇 Mute",
                        listOf(Color(0xFF455A64), Color(0xFF263238)), Modifier.weight(1f)) {
                        muted = !muted; peer.mute(muted)
                    }
                }
                Spacer(Modifier.height(10.dp))
                GlowButton("🔴 END CALL", listOf(Color(0xFFD32F2F), Color(0xFF7A1F1F)), Modifier.fillMaxWidth()) {
                    hangUp()
                }
            }
        }

        DisposableEffect(Unit) { onDispose { recorder?.stop() } }
    }

    // ---------------- CHAT SCREEN ----------------

    @Composable
    private fun ChatScreen(myHandle: String, other: String, onBack: () -> Unit) {
        val msgs = remember { mutableStateListOf<JSONObject>() }
        var lastId by remember { mutableStateOf(0L) }
        var input by remember { mutableStateOf("") }
        val listState = rememberLazyListState()

        LaunchedEffect(other) {
            while (isActive) {
                val arr = TalkEngine.getMessages(myHandle, other, lastId)
                if (arr != null && arr.length() > 0) {
                    withContext(Dispatchers.Main) {
                        for (i in 0 until arr.length()) {
                            msgs.add(arr.getJSONObject(i))
                            lastId = arr.getJSONObject(i).getLong("id")
                        }
                    }
                }
                delay(3000)
            }
        }

        Box(Modifier.fillMaxSize().background(Palette.pageBg)) {
            Column(Modifier.fillMaxSize().padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("← Back", color = Palette.CYAN, fontSize = 13.sp,
                        modifier = Modifier.clickable { onBack() }.padding(4.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("💬 @$other", color = Palette.TEXT_DIM, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.weight(1f), state = listState) {
                    items(msgs) { m ->
                        val mine = m.optString("sender") == myHandle
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                        ) {
                            Box(
                                Modifier
                                    .background(
                                        if (mine) Palette.GREEN else Color(0xFF37474F),
                                        RoundedCornerShape(10.dp)
                                    )
                                    .padding(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Text(m.optString("body"), color = Color.White, fontSize = 13.sp)
                            }
                        }
                    }
                }
                LaunchedEffect(msgs.size) {
                    if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(value = input, onValueChange = { input = it.take(500) },
                        modifier = Modifier.weight(1f), placeholder = { Text("Message…", fontSize = 13.sp) })
                    Spacer(Modifier.width(8.dp))
                    GlowButton("Send ➤", listOf(Palette.PURPLE, Palette.CYAN), Modifier,
                        enabled = input.isNotBlank()) {
                        val text = input; input = ""
                        ioScope.launch {
                            TalkEngine.sendMessage(myHandle, other, text)
                        }
                    }
                }
            }
        }
    }
}
