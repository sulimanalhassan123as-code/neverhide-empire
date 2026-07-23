package com.neverhide.empire.launcher.shapes

import kotlin.math.ceil
import kotlin.math.sqrt

/** BOX mode: distribute apps across the 6 faces of a cube (grid per face). */
object CubeLayout {
    fun place(count: Int): List<Vec3> {
        if (count == 0) return emptyList()
        val out = ArrayList<Vec3>(count)
        val perFace = ceil(count / 6.0).toInt().coerceAtLeast(1)
        val cols = ceil(sqrt(perFace.toDouble())).toInt().coerceAtLeast(1)
        val step = 2f / (cols + 1)
        val faces: List<(Float, Float) -> Vec3> = listOf(
            { u, v -> Vec3(u, v, 1.6f) },
            { u, v -> Vec3(u, v, -1.6f) },
            { u, v -> Vec3(1.6f, v, u) },
            { u, v -> Vec3(-1.6f, v, u) },
            { u, v -> Vec3(u, 1.6f, v) },
            { u, v -> Vec3(u, -1.6f, v) }
        )
        var i = 0
        for (f in faces) for (r in 0 until cols) for (c in 0 until cols) {
            if (i >= count) return out
            val u = -1f + step * (c + 1)
            val v = -1f + step * (r + 1)
            out.add(f(u, v)); i++
        }
        return out
    }
}
