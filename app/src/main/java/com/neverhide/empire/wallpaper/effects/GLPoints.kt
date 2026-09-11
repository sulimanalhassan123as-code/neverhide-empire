package com.neverhide.empire.wallpaper.effects

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Lightweight GL_POINTS renderer shared by all particle-style wallpaper
 * effects. Uploads a single dynamic vertex buffer and animates it entirely in
 * the vertex shader, so CPU cost stays near zero (battery-friendly).
 *
 * Motion modes (uMode):
 *  0 fire      — rise + flicker
 *  1 galaxy    — rotate around center
 *  2 hologram  — gentle bob grid
 *  3 rain      — fast diagonal fall
 *  4 snow      — slow sway fall
 *  5 matrix    — vertical digital columns
 *  6 bubbles   — wobble rise (round points)
 *  7 starfield — radial drift from center
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
            uniform int uMode;
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
                } else if (uMode == 2) {    // hologram: gentle bob grid
                    p = vec3(aSeed.x, aSeed.y + sin(uTime + aSeed.x * 8.0) * 0.03, 0.0);
                    vLife = 0.8;
                } else if (uMode == 3) {    // rain: diagonal fall
                    float y = mod(aSeed.y + t * 0.9, 2.0) - 1.0;
                    float x = aSeed.x + y * 0.25;
                    p = vec3(x, y, 0.0);
                    vLife = 1.0 - abs(y);
                } else if (uMode == 4) {    // snow: slow sway fall
                    float y = mod(aSeed.y + t * 0.15, 2.0) - 1.0;
                    float x = aSeed.x * 0.9 + sin(uTime * 0.8 + aSeed.x * 20.0) * 0.08;
                    p = vec3(x, y, 0.0);
                    vLife = 1.0 - abs(y) * 0.3;
                } else if (uMode == 5) {    // matrix: digital columns
                    float y = mod(aSeed.y + t * 1.2, 2.0) - 1.0;
                    float x = floor(aSeed.x * 30.0) / 30.0;
                    p = vec3(x, y, 0.0);
                    vLife = 1.0 - (y * 0.5 + 0.5);
                } else if (uMode == 6) {    // bubbles: wobble rise
                    float y = mod(aSeed.y + t * 0.25 + 1.0, 2.0) - 1.0;
                    float x = aSeed.x * 0.85 + sin(uTime * 1.5 + aSeed.y * 6.0) * 0.06;
                    p = vec3(x, y, 0.0);
                    vLife = 1.0 - (y * 0.5 + 0.5);
                } else {                     // starfield: radial drift
                    float ang = atan(aSeed.y, aSeed.x);
                    float r = length(aSeed.xy) * mod(t * 0.2 + aSeed.z, 1.5);
                    p = vec3(cos(ang) * r, sin(ang) * r, 0.0);
                    vLife = 1.0 - r;
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
                float alpha = smoothstep(0.5, 0.0, d) * clamp(vLife, 0.1, 1.0);
                vec3 col = mix(uColorB, uColorA, clamp(vLife, 0.0, 1.0));
                gl_FragColor = vec4(col, alpha);
            }
        """
        program = buildProgram(vs, fs)

        aSeed = GLES20.glGetAttribLocation(program, "aSeed")
        uTime = GLES20.glGetUniformLocation(program, "uTime")
        uColorA = GLES20.glGetUniformLocation(program, "uColorA")
        uColorB = GLES20.glGetUniformLocation(program, "uColorB")
        uMode = GLES20.glGetUniformLocation(program, "uMode")
        uPointSize = GLES20.glGetUniformLocation(program, "uPointSize")

        // Seed data: random position + speed for each particle
        val data = FloatArray(count * 3)
        val rng = java.util.Random(42)
        for (i in 0 until count) {
            data[i * 3] = rng.nextFloat() * 2f - 1f
            data[i * 3 + 1] = rng.nextFloat() * 2f - 1f
            data[i * 3 + 2] = 0.5f + rng.nextFloat() * 1.5f
        }
        buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(data); position = 0
            }
    }

    fun draw(time: Float, mode: Int, colorA: FloatArray, colorB: FloatArray, pointSize: Float) {
        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(aSeed)
        GLES20.glVertexAttribPointer(aSeed, 3, GLES20.GL_FLOAT, false, 0, buffer)

        GLES20.glUniform1f(uTime, time)
        GLES20.glUniform1i(uMode, mode)
        GLES20.glUniform3f(uColorA, colorA[0], colorA[1], colorA[2])
        GLES20.glUniform3f(uColorB, colorB[0], colorB[1], colorB[2])
        GLES20.glUniform1f(uPointSize, pointSize)

        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(aSeed)
    }

    private fun buildProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vsSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fsSrc)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        return s
    }
}
