package com.neverhide.empire.launcher

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import com.neverhide.empire.launcher.shapes.CircleLayout
import com.neverhide.empire.launcher.shapes.CubeLayout
import com.neverhide.empire.launcher.shapes.SphereLayout
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * Custom 3D launcher surface. Renders each installed app as a textured quad
 * placed by the active [Mode]. OpenGL ES 2.0 for guaranteed 60fps on min SDK 26.
 *
 *  - Drag  -> rotates the whole scene (with fling inertia).
 *  - Tap   -> screen-space picks the nearest tile and launches its app.
 */
class Launcher3D(context: Context) : GLSurfaceView(context) {

    enum class Mode { BOX, CIRCLE, SPHERE }

    private val renderer = SceneRenderer(context)

    init {
        setEGLContextClientVersion(2)
        // Transparent surface so the live wallpaper shows through behind tiles.
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    fun setMode(mode: Mode) { renderer.mode = mode }
    fun setTiles(tiles: List<AppTile>) { renderer.pendingTiles = tiles }

    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f
    private val tapSlop = 14f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y; downX = e.x; downY = e.y }
            MotionEvent.ACTION_MOVE -> {
                renderer.velY = (e.x - lastX) * 0.4f
                renderer.velX = (e.y - lastY) * 0.4f
                lastX = e.x; lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                if (abs(e.x - downX) < tapSlop && abs(e.y - downY) < tapSlop) {
                    renderer.requestPick(e.x, e.y, width, height)
                }
            }
        }
        return true
    }

    private class SceneRenderer(val context: Context) : Renderer {

        var mode: Mode = Mode.BOX
        @Volatile var pendingTiles: List<AppTile> = emptyList()
        private var tiles: List<AppTile> = emptyList()

        private val rotation = FloatArray(16)
        private val projection = FloatArray(16)
        private val view = FloatArray(16)
        private val mvp = FloatArray(16)
        private val model = FloatArray(16)
        var velX = 0f; var velY = 0f

        private var pickPending = false
        private var pickX = 0f; private var pickY = 0f
        private var vpW = 0; private var vpH = 0

        private var program = 0
        private var quad: TexturedQuad? = null

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 0f)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            Matrix.setIdentityM(rotation, 0)
            program = Shaders.buildTileProgram()
            quad = TexturedQuad(program)
            // Force re-upload of textures on a fresh GL context.
            tiles = emptyList()
        }

        override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
            GLES20.glViewport(0, 0, w, h)
            vpW = w; vpH = h
            val ratio = w.toFloat() / h
            Matrix.perspectiveM(projection, 0, 55f, ratio, 1f, 20f)
            Matrix.setLookAtM(view, 0, 0f, 0f, 7f, 0f, 0f, 0f, 0f, 1f, 0f)
        }

        override fun onDrawFrame(gl: GL10?) {
            if (pendingTiles !== tiles) {
                tiles = pendingTiles
                tiles.forEach { it.ensureTexture() }
            }

            applyRotation(velY, 0f, 1f, 0f)
            applyRotation(velX, 1f, 0f, 0f)
            velX *= 0.94f; velY *= 0.94f
            if (abs(velX) < 0.01f) velX = 0f
            if (abs(velY) < 0.01f) velY = 0f

            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val q = quad ?: return
            if (tiles.isEmpty()) return

            val positions = when (mode) {
                Mode.BOX -> CubeLayout.place(tiles.size)
                Mode.CIRCLE -> CircleLayout.place(tiles.size)
                Mode.SPHERE -> SphereLayout.place(tiles.size)
            }

            tiles.forEachIndexed { i, tile ->
                val p = positions[i]
                Matrix.setIdentityM(model, 0)
                Matrix.multiplyMM(model, 0, rotation, 0, model, 0)
                Matrix.translateM(model, 0, p.x, p.y, p.z)
                if (mode != Mode.BOX) billboard(model)
                Matrix.multiplyMM(mvp, 0, view, 0, model, 0)
                Matrix.multiplyMM(mvp, 0, projection, 0, mvp, 0)
                q.draw(mvp, tile.textureId)
                tile.lastMvp = mvp.copyOf()
            }

            if (pickPending) { doPick(); pickPending = false }
        }

        private fun applyRotation(angle: Float, x: Float, y: Float, z: Float) {
            if (angle == 0f) return
            val delta = FloatArray(16)
            Matrix.setRotateM(delta, 0, angle, x, y, z)
            val result = FloatArray(16)
            Matrix.multiplyMM(result, 0, delta, 0, rotation, 0)
            System.arraycopy(result, 0, rotation, 0, 16)
        }

        private fun billboard(m: FloatArray) {
            m[0] = 1f; m[1] = 0f; m[2] = 0f
            m[4] = 0f; m[5] = 1f; m[6] = 0f
            m[8] = 0f; m[9] = 0f; m[10] = 1f
        }

        fun requestPick(x: Float, y: Float, w: Int, h: Int) {
            pickX = x; pickY = y; vpW = w; vpH = h; pickPending = true
        }

        private fun doPick() {
            var best: AppTile? = null
            var bestDist = Float.MAX_VALUE
            val clip = FloatArray(4)
            tiles.forEach { tile ->
                val m = tile.lastMvp ?: return@forEach
                Matrix.multiplyMV(clip, 0, m, 0, floatArrayOf(0f, 0f, 0f, 1f), 0)
                if (clip[3] <= 0f) return@forEach
                val ndcX = clip[0] / clip[3]
                val ndcY = clip[1] / clip[3]
                val sx = (ndcX * 0.5f + 0.5f) * vpW
                val sy = (1f - (ndcY * 0.5f + 0.5f)) * vpH
                val d = (sx - pickX) * (sx - pickX) + (sy - pickY) * (sy - pickY)
                if (d < bestDist) { bestDist = d; best = tile }
            }
            val threshold = (vpW * 0.12f)
            if (bestDist < threshold * threshold) best?.launch(context)
        }
    }
}
