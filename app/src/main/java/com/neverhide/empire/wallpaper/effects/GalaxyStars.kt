package com.neverhide.empire.wallpaper.effects

import android.opengl.GLES20

/** Spiralling galaxy of stars rotating around the centre. */
class GalaxyStars : Effect {
    private lateinit var points: GLPoints
    override fun init() { points = GLPoints(900) }
    override fun resize(width: Int, height: Int) {}
    override fun draw(time: Float) {
        GLES20.glClearColor(0.01f, 0.01f, 0.05f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        points.draw(time, 1, floatArrayOf(0.8f, 0.9f, 1f), floatArrayOf(0.5f, 0.2f, 0.8f), 18f)
    }
}
