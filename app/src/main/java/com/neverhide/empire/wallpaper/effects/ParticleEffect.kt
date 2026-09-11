package com.neverhide.empire.wallpaper.effects

import android.opengl.GLES20

/**
 * A single parameterized particle effect. Every one of the 20 catalog
 * wallpapers is just a different config of this class — that's how we keep
 * the app small while offering a huge variety.
 */
class ParticleEffect(
    private val mode: Int,
    private val colorA: FloatArray,
    private val colorB: FloatArray,
    private val count: Int,
    private val pointSize: Float,
    private val bg: FloatArray
) : Effect {

    private lateinit var points: GLPoints

    override fun init() {
        points = GLPoints(count)
    }

    override fun resize(width: Int, height: Int) {}

    override fun draw(time: Float) {
        GLES20.glClearColor(bg[0], bg[1], bg[2], 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        points.draw(time, mode, colorA, colorB, pointSize)
    }
}
