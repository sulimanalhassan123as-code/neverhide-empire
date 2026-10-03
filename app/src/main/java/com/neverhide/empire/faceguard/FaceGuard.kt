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
 *     downscaled to a small SQUARE grayscale grid (96x96).
 *  2. The grid is checked in 3 orientations (0°, 90°, 270°) — sensor frames
 *     are landscape while the phone is portrait, so the upright orientation
 *     is whatever the lighting/rotation of the moment makes it; the pass
 *     with the HIGHEST face-confidence wins. Square grid makes rotation
 *     index math trivially safe.
 *  3. android.media.FaceDetector (built into Android) locates the face and
 *     eye distance in the winning orientation.
 *  4. The face region is cropped around the midpoint, normalized to a fixed
 *     24x24 grid, zero-meaned and unit-lengthed.
 *  5. MATCHING = normalized cross-correlation against the owner's enrolled
 *     templates. Enrollment and gating use the identical pipeline, so the
 *     same physical pose produces comparable templates.
 *
 * HONEST LIMITS (also shown in the app UI): this is a visual deterrent,
 * not certified biometric security. It can be fooled by photos held very
 * close to the camera in bad lighting. For real security use the system
 * lock. The value here is the intruder RESPONSE: wrong face => siren +
 * vibration + selfie + location alert, same as the wrong-password Guardian.
 */
object FaceGuard {

    private const val GRID = 24
    private const val SCAN = 96           // square scan grid — rotation-safe
    const val MATCH_THRESHOLD = 0.60f
    const val REJECT_THRESHOLD = 0.45f

    /** In-memory: once the gate passes, stay open until the process dies. */
    @Volatile var unlockedThisProcess = false

    data class FaceResult(
        val present: Boolean,
        val score: Float?,   // best similarity vs enrolled owner templates
        val vec: FloatArray? // face vector for enrollment (null if templates exist)
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

    // ===================== frame prep =====================

    /** NV21 luma plane -> 96x96 square gray grid (row-major, in[y*SCAN+x]). */
    fun nv21ToGray(data: ByteArray, frameW: Int, frameH: Int): ByteArray {
        val sx = (frameW / SCAN).coerceAtLeast(1)
        val sy = (frameH / SCAN).coerceAtLeast(1)
        val out = ByteArray(SCAN * SCAN)
        for (y in 0 until SCAN) {
            val gy = y * sy
            for (x in 0 until SCAN) {
                out[y * SCAN + x] = data[gy * frameW + x * sx]
            }
        }
        return out
    }

    /** 90° clockwise rotation of the square grid: out[y][x] = in[N-1-x][y]. */
    fun rot90(g: ByteArray): ByteArray {
        val out = ByteArray(SCAN * SCAN)
        for (y in 0 until SCAN)
            for (x in 0 until SCAN)
                out[y * SCAN + x] = g[(SCAN - 1 - x) * SCAN + y]
        return out
    }

    /** 270° clockwise (= 90° CCW): out[y][x] = in[x][N-1-y]. */
    fun rot270(g: ByteArray): ByteArray {
        val out = ByteArray(SCAN * SCAN)
        for (y in 0 until SCAN)
            for (x in 0 until SCAN)
                out[y * SCAN + x] = g[x * SCAN + (SCAN - 1 - y)]
        return out
    }

    // ===================== core =====================

    private class Hit(val gray: ByteArray, val face: FaceDetector.Face)

    /**
     * Analyze one camera frame: detect the best face across the 3
     * orientations (confidence-ranked), then score it against the enrolled
     * owner templates.
     */
    fun analyze(context: Context, data: ByteArray, frameW: Int, frameH: Int): FaceResult {
        val base = nv21ToGray(data, frameW, frameH)
        val candidates = listOf(base, rot90(base), rot270(base))

        var best: Hit? = null
        var bestConf = 0f
        for (g in candidates) {
            val f = detect(g) ?: continue
            if (f.confidence() > bestConf) {
                bestConf = f.confidence()
                best = Hit(g, f)
            }
        }
        val hit = best ?: return FaceResult(false, null, null)
        if (hit.face.eyesDistance() < 5f) return FaceResult(false, null, null) // too far away

        val vec = extractVec(hit.gray, hit.face)
        val templates = loadTemplates(context)
        val score = templates.maxOfOrNull { ncc(it, vec) }
        return FaceResult(true, score, if (templates.isEmpty()) vec else null)
    }

    /** Runs FaceDetector on one gray grid; null if no face. */
    private fun detect(gray: ByteArray): FaceDetector.Face? {
        // FaceDetector requires an RGB_565 bitmap (width must be even — 96 is)
        val bmp = Bitmap.createBitmap(SCAN, SCAN, Bitmap.Config.RGB_565)
        for (y in 0 until SCAN) {
            for (x in 0 until SCAN) {
                val v = gray[y * SCAN + x].toInt() and 0xFF
                bmp.setPixel(x, y, Color.rgb(v, v, v))
            }
        }
        val detector = FaceDetector(SCAN, SCAN, 1)
        val faces = arrayOfNulls<FaceDetector.Face>(1)
        val n = detector.findFaces(bmp, faces)
        bmp.recycle()
        return if (n > 0) faces[0] else null
    }

    /** Crop a normalized 24x24 vector around the detected face. */
    private fun extractVec(gray: ByteArray, face: FaceDetector.Face): FloatArray {
        val mp = android.graphics.PointF()
        face.getMidPoint(mp)
        val eyeDist = face.eyesDistance()
        val half = (eyeDist * 1.3f).coerceAtLeast(8f)
        val vec = FloatArray(GRID * GRID)
        for (gy in 0 until GRID) {
            for (gx in 0 until GRID) {
                val fx = (mp.x - half) + (gx + 0.5f) / GRID * (2 * half)
                val fy = (mp.y - half * 1.1f) + (gy + 0.5f) / GRID * (2.2f * half)
                val cx = fx.toInt().coerceIn(0, SCAN - 1)
                val cy = fy.toInt().coerceIn(0, SCAN - 1)
                vec[gy * GRID + gx] = (gray[cy * SCAN + cx].toInt() and 0xFF).toFloat()
            }
        }
        normalize(vec)
        return vec
    }

    /**
     * Enroll from a batch of face vectors: keeps up to 5 distinct captures
     * as the owner's reference templates. Returns the count stored.
     */
    fun enroll(context: Context, vecs: List<FloatArray>): Int {
        val picked = vecs.take(5)
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
                    v[j] = (hex.substring(j * 4, j * 4 + 4).toInt(16) - 2048) / 2047f
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
