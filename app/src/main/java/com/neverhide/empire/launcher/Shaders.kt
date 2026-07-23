package com.neverhide.empire.launcher

import android.opengl.GLES20

/** Compiles the simple textured-quad shader program used for app tiles. */
object Shaders {

    private const val VERTEX = """
        uniform mat4 uMvp;
        attribute vec4 aPos;
        attribute vec2 aTex;
        varying vec2 vTex;
        void main() {
            vTex = aTex;
            gl_Position = uMvp * aPos;
        }
    """

    private const val FRAGMENT = """
        precision mediump float;
        uniform sampler2D uTex;
        varying vec2 vTex;
        void main() {
            vec4 c = texture2D(uTex, vTex);
            if (c.a < 0.05) discard;   // keep icon edges clean
            gl_FragColor = c;
        }
    """

    fun buildTileProgram(): Int {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT)
        return GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
    }

    private fun compile(type: Int, src: String): Int =
        GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, src)
            GLES20.glCompileShader(it)
        }
}
