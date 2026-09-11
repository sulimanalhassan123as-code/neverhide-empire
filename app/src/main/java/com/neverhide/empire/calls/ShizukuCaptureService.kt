package com.neverhide.empire.calls

import androidx.annotation.Keep
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

/**
 * CALLS SECTION — Shizuku true call capture.
 *
 * HOW THIS WORKS (the real technique, proven by FOSS projects like
 * ShizuCallRecorder):
 *   Android 10+ reserves CAPTURE_AUDIO_OUTPUT for the system — normal
 *   apps get silence from the call stream. BUT the ADB *shell* user
 *   holds that permission (see packages/Shell/AndroidManifest.xml in
 *   AOSP). Shizuku runs our [ShizukuCaptureService] INSIDE the shell
 *   process, so its AudioRecord with the VOICE_CALL source captures
 *   BOTH sides of the call, digitally, with no speakerphone trick.
 *
 * This class is loaded by Shizuku (UserService) — the app itself never
 * holds these privileges, Shizuku brokers them per-session.
 */
@Keep
class ShizukuCaptureService : IShizukuCapture.Stub() {

    private val RATE = 44100
    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    private var wavPath: String = ""

    override fun ping(): Boolean {
        // Verify we can construct a call-source recorder at all.
        return try {
            val min = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_CALL, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, min
            )
            val ok = r.state == AudioRecord.STATE_INITIALIZED
            runCatching { r.release() }
            ok
        } catch (e: Exception) {
            false
        }
    }

    override fun start(wavPath: String?): Int {
        if (running) return -1
        val path = wavPath ?: return -1
        try {
            val min = AudioRecord.getMinBufferSize(
                RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_CALL, RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, RATE)
            )
            if (record?.state != AudioRecord.STATE_INITIALIZED) return -1
            this.wavPath = path
            running = true
            val tmp = File(path + ".pcm")
            thread = Thread {
                try {
                    val buf = ShortArray(RATE / 2)
                    val fos = FileOutputStream(tmp)
                    record?.startRecording()
                    while (running) {
                        val n = record?.read(buf, 0, buf.size) ?: 0
                        if (n <= 0) break
                        val bytes = ByteArray(n * 2)
                        for (i in 0 until n) {
                            val s = buf[i].toInt()
                            bytes[i * 2] = (s and 0xFF).toByte()
                            bytes[i * 2 + 1] = ((s shr 8) and 0xFF).toByte()
                        }
                        fos.write(bytes)
                    }
                    fos.close()
                    pcmToWav(tmp, File(path))
                    tmp.delete()
                } catch (e: Exception) {
                    running = false
                } finally {
                    runCatching { record?.stop() }
                    record?.release(); record = null
                }
            }.apply { start() }
            return 0
        } catch (e: Exception) {
            return -1
        }
    }

    override fun stop(): Int {
        val wasRunning = running
        running = false
        thread?.join(2500)
        return if (wasRunning) 0 else -1
    }

    private fun pcmToWav(pcm: File, out: File) {
        val dataSize = pcm.length().toInt()
        val fos = FileOutputStream(out)
        fun le(v: Int) {
            fos.write(v and 0xFF); fos.write((v shr 8) and 0xFF)
            fos.write((v shr 16) and 0xFF); fos.write((v shr 24) and 0xFF)
        }
        fos.write("RIFF".toByteArray()); le(36 + dataSize)
        fos.write("WAVE".toByteArray())
        fos.write("fmt ".toByteArray()); le(16)
        le(1); le(1)
        le(RATE); le(RATE * 2)
        le(2); le(16)
        fos.write("data".toByteArray()); le(dataSize)
        pcm.inputStream().use { it.copyTo(fos) }
        fos.close()
    }
}
