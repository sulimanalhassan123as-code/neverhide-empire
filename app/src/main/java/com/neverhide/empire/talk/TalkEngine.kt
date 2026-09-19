package com.neverhide.empire.talk

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * EMPIRE TALK ENGINE
 *
 * Internet calls between Empire handles — the stream belongs to US, so both
 * sides can be recorded in full quality (the thing normal phone calls can
 * never give us).
 *
 * Meeting point: Supabase (signaling + chat store). Voice: WebRTC
 * peer-to-peer with Google STUN. The Supabase anon key is a public client
 * key by design; TALK_APP_KEY is the shared Empire secret (same model as the
 * Guardian bridge secret) and Supabase RLS refuses any request without it.
 */
object TalkEngine {

    private const val SB_URL = "https://hokqlvkowcrujppeliip.supabase.co/rest/v1"
    private const val SB_ANON = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6Imhva3Fsdmtvd2NydWpwcGVsaWlwIiwicm9sZSI6ImFub24iLCJpYXQiOjE3Nzk3MTg1MDUsImV4cCI6MjA5NTI5NDUwNX0._iO6p71kJRiBWH-fJ1j7GWDNmMcjSMN5nseNU4VN8tE"
    private const val TALK_APP_KEY = "empire-talk-647a00bd4a571d2991bf591a4f18f101"

    // ---------------- Supabase REST (blocking — call from IO coroutines) ----

    private class Resp(val code: Int, val text: String)

