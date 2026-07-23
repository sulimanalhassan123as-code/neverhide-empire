package com.neverhide.empire.wallpaper

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.neverhide.empire.wallpaper.effects.Effect
import com.neverhide.empire.wallpaper.effects.FireParticles
import com.neverhide.empire.wallpaper.effects.GalaxyStars
import com.neverhide.empire.wallpaper.effects.HologramGrid
import com.neverhide.empire.wallpaper.effects.WaterWaves
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Drives whichever [Effect] is currently selected. Uses a monotonic clock so
 * animations are frame-rate independent.
 */
class WallpaperRenderer(private val effectId: Int) : GLSurfaceView.Renderer {

    companion object {
        const val FIRE = 0
        const val WATER = 1
        const val GALAXY = 2
        const val HOLOGRAM = 3
    }

    private lateinit var effect: Effect
    private var startNanos = 0L

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE)
        effect = when (effectId) {
            WATER -> WaterWaves()
            GALAXY -> GalaxyStars()
            HOLOGRAM -> HologramGrid()
            else -> FireParticles()
        }
        effect.init()
        startNanos = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        effect.resize(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val time = (System.nanoTime() - startNanos) / 1_000_000_000f
        effect.draw(time)
    }
}
