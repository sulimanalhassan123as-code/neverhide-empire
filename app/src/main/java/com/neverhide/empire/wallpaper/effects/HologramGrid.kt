package com.neverhide.empire.wallpaper.effects

import android.opengl.GLES20

/** Cyberpunk hologram: a bobbing grid of cyan nodes. */
class HologramGrid : Effect {
    private lateinit var points: GLPoints
    override fun init() { points = GLPoints(700) }
    override fun resize(width: Int, height: Int) {}
    override fun draw(time: Float) {
        GLES20.glClearColor(0.0f, 0.02f, 0.03f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        points.draw(time, 2, floatArrayOf(0.1f, 1f, 0.9f), floatArrayOf(0.0f, 0.4f, 0.5f), 22f)
    }
}
