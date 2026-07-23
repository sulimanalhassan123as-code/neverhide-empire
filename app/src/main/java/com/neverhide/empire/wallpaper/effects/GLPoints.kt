package com.neverhide.empire.wallpaper.effects

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Lightweight GL_POINTS renderer shared by the particle-style effects
 * (fire, galaxy, hologram nodes). Uploads a single dynamic vertex buffer and
 * animates it in the vertex shader to keep the CPU cost near zero (battery-friendly).
 */
class GLPoints(private val count: Int) {

    private val program: Int
    private val aSeed: Int
    private val uTime: Int
    private val uColorA: Int
    private val uColorB: Int
    private val uMode: Int
    private val uPointSize: Int
    private val buffer: FloatBuffer

    init {
        val vs = """
            precision mediump float;
            attribute vec3 aSeed;      // x,y random [-1,1], z speed
            uniform float uTime;
            uniform int uMode;         // 0 fire, 1 galaxy, 2 hologram
            uniform float uPointSize;
            varying float vLife;
            void main() {
                float t = uTime * aSeed.z;
                vec3 p = aSeed;
                if (uMode == 0) {           // fire: rise + flicker
                    float y = mod(aSeed.y + t * 0.4, 2.0) - 1.0;
                    float x = aSeed.x * 0.4 + sin(uTime * 2.0 + aSeed.y * 10.0) * 0.05;
                    p = vec3(x, y, 0.0);
                    vLife = 1.0 - (y * 0.5 + 0.5);
                } else if (uMode == 1) {    // galaxy: rotate around center
                    float ang = t * 0.3 + length(aSeed.xy) * 6.0;
                    float r = length(aSeed.xy);
                    p = vec3(cos(ang) * r, sin(ang) * r, 0.0);
                    vLife = 1.0 - r;
                } else {                     // hologram: gentle bob grid
                    p = vec3(aSeed.x, aSeed.y + sin(uTime + aSeed.x * 8.0) * 0.03, 0.0);
                    vLife = 0.8;
                }
                gl_Position = vec4(p, 1.0);
                gl_PointSize = uPointSize * (0.5 + vLife);
            }
        """
        val fs = """
            precision mediump float;
            uniform vec3 uColorA;
            uniform vec3 uColorB;
            varying float vLife;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                float d = length(c);
                if (d > 0.5) discard;               // round soft points
                float a = smoothstep(0.5, 0.0, d) * vLife;
                vec3 col = mix(uColorB, uColorA, vLife);
                gl_FragColor = vec4(col, a);
            }
        """
        program = link(vs, fs)
        aSeed = GLES20.glGetAttribLocation(program, "aSeed")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uColorA = GLES20.glGetUniformLocation(program, "uColorA")
        uColorB = GLES20.glGetUniformLocation(program, "uColorB")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        uPointSize = GLES20.glGetUniformLocation(program, "uPointSize")

        val data = FloatArray(count * 3)
        for (i in 0 until count) {
            data[i * 3] = (Math.random().toFloat() * 2f - 1f)
            data[i * 3 + 1] = (Math.random().toFloat() * 2f - 1f)
            data[i * 3 + 2] = (0.3f + Math.random().toFloat() * 0.9f)
        }
        buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(data); position(0) }
    }

    fun draw(time: Float, mode: Int, colorA: FloatArray, colorB: FloatArray, pointSize: Float) {
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aSeed)
        GLES20.glVertexAttribPointer(aSeed, 3, GLES20.GL_FLOAT, false, 0, buffer)
        GLES20.glUniform1f(uTime, time)
        GLES20.glUniform1i(uMode, mode)
        GLES20.glUniform1f(uPointSize, pointSize)
        GLES20.glUniform3fv(uColorA, 1, colorA, 0)
        GLES20.glUniform3fv(uColorB, 1, colorB, 0)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(aSeed)
    }

    private fun link(vs: String, fs: String): Int {
        fun c(type: Int, s: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, s); GLES20.glCompileShader(it)
        }
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, c(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(it, c(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(it)
        }
    }
}
