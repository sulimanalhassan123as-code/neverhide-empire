package com.neverhide.empire.wallpaper.effects

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Full-screen animated water: a fragment shader draws layered sine waves.
 * Two triangles only, so it is extremely cheap on the GPU.
 */
class WaterWaves : Effect {
    private var program = 0
    private var aPos = 0
    private var uTime = 0
    private lateinit var quad: FloatBuffer

    override fun init() {
        val vs = """
            attribute vec2 aPos;
            varying vec2 vUv;
            void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }
        """
        val fs = """
            precision mediump float;
            varying vec2 vUv;
            uniform float uTime;
            void main() {
                float w = sin(vUv.x * 12.0 + uTime * 1.5) * 0.5
                        + sin(vUv.y * 8.0 - uTime * 1.1) * 0.5;
                float shade = 0.5 + 0.25 * w;
                vec3 deep = vec3(0.0, 0.15, 0.35);
                vec3 crest = vec3(0.1, 0.5, 0.8);
                gl_FragColor = vec4(mix(deep, crest, shade), 1.0);
            }
        """
        program = link(vs, fs)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        val v = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        quad = ByteBuffer.allocateDirect(v.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
    }

    override fun resize(width: Int, height: Int) {}

    override fun draw(time: Float) {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, quad)
        GLES20.glUniform1f(uTime, time)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
    }

    private fun link(vs: String, fs: String): Int {
        fun c(t: Int, s: String) = GLES20.glCreateShader(t).also {
            GLES20.glShaderSource(it, s); GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, c(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, c(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }
}
