package com.neverhide.empire.launcher

import android.opengl.GLES20
import java.nio.FloatBuffer

/**
 * A unit quad (centred at origin) that draws a single texture with a given MVP.
 * Reused for every app tile to avoid per-frame allocations.
 */
class TexturedQuad(private val program: Int) {

    private val half = 0.42f
    private val vertices: FloatBuffer = GLMath.floatBuffer(
        floatArrayOf(
            -half,  half, 0f,
            -half, -half, 0f,
             half,  half, 0f,
             half, -half, 0f
        )
    )
    private val texCoords: FloatBuffer = GLMath.floatBuffer(
        floatArrayOf(0f, 0f,  0f, 1f,  1f, 0f,  1f, 1f)
    )

    private val aPos = GLES20.glGetAttribLocation(program, "aPos")
    private val aTex = GLES20.glGetAttribLocation(program, "aTex")
    private val uMvp = GLES20.glGetUniformLocation(program, "uMvp")
    private val uTex = GLES20.glGetUniformLocation(program, "uTex")

    fun draw(mvp: FloatArray, textureId: Int) {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, vertices)
        GLES20.glEnableVertexAttribArray(aTex)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, texCoords)

        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(uTex, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aTex)
    }
}
