package com.neverhide.empire.wallpaper.effects

import android.opengl.GLES20

/** Rising fire particles: warm colours, upward motion. */
class FireParticles : Effect {
    private lateinit var points: GLPoints
    override fun init() { points = GLPoints(600) }
    override fun resize(width: Int, height: Int) {}
    override fun draw(time: Float) {
        GLES20.glClearColor(0.02f, 0.0f, 0.0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        points.draw(time, 0, floatArrayOf(1f, 0.85f, 0.2f), floatArrayOf(0.8f, 0.1f, 0f), 40f)
    }
}