    private fun http(method: String, path: String, body: String?, prefer: String? = null): Resp {
        val conn = URL(SB_URL + path).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = method
            conn.connectTimeout = 10000
            conn.readTimeout = 15000
            conn.setRequestProperty("apikey", SB_ANON)
            conn.setRequestProperty("Authorization", "Bearer $SB_ANON")
            if (prefer != null) conn.setRequestProperty("Prefer", prefer)
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            Resp(code, stream?.bufferedReader()?.readText() ?: "")
        } finally {
            conn.disconnect()
        }
    }

    fun upsertProfile(handle: String, token: String?): Boolean {
        val o = JSONObject().put("handle", handle).put("app_key", TALK_APP_KEY)
        if (token != null) o.put("fcm_token", token)
        // PostgREST 12+ moved upsert resolution from query param to the
        // Prefer header — the old ?resolution= form is parsed as a filter
        // and rejected with 400. on_conflict=handle is the key constraint.
        return http(
            "POST", "/talk_profiles?on_conflict=handle", o.toString(),
            "resolution=merge-duplicates"
        ).code in 200..299
    }

    fun startCall(callId: String, from: String, to: String, offer: JSONObject): Boolean {
        val o = JSONObject().put("call_id", callId).put("caller", from).put("callee", to)
            .put("offer", offer).put("state", "ringing").put("app_key", TALK_APP_KEY)
        return http("POST", "/talk_calls", o.toString()).code in 200..299
    }

    fun getCall(callId: String): JSONObject? {
        val r = http("GET", "/talk_calls?call_id=eq.$callId&select=*", null)
        if (r.code !in 200..299) return null
        val a = JSONArray(r.text)
        return if (a.length() > 0) a.getJSONObject(0) else null
    }

    fun incomingCalls(handle: String): JSONArray? {
        val r = http(
            "GET",
            "/talk_calls?callee=eq.$handle&state=eq.ringing&select=call_id,caller&order=created_at.desc&limit=5",
            null
        )
        return if (r.code in 200..299) JSONArray(r.text) else null
    }

    fun answerCall(callId: String, answer: JSONObject): Boolean {
        val o = JSONObject().put("answer", answer).put("state", "active")
        return http("PATCH", "/talk_calls?call_id=eq.$callId", o.toString()).code in 200..299
    }

    fun endCall(callId: String): Boolean {
        return http("PATCH", "/talk_calls?call_id=eq.$callId", JSONObject().put("state", "ended").toString())
            .code in 200..299
    }

    fun pushCandidate(callId: String, role: String, cand: IceCandidate): Boolean {
        val o = JSONObject().put("call_id", callId).put("role", role)
            .put("cand", JSONObject()
                .put("sdpMid", cand.sdpMid)
                .put("sdpMLineIndex", cand.sdpMLineIndex)
                .put("sdp", cand.sdp))
            .put("app_key", TALK_APP_KEY)
        return http("POST", "/talk_candidates", o.toString()).code in 200..299
    }

    fun getCandidates(callId: String, role: String, sinceId: Long): JSONArray? {
        val r = http(
            "GET",
            "/talk_candidates?call_id=eq.$callId&role=eq.$role&id=gt.$sinceId&select=id,cand&order=id.asc",
            null
        )
        return if (r.code in 200..299) JSONArray(r.text) else null
    }

    fun sendMessage(from: String, to: String, body: String): Boolean {
        val o = JSONObject().put("sender", from).put("receiver", to).put("body", body)
            .put("app_key", TALK_APP_KEY)
        return http("POST", "/talk_messages", o.toString()).code in 200..299
    }

    fun getMessages(me: String, other: String, sinceId: Long): JSONArray? {
        val r = http(
            "GET",
            "/talk_messages?or=(and(sender.eq.$me,receiver.eq.$other),and(sender.eq.$other,receiver.eq.$me))" +
                "&id=gt.$sinceId&select=id,sender,receiver,body&order=id.asc&limit=200",
            null
        )
        return if (r.code in 200..299) JSONArray(r.text) else null
    }

    fun recentThreads(handle: String): JSONArray? {
        val r = http(
            "GET",
            "/talk_messages?or=(sender.eq.$handle,receiver.eq.$handle)" +
                "&select=id,sender,receiver,body&order=id.desc&limit=60",
            null
        )
        return if (r.code in 200..299) JSONArray(r.text) else null
    }

    fun recentCalls(handle: String): JSONArray? {
        val r = http(
            "GET",
            "/talk_calls?or=(caller.eq.$handle,callee.eq.$handle)&select=caller,callee&order=created_at.desc&limit=10",
            null
        )
        return if (r.code in 200..299) JSONArray(r.text) else null
    }

    // ---------------- WebRTC voice peer --------------------------------------

    abstract class SdpObs : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    class CallPeer(
        context: Context,
        private val onState: (String) -> Unit,
        private val onCandidate: (IceCandidate) -> Unit
    ) {
        private var factory: PeerConnectionFactory? = null
        private var pc: PeerConnection? = null
        private var track: org.webrtc.AudioTrack? = null

        init {
            runCatching {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(context)
                        .createInitializationOptions()
                )
                factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
                val config = PeerConnection.RTCConfiguration(
                    listOf(
                        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
                    )
                )
                pc = factory!!.createPeerConnection(config, object : PeerConnection.Observer {
                    override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                    override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                        when (state) {
                            PeerConnection.IceConnectionState.CONNECTED,
                            PeerConnection.IceConnectionState.COMPLETED -> onState("connected")
                            PeerConnection.IceConnectionState.FAILED -> onState("failed")
                            PeerConnection.IceConnectionState.DISCONNECTED -> onState("disconnected")
                            PeerConnection.IceConnectionState.CLOSED -> onState("closed")
                            else -> {}
                        }
                    }
                    override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                    override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                    override fun onIceCandidate(candidate: IceCandidate) { onCandidate(candidate) }
                    override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                    override fun onAddStream(stream: org.webrtc.MediaStream?) {}
                    override fun onRemoveStream(stream: org.webrtc.MediaStream?) {}
                    override fun onDataChannel(dc: org.webrtc.DataChannel?) {}
                    override fun onRenegotiationNeeded() {}
                    override fun onTrack(transceiver: RtpTransceiver?) {}
                    override fun onAddTrack(
                        receiver: org.webrtc.RtpReceiver?,
                        mediaStreams: Array<out org.webrtc.MediaStream?>?
                    ) {}
                })
                val source = factory!!.createAudioSource(MediaConstraints())
                track = factory!!.createAudioTrack("empire-audio", source)
                pc!!.addTrack(track!!, listOf("empire-stream"))
                // Speaker on so the mic recorder captures both sides; this is
                // OUR app call (no OS call routing to fight).
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                am.isSpeakerphoneOn = true
            }.onFailure { onState("failed") }
        }

        fun createOffer(onSdp: (SessionDescription) -> Unit) {
            pc?.createOffer(object : SdpObs() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    pc?.setLocalDescription(object : SdpObs() {
                        override fun onSetSuccess() { desc?.let(onSdp) }
                    }, desc)
                }
            }, MediaConstraints())
        }

        fun setRemote(type: String, sdp: String, onSet: () -> Unit) {
            pc?.setRemoteDescription(object : SdpObs() {
                override fun onSetSuccess() { onSet() }
            }, SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp))
        }

        fun createAnswer(onSdp: (SessionDescription) -> Unit) {
            pc?.createAnswer(object : SdpObs() {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    pc?.setLocalDescription(object : SdpObs() {
                        override fun onSetSuccess() { desc?.let(onSdp) }
                    }, desc)
                }
            }, MediaConstraints())
        }

        fun addCandidate(mid: String?, mLine: Int, sdp: String) {
            runCatching { pc?.addIceCandidate(IceCandidate(mid, mLine, sdp)) }
        }

        fun mute(muted: Boolean) { track?.setEnabled(!muted) }

        fun close() {
            runCatching { pc?.close() }
            runCatching { track?.dispose() }
            runCatching { factory?.dispose() }
            pc = null; factory = null
        }
    }

    // ---------------- WAV recorder (mic on speaker — both sides) ------------

    class WavRecorder(private val file: File) {
        @Volatile var running = false

        fun start() {
            running = true
            file.parentFile?.mkdirs()
            thread {
                val rate = 16000
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val rec = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192) * 4
                )
                val raf = RandomAccessFile(file, "rw")
                raf.setLength(0)
                raf.write(ByteArray(44)) // header placeholder
                var total = 0L
                val buf = ShortArray(3200)
                try {
                    rec.startRecording()
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            val b = ByteArray(n * 2)
                            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                .asShortBuffer().put(buf, 0, n)
                            raf.write(b)
                            total += n * 2
                        }
                    }
                } catch (t: Throwable) {
                    // recorder thread must never crash the call
                } finally {
                    runCatching { rec.stop() }
                    runCatching { rec.release() }
                    runCatching {
                        raf.seek(0)
                        wavHeader(raf, rate, 1, 16, total)
                    }
                    runCatching { raf.close() }
                }
            }
        }

        fun stop() { running = false }

        private fun le(r: RandomAccessFile, v: Int, n: Int) {
            var x = v
            repeat(n) { r.write(x and 0xff); x = x shr 8 }
        }

        private fun wavHeader(r: RandomAccessFile, rate: Int, ch: Int, bits: Int, dataLen: Long) {
            r.writeBytes("RIFF")
            le(r, (36 + dataLen).toInt(), 4)
            r.writeBytes("WAVE")
            r.writeBytes("fmt ")
            le(r, 16, 4); le(r, 1, 2); le(r, ch, 2); le(r, rate, 4)
            le(r, rate * ch * bits / 8, 4); le(r, ch * bits / 8, 2); le(r, bits, 2)
            r.writeBytes("data")
            le(r, dataLen.toInt(), 4)
        }
    }
}
