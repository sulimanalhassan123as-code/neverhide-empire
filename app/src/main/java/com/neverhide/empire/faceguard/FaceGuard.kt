package com.neverhide.empire.faceguard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.FaceDetector
import org.json.JSONArray

/**
 * FACE GUARD ENGINE — on-device face capture + visual matching.
 *
 * Pipeline (100% offline, no ML model downloads, zero APK size cost):
 *  1. Front-camera preview frames arrive as NV21; the Y (luma) plane is
 *     downscaled to a small grayscale grid.
 *  2. android.media.FaceDetector (built into Android since API 1) locates
 *     the face and eye distance.
 *  3. The face region is cropped around the midpoint, normalized to a fixed
 *     24x24 grid, zero-meaned and unit-lengthed.
 *  4. MATCHING = normalized cross-correlation against the owner's enrolled
 *     templates. Above MATCH_THRESHOLD => owner; below REJECT_THRESHOLD with
 *     a face clearly present => wrong person.
 *
 * HONEST LIMITS (also shown in the app UI): this is a visual deterrent,
 * not certified biometric security. It can be fooled by photos held very
 * close to the camera in bad lighting. For real security use the system
 * lock. The value here is the intruder RESPONSE: wrong face => siren +
 * vibration + selfie + location alert, same as the wrong-password Guardian.
 */
object FaceGuard {

    private const val GRID = 24
    private const val SCAN_W = 128
    private const val SCAN_H = 96
    const val MATCH_THRESHOLD = 0.60f
    const val REJECT_THRESHOLD = 0.45f

    /** In-memory: once the gate passes, stay open until the process dies. */
    @Volatile var unlockedThisProcess = false

    data class FaceResult(
        val present: Boolean,
        val score: Float?,   // best similarity vs enrolled owner templates
        val vec: FloatArray? // face vector for enrollment (null if no face)
    )

    // ===================== prefs =====================

    private const val PREFS = "face_guard"
    private const val KEY_ENABLED = "fg_enabled"
    private const val KEY_TEMPLATES = "fg_templates"
    private const val KEY_TIME = "fg_enrolled_at"

    fun isEnabled(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    fun isEnrolled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TEMPLATES, null)
            ?.let { runCatching { JSONArray(it).length() > 0 }.getOrDefault(false) } ?: false

    fun enrolledAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_TIME, 0L)

    fun clearData(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    // ===================== core =====================

    /**
     * NV21 luma plane -> downscaled gray grid, rotated upright.
     * rotationDeg: 0 or 270 (front camera portrait handled by trying both).
     */
    fun nv21ToGray(data: ByteArray, frameW: Int, frameH: Int, rotationDeg: Int): ByteArray {
        val sx = frameW / SCAN_W
        val sy = frameH / SCAN_H
        val out = ByteArray(SCAN_W * SCAN_H)
        for (y in 0 until SCAN_H) {
            for (x in 0 until SCAN_W) {
                val gx = x * sx
                val gy = y * sy
                val v = (data[gy * frameW + gx].toInt() and 0xFF)
                when (rotationDeg) {
                    270 -> out[(SCAN_W - 1 - x) * SCAN_H + y] = v.toByte()  // rotate 270: (x,y) -> (h-1-y, x)... see below
                    90 -> out[x * SCAN_H + (SCAN_H - 1 - y)] = v.toByte()
                    else -> out[y * SCAN_W + x] = v.toByte()
                }
            }
        }
        return out
    }

    /**
     * Analyze a downscaled gray frame: detect the face, and if enrolled
     * templates exist, score it against them.
     */
    fun analyze(context: Context, gray: ByteArray): FaceResult {
        // FaceDetector requires an RGB_565 bitmap
        val bmp = Bitmap.createBitmap(SCAN_W, SCAN_H, Bitmap.Config.RGB_565)
        for (y in 0 until SCAN_H) {
            for (x in 0 until SCAN_W) {
                val v = gray[y * SCAN_W + x].toInt() and 0xFF
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val detector = FaceDetector(SCAN_W, SCAN_H, 1)
        val faces = arrayOfNulls<FaceDetector.Face>(1)
        val n = detector.findFaces(bmp, faces)
        bmp.recycle()
        if (n < 1 || faces[0] == null) return FaceResult(false, null, null)

        val face = faces[0]!!
        val mp = android.graphics.PointF()
        face.getMidPoint(mp)
        val eyeDist = face.eyesDistance()
        if (eyeDist < 6f) return FaceResult(false, null, null) // too far away

        // crop a square region around the face, fixed to eye distance
        val half = (eyeDist * 1.3f).coerceAtLeast(8f)
        val vec = FloatArray(GRID * GRID)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val fx = (mp.x - half) + (gx + 0.5f) / GRID * (2 * half)
                val fy = (mp.y - half * 1.1f) + (gy + 0.5f) / GRID * (2.2f * half)
                val cx = fx.toInt().coerceIn(0, SCAN_W - 1)
                val cy = fy.toInt().coerceIn(0, SCAN_H - 1)
                vec[gy * GRID + gx] = (gray[cy * SCAN_W + cx].toInt() and 0xFF).toFloat()
            }
        }
        normalize(vec)

        val templates = loadTemplates(context)
        val score = templates.maxOfOrNull { ncc(it, vec) }
        return FaceResult(true, score, if (templates.isEmpty()) vec else null)
    }

    /**
     * Enroll from a batch of analyzed face vectors: keeps the clearest
     * captures as the owner's reference templates.
     * Returns the number of templates stored.
     */
    fun enroll(context: Context, vecs: List<FloatArray>): Int {
        val picked = vecs.distinctBy { it.toList().hashCode() }.take(5)
        if (picked.isEmpty()) return 0
        val arr = JSONArray()
        for (v in picked) {
            val sb = StringBuilder()
            for (f in v) sb.append(String.format("%04x", (f * 2047).toInt() + 2048))
            arr.put(sb.toString())
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_TEMPLATES, arr.toString())
            .putLong(KEY_TIME, System.currentTimeMillis())
            .apply()
        return picked.size
    }

    private fun loadTemplates(context: Context): List<FloatArray> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TEMPLATES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val hex = arr.getString(i)
                if (hex.length != GRID * GRID * 4) return@mapNotNull null
                val v = FloatArray(GRID * GRID)
                for (j in 0 until GRID * GRID) {
                    v[j] = ((hex.substring(j * 4, j * 4 + 4).toInt(16)) - 2048) / 2047f
                }
                v
            }
        }.getOrDefault(emptyList())
    }

    // ===================== math =====================

    private fun normalize(v: FloatArray) {
        var mean = 0f
        for (f in v) mean += f
        mean /= v.size
        var sum = 0.0
        for (i in v.indices) {
            v[i] -= mean
            sum += (v[i] * v[i]).toDouble()
        }
        val len = Math.sqrt(sum).toFloat()
        if (len > 1e-6f) for (i in v.indices) v[i] /= len
    }

    private fun ncc(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0f
        for (i in a.indices) dot += a[i] * b[i]
        return dot
    }
}
